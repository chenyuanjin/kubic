package com.kaodian.server.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link TokenStore} 的阶段 0/1 实现 —— 一个 JSON 文件。
 *
 * <p>形态与 {@code FileTouchStore} 一致:全量重写 + 原子替换,读写逐字段列举。
 * 令牌量级是「用户数 × 设备数」,几百到几千行,全量重写完全够用;
 * 撑不住的那天正是 {@code 1.2.4} 换 JDBC 的那天。
 *
 * <h2>🔴 这个文件里没有一个令牌原值</h2>
 *
 * 键是 SHA-256。把这个文件整个拷走,拿不到任何一个能用的令牌 —— 这是
 * docs/technical/INDEX.md §7.4「存 SHA-256 不存原值」在磁盘上的样子。
 */
@Component
public class FileTokenStore implements TokenStore {

    private static final String FILE_NAME = "auth-tokens.json";

    private final AuthJsonFile file;
    private final Object lock = new Object();

    /** {@code null} 表示还没载入。键是 tokenHash,插入序即签发序。 */
    private Map<String, AccessToken> tokens;

    // 🔴 这个类有两个构造器,Spring 挑不出来 —— 少了这个注解,启动期报的是
    // 「No default constructor found」,而那句话和真正的原因(构造器歧义)毫无关系。
    // 另一个构造器是给测试用的:它直接收 Path,不碰配置也不碰用户目录。
    @Autowired
    public FileTokenStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public FileTokenStore(Path file) {
        this.file = new AuthJsonFile(file);
    }

    public Path dataFile() {
        return file.path();
    }

    @Override
    public Optional<AccessToken> findByHash(String tokenHash) {
        synchronized (lock) {
            ensureLoaded();
            return Optional.ofNullable(tokens.get(tokenHash));
        }
    }

    @Override
    public List<AccessToken> findByUser(long userId) {
        synchronized (lock) {
            ensureLoaded();
            return tokens.values().stream()
                    .filter(t -> t.userId() == userId)
                    .sorted(Comparator.comparing(AccessToken::lastUsedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                    .toList();
        }
    }

    @Override
    public void save(AccessToken token) {
        synchronized (lock) {
            ensureLoaded();
            if (tokens.containsKey(token.tokenHash())) {
                // 32 字节随机撞上的概率不值得处理,但真撞上了必须是响亮的失败:
                // 静默覆盖等于把别人的会话让给了这次签发。
                throw new IllegalStateException("令牌哈希已存在,拒绝覆盖");
            }
            Map<String, AccessToken> next = new LinkedHashMap<>(tokens);
            next.put(token.tokenHash(), token);
            persist(next);
        }
    }

    @Override
    public void replace(AccessToken token) {
        synchronized (lock) {
            ensureLoaded();
            if (!tokens.containsKey(token.tokenHash())) {
                throw new IllegalStateException("要替换的令牌不存在");
            }
            Map<String, AccessToken> next = new LinkedHashMap<>(tokens);
            next.put(token.tokenHash(), token);
            persist(next);
        }
    }

    @Override
    public int revokeAllOfUser(long userId, Instant now) {
        synchronized (lock) {
            ensureLoaded();
            Map<String, AccessToken> next = new LinkedHashMap<>(tokens);
            int n = 0;
            for (Map.Entry<String, AccessToken> e : next.entrySet()) {
                AccessToken t = e.getValue();
                if (t.userId() == userId && !t.isRevoked()) {
                    e.setValue(t.revoked(now));
                    n++;
                }
            }
            if (n > 0) {
                persist(next);
            }
            return n;
        }
    }

    /**
     * 清掉已经没有意义的行 —— 过期且吊销都超过保留期的。
     *
     * <p>不自动跑。设备管理页需要「上次登录」这类信息,过早清掉会让那一页变空;
     * 而永不清会让文件线性增长。折中是<b>由调用方在明确的时机调用</b>,
     * 目前只有测试与将来的运维脚本会用。
     */
    public int purgeBefore(Instant cutoff) {
        synchronized (lock) {
            ensureLoaded();
            Map<String, AccessToken> next = new LinkedHashMap<>();
            int removed = 0;
            for (Map.Entry<String, AccessToken> e : tokens.entrySet()) {
                AccessToken t = e.getValue();
                boolean dead = t.expiresAt().isBefore(cutoff)
                        && (t.revokedAt() == null || t.revokedAt().isBefore(cutoff));
                if (dead) {
                    removed++;
                } else {
                    next.put(e.getKey(), t);
                }
            }
            if (removed > 0) {
                persist(next);
            }
            return removed;
        }
    }

    // —— 载入与落盘 ——

    private void ensureLoaded() {
        if (tokens != null) {
            return;
        }
        tokens = file.read(FileTokenStore::parse, LinkedHashMap::new);
    }

    private void persist(Map<String, AccessToken> next) {
        // 先落盘再改内存:写失败时内存与磁盘仍然一致(与 FileTouchStore 同一条纪律)。
        ObjectNode root = file.newRoot(
                "会话令牌 —— 🔴 这里没有一个令牌原值,只有 SHA-256。",
                "docs/technical/INDEX.md §7.4:签发时返回一次,不可再查看。");
        ArrayNode arr = root.putArray("tokens");
        for (AccessToken t : next.values()) {
            arr.add(toNode(t));
        }
        file.write(root);
        tokens = next;
    }

    private static Map<String, AccessToken> parse(JsonNode root) {
        JsonNode arr = root.path("tokens");
        if (!arr.isArray()) {
            throw new IllegalStateException("令牌文件里没有 tokens 数组");
        }
        Map<String, AccessToken> out = new LinkedHashMap<>();
        List<AccessToken> all = new ArrayList<>();
        for (JsonNode n : arr) {
            all.add(new AccessToken(
                    required(n, "tokenHash"),
                    requiredLong(n, "userId"),
                    TokenScope.ofWireName(required(n, "scope")),
                    n.path("deviceLabel").asString(""),
                    Instant.parse(required(n, "issuedAt")),
                    optionalInstant(n, "lastUsedAt"),
                    Instant.parse(required(n, "expiresAt")),
                    optionalInstant(n, "revokedAt")));
        }
        for (AccessToken t : all) {
            out.put(t.tokenHash(), t);
        }
        return out;
    }

    /** 🔴 逐字段写。文件里能出现哪些键由这里显式列出,加字段必须先过这一处。 */
    private static ObjectNode toNode(AccessToken t) {
        ObjectNode o = AuthJsonFile.mapper().createObjectNode();
        o.put("tokenHash", t.tokenHash());
        o.put("userId", t.userId());
        o.put("scope", t.scope().wireName());
        o.put("deviceLabel", t.deviceLabel());
        o.put("issuedAt", t.issuedAt().toString());
        if (t.lastUsedAt() != null) {
            o.put("lastUsedAt", t.lastUsedAt().toString());
        }
        o.put("expiresAt", t.expiresAt().toString());
        if (t.revokedAt() != null) {
            o.put("revokedAt", t.revokedAt().toString());
        }
        return o;
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asString("");
        if (v.isEmpty()) {
            throw new IllegalStateException("令牌记录缺少必填字段:" + field);
        }
        return v;
    }

    /** userId 是 int64(B0-2 §3.3);tokenHash 不是 —— 它仍然是不透明字符串(契约 §1.1「令牌标识」)。 */
    private static long requiredLong(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isIntegralNumber()) {
            throw new IllegalStateException("令牌记录的 " + field + " 不是 int64:" + v
                    + " —— B0-2 之前的存量数据?删掉 ~/.kaodian/auth-*.json 重新注册即可");
        }
        return v.longValue();
    }

    private static Instant optionalInstant(JsonNode n, String field) {
        String v = n.path(field).asString("");
        return v.isEmpty() ? null : Instant.parse(v);
    }
}
