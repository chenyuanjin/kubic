package com.kaodian.server.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link SmsCodeStore} 的 Redis 实现 —— {@code kaodian.auth.sms.store=redis} 时生效。
 *
 * <h2>为什么要有它:{@code synchronized} 的前提是「整个进程一份」</h2>
 *
 * {@link FileSmsCodeStore} 的每一条不变式都靠一把进程内的 {@code synchronized} 撑着。
 * 那把锁在<b>第二个进程起来的那一刻无声地失效</b> —— 不报错、不告警,
 * 只是「错 5 次锁定」和「单次使用」从此各算各的。Redis 把这把锁挪到进程外面,
 * 让上面那两条在多实例下仍然成立。
 *
 * <h2>全部 key 与 TTL</h2>
 *
 * <table border="1">
 *   <caption>这个类会写出来的键,逐个列举</caption>
 *   <tr><th>key</th><th>类型</th><th>TTL</th><th>装什么</th></tr>
 *   <tr><td>{@code kaodian:sms:code:latest:<phoneHmac>}</td><td>hash</td><td>30 分钟</td>
 *       <td>最新一条码的六个字段</td></tr>
 *   <tr><td>{@code kaodian:sms:code:superseded:<phoneHmac>}</td><td>hash</td><td>30 分钟</td>
 *       <td>被顶掉的上一条,状态已改成 {@code SUPERSEDED}</td></tr>
 *   <tr><td>{@code kaodian:sms:lock:<phoneHmac>}</td><td>hash</td><td>24 小时</td>
 *       <td>{@code failedCount} 与 {@code lockedUntil}</td></tr>
 * </table>
 *
 * <p>hash 的字段名与 {@code auth-sms.json} 里逐字段写出来的那几个<b>一模一样</b>:
 * 谁的存储里能出现哪些键,由代码逐字列举,不交给反射序列化器决定。
 *
 * <h2>🔴 与文件版刻意不同的两处,以及各自的理由</h2>
 *
 * <h3>一、码的 TTL 是 30 分钟,不是 5 分钟</h3>
 *
 * 验证码<b>本身</b>是 5 分钟有效({@link SmsCodeService#CODE_TTL}),但把 TTL 设成 5 分钟是错的:
 * {@link SmsCode.State#EXPIRED} 是 {@link SmsCode#effectiveStateAt} <b>从时间派生</b>出来的,
 * 不是靠把记录删掉实现的。key 一旦消失,{@link #findLatest} 返回空,
 * 服务层回的就是「没有待验证的码」而不是「已过期,请重发」——
 * {@link SmsCodeStore} 类注释里那<b>四句不同的话塌成三句</b>,
 * 而用户会对着一条早该重发的码继续琢磨自己是不是输错了。
 * <p>
 * 所以 TTL 取一个<b>明显长于码有效期</b>的值,只当垃圾回收用;判过期的仍然是时间。
 * 30 分钟之后记录才真的消失,那时用户听到的是「请先获取验证码」—— 也已经是对的话了。
 *
 * <h3>二、失败计数器的 TTL 是 24 小时,不是几十秒</h3>
 *
 * 文件版的 {@code failedCount} <b>没有时间衰减</b>:只在锁上的那一刻和校验成功时归零。
 * 用 {@code INCR + EXPIRE 60s} 那种写法会把「错 5 次锁 30 分钟」悄悄放松成
 * 「错 4 次、等一会儿就白错了」—— 一道闸被改成了一个节流阀。
 * <p>
 * 24 小时是这个实现<b>唯一一处真的把闸放松了</b>的地方:连续 24 小时没有再错过的号,
 * 计数会归零(文件版不会)。这是 Redis 这一侧为「不需要清理任务」付的价,
 * 量级上够不着攻击者(5 次里的前 4 次要拉开一整天),
 * 而它换来的是<b>不会有一个只增不减的键空间</b>。
 * <p>
 * 另外两处与文件版不同但<b>观察不到</b>,记在这里免得下次有人以为是 bug:
 * ① 作废槽同样是 30 分钟 TTL,而服务层只在码发出后 5 分钟内才会回「请用最新收到的那一条」,
 *    30 分钟永远够用;② 已核销的码在 30 分钟后整条消失,而文件版留着 —— 两种情况下
 *    用户听到的都是「没有待验证的码」,是同一句话。
 * <p>
 * 还有一处不是语义而是运维:key 里带着 {@code phoneHmac}(仍然只有哈希,没有号码原文),
 * 而 Redis 的 key 会出现在 {@code MONITOR} 与慢日志里 —— 文件版的哈希只躺在数据文件里。
 *
 * <h2>哪几步靠 Lua 保证原子</h2>
 *
 * 四个脚本,各自钉住一条不变式:
 * <ul>
 *   <li>{@code ISSUE} —— 「旧码只有还在飞的时候才进作废槽,否则那个槽必须清空」。
 *       读旧状态和写新码之间不能有别人插进来</li>
 *   <li>{@code CONSUME_IF_SENT} —— <b>单次使用</b>。比对与改状态是一次 compare-and-set,
 *       不是「读出来 → 比 → 写回去」。见 {@link SmsCodeStore#consumeIfSent}</li>
 *   <li>{@code DISCARD} —— 「先比 codeHmac 再删」。比与删拆开的话,
 *       这一瞬间刚发出的新码会被上一条的清理误伤</li>
 *   <li>{@code RECORD_FAILURE} —— <b>读-改-写</b>。拆开写的话两个并发的错误猜测会都读到同一个
 *       {@code failedCount}、都写 +1,计数只前进一格,「错 5 次锁定」在并发下变成「错 10 次锁定」</li>
 * </ul>
 *
 * <h2>换钥的副作用照旧成立</h2>
 *
 * {@link PhoneKeyGuard#sideEffectNotice()} 那句话在 Redis 上一个字都不用改:
 * 这里的三个 key 仍然按 {@code phoneHmac} 建,而 {@code phoneHmac} 是换钥要换的那个东西,
 * 这些键<b>同样不参与换钥</b>(里面有还没有账号的号,算不出新 HMAC)。
 * 于是换钥之后,未核销的验证码作废、号码锁定清零 —— 与文件版是同一个副作用,同一份代价。
 */
@Component
@ConditionalOnProperty(name = "kaodian.auth.sms.store", havingValue = "redis")
public class RedisSmsCodeStore implements SmsCodeStore {

    /** 全部 key 的统一前缀。类注释里列了三种形状。 */
    private static final String PREFIX = "kaodian:sms:";

    /**
     * 🔴 记录活多久 —— <b>不是</b>验证码活多久。
     *
     * <p>码的有效期是 5 分钟({@link SmsCodeService#CODE_TTL}),但 key 必须活得明显更久:
     * 「已过期,请重发」这句话是靠<b>记录还在、状态由时间派生</b>说出来的。
     * 详见类注释「与文件版刻意不同的两处」。
     */
    private static final Duration RECORD_TTL = Duration.ofMinutes(30);

    /**
     * 号码锁与失败计数活多久。
     *
     * <p>🔴 <b>不能设成几十秒。</b> 文件版的 failedCount 没有时间衰减,短 TTL 会把
     * 「错 5 次锁定」放松成「错 4 次等一会儿」。24 小时远大于 30 分钟的锁定窗口,
     * 锁本身不会被它提前解开。
     */
    private static final Duration LOCK_TTL = Duration.ofHours(24);

    /** 🔴 旧码进作废槽的条件与清空,和写新码,必须在一次执行里完成。 */
    private static final RedisScript<Long> ISSUE = new DefaultRedisScript<>("""
            local prev = redis.call('HMGET', KEYS[1],
                    'phoneHmac', 'codeHmac', 'purpose', 'issuedAt', 'expiresAt', 'state')
            redis.call('DEL', KEYS[2])
            if prev[6] == 'SENT' then
                redis.call('HSET', KEYS[2],
                        'phoneHmac', prev[1], 'codeHmac', prev[2], 'purpose', prev[3],
                        'issuedAt', prev[4], 'expiresAt', prev[5], 'state', 'SUPERSEDED')
                redis.call('EXPIRE', KEYS[2], ARGV[7])
            end
            redis.call('HSET', KEYS[1],
                    'phoneHmac', ARGV[1], 'codeHmac', ARGV[2], 'purpose', ARGV[3],
                    'issuedAt', ARGV[4], 'expiresAt', ARGV[5], 'state', ARGV[6])
            redis.call('EXPIRE', KEYS[1], ARGV[7])
            return 1
            """, Long.class);

    /** 🔴 compare-and-set:四个条件全中才改状态。抢输的那一方拿到 0。 */
    private static final RedisScript<Long> CONSUME_IF_SENT = new DefaultRedisScript<>("""
            local v = redis.call('HMGET', KEYS[1], 'state', 'purpose', 'codeHmac')
            if v[1] ~= 'SENT' or v[2] ~= ARGV[1] or v[3] ~= ARGV[2] then
                return 0
            end
            redis.call('HSET', KEYS[1], 'state', 'CONSUMED')
            return 1
            """, Long.class);

    /** 🔴 先比 codeHmac 再删 —— 不能误伤这一瞬间刚发出的新码。 */
    private static final RedisScript<Long> DISCARD = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'codeHmac') == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    /**
     * 🔴 读-改-写。逻辑与 {@link PhoneLock#afterFailure} 逐句对应:
     * 计数 +1;到 {@code ARGV[2]}({@link PhoneLock#MAX_FAILURES})就置锁定时点<b>并把计数归零</b>
     * —— 不归零的话解锁后第一次输错会立刻再锁 30 分钟。
     * 阈值与锁定时点都由 Java 侧从 {@link PhoneLock} 取好传进来,这里不再写一份数字。
     */
    private static final RedisScript<String> RECORD_FAILURE = new DefaultRedisScript<>("""
            local n = tonumber(redis.call('HGET', KEYS[1], 'failedCount') or '0') + 1
            local lockedUntil = redis.call('HGET', KEYS[1], 'lockedUntil')
            if n >= tonumber(ARGV[2]) then
                n = 0
                lockedUntil = ARGV[3]
            end
            local ns = string.format('%d', n)
            redis.call('HSET', KEYS[1], 'phoneHmac', ARGV[1], 'failedCount', ns)
            if lockedUntil then
                redis.call('HSET', KEYS[1], 'lockedUntil', lockedUntil)
            end
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return ns .. '|' .. (lockedUntil or '')
            """, String.class);

    private final StringRedisTemplate redis;

    public RedisSmsCodeStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<SmsCode> findLatest(String phoneHmac) {
        return readCode(latestKey(phoneHmac));
    }

    @Override
    public Optional<SmsCode> findSuperseded(String phoneHmac) {
        return readCode(supersededKey(phoneHmac));
    }

    @Override
    public void issue(SmsCode code) {
        redis.execute(ISSUE,
                List.of(latestKey(code.phoneHmac()), supersededKey(code.phoneHmac())),
                code.phoneHmac(), code.codeHmac(), code.purpose().wireName(),
                code.issuedAt().toString(), code.expiresAt().toString(), code.state().name(),
                String.valueOf(RECORD_TTL.toSeconds()));
    }

    @Override
    public boolean consumeIfSent(String phoneHmac, String codeHmac, SmsPurpose purpose) {
        Long changed = redis.execute(CONSUME_IF_SENT, List.of(latestKey(phoneHmac)),
                purpose.wireName(), codeHmac);
        return changed != null && changed == 1L;
    }

    @Override
    public PhoneLock lockOf(String phoneHmac) {
        Map<String, String> h = redis.<String, String>opsForHash().entries(lockKey(phoneHmac));
        if (h.isEmpty()) {
            return PhoneLock.clean(phoneHmac);
        }
        String until = h.getOrDefault("lockedUntil", "");
        return new PhoneLock(phoneHmac,
                Integer.parseInt(h.getOrDefault("failedCount", "0")),
                until.isEmpty() ? null : Instant.parse(until));
    }

    @Override
    public PhoneLock recordFailure(String phoneHmac, Instant now) {
        String encoded = redis.execute(RECORD_FAILURE, List.of(lockKey(phoneHmac)),
                phoneHmac,
                String.valueOf(PhoneLock.MAX_FAILURES),
                now.plus(PhoneLock.LOCK_WINDOW).toString(),
                String.valueOf(LOCK_TTL.toSeconds()));
        if (encoded == null) {
            throw new IllegalStateException("记一次验证码失败时 Redis 没有回值");
        }
        // "failedCount|lockedUntil";没有锁定时点时第二段是空串。
        int bar = encoded.indexOf('|');
        String until = encoded.substring(bar + 1);
        return new PhoneLock(phoneHmac, Integer.parseInt(encoded.substring(0, bar)),
                until.isEmpty() ? null : Instant.parse(until));
    }

    @Override
    public void discard(String phoneHmac, String codeHmac) {
        redis.execute(DISCARD, List.of(latestKey(phoneHmac)), codeHmac);
    }

    @Override
    public void clearLock(String phoneHmac) {
        redis.delete(lockKey(phoneHmac));
    }

    // —— key 与编解码 ——

    private static String latestKey(String phoneHmac) {
        return PREFIX + "code:latest:" + phoneHmac;
    }

    private static String supersededKey(String phoneHmac) {
        return PREFIX + "code:superseded:" + phoneHmac;
    }

    private static String lockKey(String phoneHmac) {
        return PREFIX + "lock:" + phoneHmac;
    }

    /** 🔴 逐字段读,字段名与 {@code auth-sms.json} 里写出去的那几个相同。 */
    private Optional<SmsCode> readCode(String key) {
        Map<String, String> h = redis.<String, String>opsForHash().entries(key);
        if (h.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SmsCode(
                required(h, "phoneHmac"),
                required(h, "codeHmac"),
                SmsPurpose.ofWireName(required(h, "purpose")),
                Instant.parse(required(h, "issuedAt")),
                Instant.parse(required(h, "expiresAt")),
                SmsCode.State.valueOf(required(h, "state"))));
    }

    private static String required(Map<String, String> h, String field) {
        String v = h.get(field);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("验证码记录缺少必填字段:" + field);
        }
        return v;
    }
}
