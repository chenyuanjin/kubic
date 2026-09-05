package com.kaodian.server.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link SmsRateLimiter} 的阶段 0/1 实现 —— 计数落文件。
 *
 * <h2>「日」按哪个时区算</h2>
 *
 * 按 {@code Asia/Shanghai} 的自然日,不是 UTC 日。
 * 用户看到的话术是「明天 0 点恢复」,而他说的「明天」是北京时间的明天。
 * 用 UTC 算,这句话在晚上 8 点之后就是错的 —— <b>而给出准确时点正是这一整套设计的要点</b>。
 * 时区可配,但默认必须是这一个。
 *
 * <h2>为什么落文件而不是内存</h2>
 *
 * 与 {@link FileSmsCodeStore} 同一条理由:内存计数意味着重启即清零,
 * 于是绕过「10 条/日」的方法是等一次发版。这条链路的另一端连着真实账单。
 */
@Component
// 默认实现:kaodian.auth.sms.store 没配或配成 file 时装这一个,配成 redis 时换 RedisSmsRateLimiter。
@ConditionalOnProperty(name = "kaodian.auth.sms.store", havingValue = "file", matchIfMissing = true)
public class FileSmsRateLimiter implements SmsRateLimiter {

    private static final String FILE_NAME = "auth-sms-quota.json";

    /** docs/technical/INDEX.md §6.1:单号 1/60s。 */
    private static final Duration PER_PHONE_COOLDOWN = Duration.ofSeconds(60);

    /** docs/technical/INDEX.md §6.1:单号 10/日。 */
    private static final int PER_PHONE_DAILY = 10;

    /** docs/technical/INDEX.md §6.1:单 IP 20/日。 */
    private static final int PER_IP_DAILY = 20;

    private final AuthJsonFile file;
    private final ZoneId zone;
    private final Object lock = new Object();

    /** 键是 phoneHmac / ip。 */
    private Map<String, Counter> phones;
    private Map<String, Counter> ips;

    // 🔴 这个类有两个构造器,Spring 挑不出来 —— 少了这个注解,启动期报的是
    // 「No default constructor found」,而那句话和真正的原因(构造器歧义)毫无关系。
    // 另一个构造器是给测试用的:它直接收 Path,不碰配置也不碰用户目录。
    @Autowired
    public FileSmsRateLimiter(
            @Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir,
            @Value("${kaodian.auth.sms.zone:Asia/Shanghai}") String zone) {
        this(Path.of(dataDir).resolve(FILE_NAME), ZoneId.of(zone));
    }

    public FileSmsRateLimiter(Path file, ZoneId zone) {
        this.file = new AuthJsonFile(file);
        this.zone = zone;
    }

    @Override
    public Decision reserve(String phoneHmac, String ip, Instant now) {
        synchronized (lock) {
            ensureLoaded();
            String today = LocalDate.ofInstant(now, zone).toString();

            Counter phone = today(phones.get(phoneHmac), today);
            if (phone.lastAt() != null) {
                Instant retryAt = phone.lastAt().plus(PER_PHONE_COOLDOWN);
                if (now.isBefore(retryAt)) {
                    return new Decision.TooFrequent(retryAt);
                }
            }
            if (phone.count() >= PER_PHONE_DAILY) {
                return new Decision.PhoneDailyExhausted(nextMidnight(now), PER_PHONE_DAILY);
            }

            // IP 为空表示取不到调用方地址。此时这一道闸没有意义,直接放行 ——
            // 但绝不因此报错:🔴「记录动作永不失败」的同源要求是登录也不能因为拿不到 IP 就登不进来。
            Counter ipc = ip == null || ip.isBlank() ? null : today(ips.get(ip), today);
            if (ipc != null && ipc.count() >= PER_IP_DAILY) {
                return new Decision.IpDailyExhausted(nextMidnight(now), PER_IP_DAILY);
            }

            Map<String, Counter> nextPhones = new LinkedHashMap<>(phones);
            nextPhones.put(phoneHmac, new Counter(today, phone.count() + 1, now));
            Map<String, Counter> nextIps = new LinkedHashMap<>(ips);
            if (ipc != null) {
                nextIps.put(ip, new Counter(today, ipc.count() + 1, now));
            }
            persist(nextPhones, nextIps);
            return new Decision.Allowed(phone.count() + 1, ipc == null ? 0 : ipc.count() + 1);
        }
    }

    @Override
    public void releaseDaily(String phoneHmac, String ip) {
        synchronized (lock) {
            ensureLoaded();
            Map<String, Counter> nextPhones = new LinkedHashMap<>(phones);
            Counter p = nextPhones.get(phoneHmac);
            if (p != null && p.count() > 0) {
                // 🔴 lastAt 原样留着 —— 60 秒冷却不还,见 SmsRateLimiter#releaseDaily。
                nextPhones.put(phoneHmac, new Counter(p.day(), p.count() - 1, p.lastAt()));
            }
            Map<String, Counter> nextIps = new LinkedHashMap<>(ips);
            if (ip != null && !ip.isBlank()) {
                Counter i = nextIps.get(ip);
                if (i != null && i.count() > 0) {
                    nextIps.put(ip, new Counter(i.day(), i.count() - 1, i.lastAt()));
                }
            }
            persist(nextPhones, nextIps);
        }
    }

    /** 跨日即清零。存的是「哪一天的计数」,不需要定时任务来重置。 */
    private static Counter today(Counter c, String day) {
        if (c == null || !c.day().equals(day)) {
            return new Counter(day, 0, c == null ? null : c.lastAt());
        }
        return c;
    }

    private Instant nextMidnight(Instant now) {
        return LocalDate.ofInstant(now, zone).plusDays(1).atStartOfDay(zone).toInstant();
    }

    // —— 载入与落盘 ——

    private void ensureLoaded() {
        if (phones != null) {
            return;
        }
        Loaded l = file.read(FileSmsRateLimiter::parse,
                () -> new Loaded(new LinkedHashMap<>(), new LinkedHashMap<>()));
        phones = l.phones();
        ips = l.ips();
    }

    private void persist(Map<String, Counter> nextPhones, Map<String, Counter> nextIps) {
        ObjectNode root = file.newRoot(
                "短信频控计数。单号 1/60s · 10/日,单 IP 20/日(docs/technical/INDEX.md §6.1)。",
                "🔴 这两道闸挡的是账单,不是坏人 —— 挡坏人的是第①道滑块。",
                "手机号只有 HMAC。IP 是明文,它本来就在每一条访问日志里。");
        ArrayNode ps = root.putArray("phones");
        for (Map.Entry<String, Counter> e : nextPhones.entrySet()) {
            ps.add(toNode("phoneHmac", e.getKey(), e.getValue()));
        }
        ArrayNode is = root.putArray("ips");
        for (Map.Entry<String, Counter> e : nextIps.entrySet()) {
            is.add(toNode("ip", e.getKey(), e.getValue()));
        }
        file.write(root);
        phones = nextPhones;
        ips = nextIps;
    }

    private record Counter(String day, int count, Instant lastAt) {
    }

    private record Loaded(Map<String, Counter> phones, Map<String, Counter> ips) {
    }

    private static Loaded parse(JsonNode root) {
        return new Loaded(read(root, "phones", "phoneHmac"), read(root, "ips", "ip"));
    }

    private static Map<String, Counter> read(JsonNode root, String arrayName, String keyName) {
        JsonNode arr = root.path(arrayName);
        if (!arr.isArray()) {
            throw new IllegalStateException("频控文件里没有 " + arrayName + " 数组");
        }
        Map<String, Counter> out = new LinkedHashMap<>();
        for (JsonNode n : arr) {
            String key = n.path(keyName).asString("");
            if (key.isEmpty()) {
                throw new IllegalStateException("频控记录缺少 " + keyName);
            }
            String last = n.path("lastAt").asString("");
            out.put(key, new Counter(
                    n.path("day").asString(""),
                    n.path("count").asInt(0),
                    last.isEmpty() ? null : Instant.parse(last)));
        }
        return out;
    }

    private static ObjectNode toNode(String keyName, String key, Counter c) {
        ObjectNode o = AuthJsonFile.mapper().createObjectNode();
        o.put(keyName, key);
        o.put("day", c.day());
        o.put("count", c.count());
        if (c.lastAt() != null) {
            o.put("lastAt", c.lastAt().toString());
        }
        return o;
    }
}
