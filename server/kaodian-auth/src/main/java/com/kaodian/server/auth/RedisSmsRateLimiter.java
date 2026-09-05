package com.kaodian.server.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SmsRateLimiter} 的 Redis 实现 —— {@code kaodian.auth.sms.store=redis} 时生效。
 *
 * <h2>它挡的仍然是账单</h2>
 *
 * 三个数字与 {@link FileSmsRateLimiter} 逐条相同(docs/technical/INDEX.md §6.1):
 * 单号 1/60s、单号 10/日、单 IP 20/日。「日」按 {@code kaodian.auth.sms.zone}
 * (默认 {@code Asia/Shanghai})的自然日算,与文件版<b>读同一个配置项</b> ——
 * 用户看到的「明天 0 点恢复」里的「明天」是北京时间的明天,换成 UTC 这句话晚上 8 点后就是错的。
 *
 * <h2>全部 key 与 TTL</h2>
 *
 * <table border="1">
 *   <caption>这个类会写出来的键,逐个列举</caption>
 *   <tr><th>key</th><th>类型</th><th>TTL</th><th>装什么</th></tr>
 *   <tr><td>{@code kaodian:sms:rate:last:<phoneHmac>}</td><td>string</td><td>24 小时</td>
 *       <td>上一次发送时刻的 epochMilli。<b>60 秒冷却的唯一依据</b></td></tr>
 *   <tr><td>{@code kaodian:sms:rate:phone:<yyyy-MM-dd>:<phoneHmac>}</td><td>counter</td>
 *       <td>到次日 0 点({@code EXPIREAT})</td><td>这个号今天发了几条</td></tr>
 *   <tr><td>{@code kaodian:sms:rate:ip:<yyyy-MM-dd>:<ip>}</td><td>counter</td>
 *       <td>到次日 0 点({@code EXPIREAT})</td><td>这个 IP 今天发了几条</td></tr>
 * </table>
 *
 * <p>🔴 <b>冷却与日额是两个键、两条 TTL,不许合并成一个 hash 用一个 TTL。</b>
 * 文件版的 {@code today(...)} 在跨日重置计数时<b>显式把 lastAt 带过去</b> ——
 * 60 秒冷却不随午夜清零。合并成一个键就等于让 23:59:30 发出的那条在半分钟后
 * 免掉冷却:一个恰好卡在午夜的刷子会拿到双倍速率。
 *
 * <p>日额的正确性来自<b>键名里的那个自然日</b>,不来自 TTL:跨日就是另一个键,天然从 0 开始,
 * 不需要任何定时任务。{@code EXPIREAT} 只负责把昨天的键收走,
 * 因此它用的是 Redis 服务端时钟而判定用的是应用时钟这件事不影响结果。
 *
 * <h2>🔴 整个 reserve 是一个 Lua 脚本</h2>
 *
 * 「先 {@code INCR} 再判上限」在并发下会超发:N 个请求各自 INCR 到 21、22、23……
 * 然后各自发现超了,而短信已经发出去了。「先判再 INCR」拆成两次往返同样超发 ——
 * 所有人都读到 19。所以判定与占用<b>在一次脚本执行里完成</b>,
 * 这正是 {@code ConcurrencyTest#rateLimiterDoesNotOverGrant} 断言的那条上限。
 * <b>先占再发</b>:名额是发送之前扣的,不是之后。
 *
 * <p>判定顺序也是脚本的一部分,与文件版逐句相同:
 * ① 冷却 → ② 单号日额 → ③ 单 IP 日额 → ④ 两个计数器一起 +1 并刷新 lastAt。
 *
 * <h2>🔴 IP 取不到时整道闸跳过,而且不建键</h2>
 *
 * {@code ip} 为 {@code null} 或空白时,脚本只收到两个 key,第三道闸根本不执行,
 * 计数也不加 —— <b>登录不能因为拿不到调用方地址就失败</b>
 * (与 {@code ClientIp} 那一侧「记录动作永不失败」是同一条)。
 * 反过来,拿空串去拼一个 {@code ...:ip:} 结尾的键就是文件版注释里骂的那件事:
 * 所有取不到 IP 的请求共享一个桶,20 条一到,全站的登录一起被自己挡住。
 *
 * <h2>与文件版刻意不同的地方</h2>
 *
 * <ul>
 *   <li><b>lastAt 有 24 小时 TTL,文件版是永久保留。</b> 观察不到差别:冷却只在 60 秒内有意义,
 *       24 小时没动静的号无论键在不在,判定都是「已过冷却」。给 TTL 只是为了不留下一个只增不减的键空间</li>
 *   <li><b>{@link #releaseDaily} 用的是墙上时钟的今天。</b> 这个方法的签名里没有 {@code Instant}
 *       (接口一个字不能改),而 Redis 这一侧的日额键名里带日期,退额度必须先选一天。
 *       退额度总是紧跟在同一次请求的 {@code reserve} 之后,两者落在不同自然日只可能发生在
 *       跨午夜的那一瞬间;真撞上时新那天的计数是 0,脚本按下限 0 处理,用户损失一次退还。
 *       文件版按记录里存的那一天减,没有这个缝</li>
 *   <li><b>三个上限常量在这里重新声明了一份。</b> 文件版里它们是 {@code private},
 *       而那个类这次<b>除了条件注解之外一个字不动</b>。两份数字必须一起改,已在各自注释里标了出处</li>
 * </ul>
 *
 * <p>其余语义逐条相同,包括 {@link #releaseDaily} <b>只还日额度、不还 60 秒冷却</b>:
 * 日额度是用户的,我们自己的供应商故障不该吃掉它;冷却是系统的,
 * 还回去等于允许客户端对着一个正在故障的接口连打。
 *
 * <p>换钥的副作用照旧成立({@link PhoneKeyGuard#sideEffectNotice()}):日额键仍然按
 * {@code phoneHmac} 建,而这些键<b>不参与换钥</b> —— 里面有还没有账号的号,算不出新 HMAC。
 * 换钥之后当日频控计数清零,与文件版是同一份代价。
 *
 * <p>已知天花板:三个 key 没有 hash tag,{@code reserve} 在 Redis Cluster 下会因跨槽被拒。
 * 当前部署形态是单实例({@code spring.data.redis.host/port}),真要上 cluster 时给键加
 * {@code {phoneHmac}} 标签即可 —— 但那会把 IP 维度也绑到号上,届时要重新想。
 */
@Component
@ConditionalOnProperty(name = "kaodian.auth.sms.store", havingValue = "redis")
public class RedisSmsRateLimiter implements SmsRateLimiter {

    /** 全部 key 的统一前缀。类注释里列了三种形状。 */
    private static final String PREFIX = "kaodian:sms:rate:";

    /** docs/technical/INDEX.md §6.1:单号 1/60s。与 {@code FileSmsRateLimiter} 的同名常量必须一致。 */
    private static final Duration PER_PHONE_COOLDOWN = Duration.ofSeconds(60);

    /** docs/technical/INDEX.md §6.1:单号 10/日。与 {@code FileSmsRateLimiter} 的同名常量必须一致。 */
    private static final int PER_PHONE_DAILY = 10;

    /** docs/technical/INDEX.md §6.1:单 IP 20/日。与 {@code FileSmsRateLimiter} 的同名常量必须一致。 */
    private static final int PER_IP_DAILY = 20;

    /** 冷却键活多久。见类注释「与文件版刻意不同的地方」—— 任何大于 60 秒的值都等价。 */
    private static final Duration LAST_AT_TTL = Duration.ofHours(24);

    /**
     * 🔴 判定 + 占用在一次执行里完成 —— 拆开就会超发。
     *
     * <p>{@code KEYS} = 冷却键、单号日额键、[单 IP 日额键];第三个只有 IP 非空时才传,
     * 于是「IP 取不到就整道闸跳过、不建键」这件事由<b>传不传 key</b> 表达,而不是由脚本里的空串判断。
     * <p>{@code ARGV} = nowMillis、冷却毫秒、单号上限、单 IP 上限、次日零点 epochSecond、冷却键 TTL 秒。
     * <p>回值是一个字符串:{@code TOO_FREQUENT|<lastAtMillis>} / {@code PHONE_DAILY} /
     * {@code IP_DAILY} / {@code ALLOWED|<phoneCount>|<ipCount>}。
     * 冷却那一支把 Java 自己写进去的原字符串<b>原样带回</b>,
     * 由 Java 加上 60 秒算出准确的 retryAt —— 避免在 Lua 里格式化大整数。
     */
    private static final RedisScript<String> RESERVE = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local last = redis.call('GET', KEYS[1])
            if last and now < tonumber(last) + tonumber(ARGV[2]) then
                return 'TOO_FREQUENT|' .. last
            end
            if tonumber(redis.call('GET', KEYS[2]) or '0') >= tonumber(ARGV[3]) then
                return 'PHONE_DAILY'
            end
            local hasIp = #KEYS >= 3
            if hasIp and tonumber(redis.call('GET', KEYS[3]) or '0') >= tonumber(ARGV[4]) then
                return 'IP_DAILY'
            end
            local phone = redis.call('INCR', KEYS[2])
            redis.call('EXPIREAT', KEYS[2], ARGV[5])
            local ip = 0
            if hasIp then
                ip = redis.call('INCR', KEYS[3])
                redis.call('EXPIREAT', KEYS[3], ARGV[5])
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[6])
            return string.format('ALLOWED|%d|%d', phone, ip)
            """, String.class);

    /**
     * 每个计数器减一,下限 0。
     *
     * <p>🔴 键不存在时<b>不建键</b>({@code DECR} 会从 0 减成 -1 并建出一个键来),
     * 也不碰 TTL —— {@code DECR} 保留原有过期时间。
     * <p>🔴 <b>冷却键不在 KEYS 里</b>:只还日额度,不还 60 秒冷却。
     */
    private static final RedisScript<Long> RELEASE_DAILY = new DefaultRedisScript<>("""
            for i = 1, #KEYS do
                if tonumber(redis.call('GET', KEYS[i]) or '0') > 0 then
                    redis.call('DECR', KEYS[i])
                end
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ZoneId zone;

    public RedisSmsRateLimiter(StringRedisTemplate redis,
                               @Value("${kaodian.auth.sms.zone:Asia/Shanghai}") String zone) {
        this.redis = redis;
        this.zone = ZoneId.of(zone);
    }

    @Override
    public Decision reserve(String phoneHmac, String ip, Instant now) {
        String day = LocalDate.ofInstant(now, zone).toString();
        List<String> keys = new ArrayList<>(3);
        keys.add(lastKey(phoneHmac));
        keys.add(phoneDayKey(day, phoneHmac));
        if (hasIp(ip)) {
            keys.add(ipDayKey(day, ip));
        }

        String out = redis.execute(RESERVE, keys,
                String.valueOf(now.toEpochMilli()),
                String.valueOf(PER_PHONE_COOLDOWN.toMillis()),
                String.valueOf(PER_PHONE_DAILY),
                String.valueOf(PER_IP_DAILY),
                String.valueOf(nextMidnight(now).getEpochSecond()),
                String.valueOf(LAST_AT_TTL.toSeconds()));
        if (out == null) {
            throw new IllegalStateException("预约短信名额时 Redis 没有回值");
        }

        String[] parts = out.split("\\|");
        return switch (parts[0]) {
            case "TOO_FREQUENT" -> new Decision.TooFrequent(
                    Instant.ofEpochMilli(Long.parseLong(parts[1])).plus(PER_PHONE_COOLDOWN));
            case "PHONE_DAILY" -> new Decision.PhoneDailyExhausted(nextMidnight(now), PER_PHONE_DAILY);
            case "IP_DAILY" -> new Decision.IpDailyExhausted(nextMidnight(now), PER_IP_DAILY);
            case "ALLOWED" -> new Decision.Allowed(
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            default -> throw new IllegalStateException("频控脚本回了看不懂的结果:" + out);
        };
    }

    @Override
    public void releaseDaily(String phoneHmac, String ip) {
        // 这个方法的签名里没有时刻(接口一个字不能改),而日额键名里带日期 ——
        // 只能按墙上时钟的今天退。退额度紧跟在同一次请求的 reserve 之后,
        // 唯一对不上的情形是恰好跨了午夜,那时新一天的计数是 0,脚本按下限 0 处理。
        String day = LocalDate.now(zone).toString();
        List<String> keys = new ArrayList<>(2);
        keys.add(phoneDayKey(day, phoneHmac));
        if (hasIp(ip)) {
            keys.add(ipDayKey(day, ip));
        }
        redis.execute(RELEASE_DAILY, keys);
    }

    // —— key ——

    /** 🔴 空串 / null 都不算 IP:不建键、不计数,整道闸跳过。 */
    private static boolean hasIp(String ip) {
        return ip != null && !ip.isBlank();
    }

    private static String lastKey(String phoneHmac) {
        return PREFIX + "last:" + phoneHmac;
    }

    private static String phoneDayKey(String day, String phoneHmac) {
        return PREFIX + "phone:" + day + ":" + phoneHmac;
    }

    private static String ipDayKey(String day, String ip) {
        return PREFIX + "ip:" + day + ":" + ip;
    }

    private Instant nextMidnight(Instant now) {
        return LocalDate.ofInstant(now, zone).plusDays(1).atStartOfDay(zone).toInstant();
    }
}
