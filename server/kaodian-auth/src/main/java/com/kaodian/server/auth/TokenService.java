package com.kaodian.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 签发、校验、吊销 —— docs/technical/INDEX.md §7.4 与 docs/technical/后端系统设计与组件接入.md §1.9 的全部实现。
 *
 * <h2>这个类里没有 Spring</h2>
 *
 * 与 {@code CoverageService} 同一形态:它只做随机、哈希、比时间。
 * 装配在 {@code api} 包的 {@code AuthBeans} 里 —— <b>谁组装,谁依赖框架</b>。
 *
 * <h2>为什么不是 JWT</h2>
 *
 * 产品里有两处硬要求撤销必须<b>立刻</b>生效:注销账号,以及设备管理页的「退出这台」。
 * JWT 在过期前无法撤销,要撤销就得另建黑名单表 —— 那等于每次请求都回库查一次,
 * 绕一圈回到有状态,还多背一套签名机制(docs/technical/后端系统设计与组件接入.md §1.9)。
 * <p>
 * 既然一定要回库,那就干脆只回库。
 */
public class TokenService {

    /** base62。刻意不含 {@code +/=} —— 令牌会出现在 URL、header 与用户手动粘贴里。 */
    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /**
     * 43 个 base62 字符 ≈ 256 位熵,与 docs/technical/INDEX.md §7.4 写的「32 字节」等价。
     *
     * <p>写成「取 43 次字符」而不是「32 字节转 base62」,是为了避开一个静默的坑:
     * 大整数进制转换会<b>吃掉前导零</b>,于是极小概率下签出一条比别人短的令牌。
     * 逐字符取样没有这个问题,而且每个位置都是均匀的。
     */
    private static final int TOKEN_CHARS = 43;

    /** 30 天,滑动续期。 */
    public static final Duration LIFETIME = Duration.ofDays(30);

    /**
     * 滑动续期的落盘节流阈值。
     *
     * <p>「每次使用都往后滑」如果字面实现,就是<b>每一个 API 请求都全量重写一次令牌文件</b>。
     * 用户体验上,30 天的有效期里差这一个小时毫无意义;运维上,差的是三个数量级的写放大。
     * <p>
     * 所以:只有当这次滑动能把过期时刻往后推超过一小时,才真的落盘。
     */
    private static final Duration SLIDE_PERSIST_THRESHOLD = Duration.ofHours(1);

    private final TokenStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(TokenStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * 签发一条。<b>返回值里的明文是这个令牌唯一一次出现的地方。</b>
     *
     * @param deviceLabel 设备名,设备管理页(D26)显示它。允许为空 —— 认不出来就叫「未知设备」,
     *                    <b>不因为认不出设备就拒绝登录</b>
     */
    public IssuedToken issue(String userId, TokenScope scope, String deviceLabel) {
        Instant now = clock.instant();
        String plaintext = scope.prefix() + randomBody();
        AccessToken stored = new AccessToken(
                sha256(plaintext), userId, scope,
                deviceLabel == null || deviceLabel.isBlank() ? "未知设备" : deviceLabel,
                now, now, now.plus(LIFETIME), null);
        store.save(stored);
        return new IssuedToken(plaintext, stored);
    }

    /**
     * 明文 → 这条会话,顺带滑动续期。
     *
     * <p>返回空的四种情况合并成一种对外表现(401),这是有意的:
     * <b>「这个令牌不存在」和「这个令牌过期了」对攻击者的信息量不同,对用户则完全一样</b> ——
     * 用户要做的事都是重新登录。区分只写进服务端日志。
     */
    public Optional<AccessToken> verify(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return Optional.empty();
        }
        // 前缀只用来快速拒绝明显不对的串。真正的作用域来自库里那一行 —— 见 TokenScope.hintFromPrefix。
        if (TokenScope.hintFromPrefix(plaintext) == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<AccessToken> found = store.findByHash(sha256(plaintext));
        if (found.isEmpty() || !found.get().isUsableAt(now)) {
            return Optional.empty();
        }
        return Optional.of(slide(found.get(), now));
    }

    /** 滑动续期。落盘被节流,见 {@link #SLIDE_PERSIST_THRESHOLD}。 */
    private AccessToken slide(AccessToken token, Instant now) {
        Instant newExpiry = now.plus(LIFETIME);
        if (newExpiry.isBefore(token.expiresAt().plus(SLIDE_PERSIST_THRESHOLD))) {
            return token;                       // 推进不到一小时,不值得写一次盘
        }
        AccessToken slid = token.slid(now, newExpiry);
        store.replace(slid);
        return slid;
    }

    /**
     * 退出这一台 —— 只吊销当前令牌(docs/technical/INDEX.md §6.1)。
     *
     * <p>🔴 <b>吊销是幂等的</b>:重复调用返回 {@code false},不报错。
     * 「退出登录」这个动作在网络不稳时会被用户点两次,而第二次报错只会让人以为没退成功。
     *
     * @return 这次是否真的从「有效」改成了「已吊销」
     */
    public boolean revoke(String plaintext) {
        Optional<AccessToken> found = store.findByHash(sha256(plaintext));
        if (found.isEmpty() || found.get().isRevoked()) {
            return false;
        }
        store.replace(found.get().revoked(clock.instant()));
        return true;
    }

    /**
     * 按哈希吊销 —— 设备管理页用。
     *
     * <p>页面上列出的是别的设备,服务端手里只有它们的哈希,拿不到明文。
     * 所以这条路和 {@link #revoke} 是两个方法,而不是一个。
     *
     * @throws IllegalArgumentException 该哈希不属于这个账号 —— <b>越权吊销别人的会话必须是显式失败</b>
     */
    public boolean revokeByHash(String userId, String tokenHash) {
        Optional<AccessToken> found = store.findByHash(tokenHash);
        if (found.isEmpty()) {
            return false;
        }
        AccessToken t = found.get();
        if (!t.userId().equals(userId)) {
            throw new IllegalArgumentException("这条会话不属于当前账号");
        }
        if (t.isRevoked()) {
            return false;
        }
        store.replace(t.revoked(clock.instant()));
        return true;
    }

    /** 注销账号 / 退出全部设备。 */
    public int revokeAll(String userId) {
        return store.revokeAllOfUser(userId, clock.instant());
    }

    /** 设备管理页的数据源。含已吊销与已过期的,由上层决定显示哪些。 */
    public List<AccessToken> sessionsOf(String userId) {
        return store.findByUser(userId);
    }

    // —— 原语 ——

    private String randomBody() {
        StringBuilder sb = new StringBuilder(TOKEN_CHARS);
        for (int i = 0; i < TOKEN_CHARS; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * 哈希的是<b>含前缀的完整明文</b>。
     *
     * <p>如果只哈希后半段,{@code at_XXX} 与 {@code ro_XXX} 会算出同一个哈希 ——
     * 于是把只读令牌的前缀改成 {@code at_} 就能查到那条 FULL 的行。
     * 前缀参与哈希,这条路就是死的。
     */
    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
