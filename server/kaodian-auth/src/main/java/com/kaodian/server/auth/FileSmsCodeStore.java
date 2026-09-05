package com.kaodian.server.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link SmsCodeStore} 的阶段 0/1 实现。
 *
 * <h2>为什么不放内存就好 —— 验证码只活 5 分钟</h2>
 *
 * 因为<b>锁不是 5 分钟的</b>。放内存意味着重启一次进程,所有号码锁定全部消失 ——
 * 于是「错 5 次锁 30 分钟」这条防线的绕过方法是「等它发一次版」。
 * 而阶段 2 的部署形态本来就是手工重启。
 * <p>
 * 顺带的好处:用户在验证码有效期内碰上一次重启,不会被要求重新获取。
 *
 * <h2>两个槽</h2>
 *
 * {@code codes} 是最新那条,{@code superseded} 是被它顶掉的上一条。
 * 为什么必须留第二个槽,见 {@link SmsCodeStore} 的类注释 ——
 * <b>少了它,「请用最新收到的那一条」这句话就说不出来。</b>
 */
@Component
// 默认实现:kaodian.auth.sms.store 没配或配成 file 时装这一个,配成 redis 时换 RedisSmsCodeStore。
@ConditionalOnProperty(name = "kaodian.auth.sms.store", havingValue = "file", matchIfMissing = true)
public class FileSmsCodeStore implements SmsCodeStore {

    private static final String FILE_NAME = "auth-sms.json";

    private final AuthJsonFile file;
    private final Object lock = new Object();

    private Map<String, SmsCode> codes;
    private Map<String, SmsCode> superseded;
    private Map<String, PhoneLock> locks;

    // 🔴 这个类有两个构造器,Spring 挑不出来 —— 少了这个注解,启动期报的是
    // 「No default constructor found」,而那句话和真正的原因(构造器歧义)毫无关系。
    // 另一个构造器是给测试用的:它直接收 Path,不碰配置也不碰用户目录。
    @Autowired
    public FileSmsCodeStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public FileSmsCodeStore(Path file) {
        this.file = new AuthJsonFile(file);
    }

    public Path dataFile() {
        return file.path();
    }

    @Override
    public Optional<SmsCode> findLatest(String phoneHmac) {
        synchronized (lock) {
            ensureLoaded();
            return Optional.ofNullable(codes.get(phoneHmac));
        }
    }

    @Override
    public Optional<SmsCode> findSuperseded(String phoneHmac) {
        synchronized (lock) {
            ensureLoaded();
            return Optional.ofNullable(superseded.get(phoneHmac));
        }
    }

    @Override
    public void issue(SmsCode code) {
        synchronized (lock) {
            ensureLoaded();
            Map<String, SmsCode> nextCodes = new LinkedHashMap<>(codes);
            Map<String, SmsCode> nextOld = new LinkedHashMap<>(superseded);

            SmsCode previous = nextCodes.get(code.phoneHmac());
            if (previous != null && previous.state() == SmsCode.State.SENT) {
                // 只有还「在飞」的那条才值得记成已作废。已核销/已过期的旧码
                // 对用户是另外两句话,占着这个槽只会盖掉真正该说「用新的那条」的场景。
                nextOld.put(code.phoneHmac(), previous.superseded());
            } else {
                nextOld.remove(code.phoneHmac());
            }
            nextCodes.put(code.phoneHmac(), code);
            persist(nextCodes, nextOld, locks);
        }
    }

    /** 🔴 比对 + 改状态在同一把锁里 —— 见 {@link SmsCodeStore#consumeIfSent}。 */
    @Override
    public boolean consumeIfSent(String phoneHmac, String codeHmac, SmsPurpose purpose) {
        synchronized (lock) {
            ensureLoaded();
            SmsCode c = codes.get(phoneHmac);
            if (c == null || c.state() != SmsCode.State.SENT
                    || c.purpose() != purpose || !c.codeHmac().equals(codeHmac)) {
                return false;
            }
            Map<String, SmsCode> next = new LinkedHashMap<>(codes);
            next.put(phoneHmac, c.consumed());
            persist(next, superseded, locks);
            return true;
        }
    }

    @Override
    public PhoneLock lockOf(String phoneHmac) {
        synchronized (lock) {
            ensureLoaded();
            PhoneLock l = locks.get(phoneHmac);
            return l == null ? PhoneLock.clean(phoneHmac) : l;
        }
    }

    /** 🔴 读-改-写在同一把锁里 —— 见 {@link SmsCodeStore#recordFailure}。 */
    @Override
    public PhoneLock recordFailure(String phoneHmac, Instant now) {
        synchronized (lock) {
            ensureLoaded();
            PhoneLock current = locks.get(phoneHmac);
            PhoneLock updated = (current == null ? PhoneLock.clean(phoneHmac) : current)
                    .afterFailure(now);
            Map<String, PhoneLock> next = new LinkedHashMap<>(locks);
            next.put(phoneHmac, updated);
            persist(codes, superseded, next);
            return updated;
        }
    }

    /** 见 {@link SmsCodeStore#discard} —— 只清「确定没送达」的那一条。 */
    @Override
    public void discard(String phoneHmac, String codeHmac) {
        synchronized (lock) {
            ensureLoaded();
            SmsCode c = codes.get(phoneHmac);
            // 比对 codeHmac:万一这一瞬间已经发了新的一条,那条是有效的,不能被这次清理误伤。
            if (c == null || !c.codeHmac().equals(codeHmac)) {
                return;
            }
            Map<String, SmsCode> next = new LinkedHashMap<>(codes);
            next.remove(phoneHmac);
            persist(next, superseded, locks);
        }
    }

    @Override
    public void clearLock(String phoneHmac) {
        synchronized (lock) {
            ensureLoaded();
            if (!locks.containsKey(phoneHmac)) {
                return;                          // 本来就干净,不必写盘
            }
            Map<String, PhoneLock> next = new LinkedHashMap<>(locks);
            next.remove(phoneHmac);
            persist(codes, superseded, next);
        }
    }

    // —— 载入与落盘 ——

    private void ensureLoaded() {
        if (codes != null) {
            return;
        }
        Loaded loaded = file.read(FileSmsCodeStore::parse,
                () -> new Loaded(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>()));
        codes = loaded.codes();
        superseded = loaded.superseded();
        locks = loaded.locks();
    }

    private void persist(Map<String, SmsCode> nextCodes, Map<String, SmsCode> nextOld,
                         Map<String, PhoneLock> nextLocks) {
        ObjectNode root = file.newRoot(
                "短信验证码与号码锁。🔴 这里没有六位数字,只有带密钥的 HMAC。",
                "手机号同样只有 HMAC —— 与 auth-accounts.json 里的 identifier 是同一把哈希。",
                "superseded 是被新码顶掉的上一条 —— 留着它才说得出「请用最新收到的那一条」。");
        ArrayNode arr = root.putArray("codes");
        for (SmsCode c : nextCodes.values()) {
            arr.add(toNode(c));
        }
        ArrayNode old = root.putArray("superseded");
        for (SmsCode c : nextOld.values()) {
            old.add(toNode(c));
        }
        ArrayNode larr = root.putArray("locks");
        for (PhoneLock l : nextLocks.values()) {
            larr.add(toNode(l));
        }
        file.write(root);
        codes = nextCodes;
        superseded = nextOld;
        locks = nextLocks;
    }

    private record Loaded(Map<String, SmsCode> codes, Map<String, SmsCode> superseded,
                          Map<String, PhoneLock> locks) {
    }

    private static Loaded parse(JsonNode root) {
        Map<String, SmsCode> codes = readCodes(root, "codes");
        // 老版本的文件里没有这个数组 —— 缺了就当空,不因此让整个鉴权层起不来。
        // 这与「认不出来就吵着失败」不冲突:那条说的是【已有数据被当成空】,
        // 而这里是一个新增字段的向后兼容,丢的最坏情况只是一句更准确的提示。
        Map<String, SmsCode> old = root.path("superseded").isArray()
                ? readCodes(root, "superseded") : new LinkedHashMap<>();
        Map<String, PhoneLock> locks = new LinkedHashMap<>();
        for (JsonNode n : requireArray(root, "locks")) {
            String until = n.path("lockedUntil").asString("");
            PhoneLock l = new PhoneLock(
                    required(n, "phoneHmac"),
                    n.path("failedCount").asInt(0),
                    until.isEmpty() ? null : Instant.parse(until));
            locks.put(l.phoneHmac(), l);
        }
        return new Loaded(codes, old, locks);
    }

    private static Map<String, SmsCode> readCodes(JsonNode root, String field) {
        Map<String, SmsCode> out = new LinkedHashMap<>();
        for (JsonNode n : requireArray(root, field)) {
            SmsCode c = new SmsCode(
                    required(n, "phoneHmac"),
                    required(n, "codeHmac"),
                    SmsPurpose.ofWireName(required(n, "purpose")),
                    Instant.parse(required(n, "issuedAt")),
                    Instant.parse(required(n, "expiresAt")),
                    SmsCode.State.valueOf(required(n, "state")));
            out.put(c.phoneHmac(), c);
        }
        return out;
    }

    /** 🔴 逐字段写。 */
    private static ObjectNode toNode(SmsCode c) {
        ObjectNode o = AuthJsonFile.mapper().createObjectNode();
        o.put("phoneHmac", c.phoneHmac());
        o.put("codeHmac", c.codeHmac());
        o.put("purpose", c.purpose().wireName());
        o.put("issuedAt", c.issuedAt().toString());
        o.put("expiresAt", c.expiresAt().toString());
        o.put("state", c.state().name());
        return o;
    }

    private static ObjectNode toNode(PhoneLock l) {
        ObjectNode o = AuthJsonFile.mapper().createObjectNode();
        o.put("phoneHmac", l.phoneHmac());
        o.put("failedCount", l.failedCount());
        if (l.lockedUntil() != null) {
            o.put("lockedUntil", l.lockedUntil().toString());
        }
        return o;
    }

    private static JsonNode requireArray(JsonNode root, String field) {
        JsonNode n = root.path(field);
        if (!n.isArray()) {
            throw new IllegalStateException("验证码文件里没有 " + field + " 数组");
        }
        return n;
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asString("");
        if (v.isEmpty()) {
            throw new IllegalStateException("验证码记录缺少必填字段:" + field);
        }
        return v;
    }
}
