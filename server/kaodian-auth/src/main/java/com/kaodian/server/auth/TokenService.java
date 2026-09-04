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
    public IssuedToken issue(long userId, TokenScope scope, String deviceLabel) {
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
     * 明文 → 四叶结果,顺带滑动续期({@code M5-账号与登录通道} §4.3)。
     *
     * <p>🔴 <b>这是唯一做校验的地方,{@link #verify} 只是它的一个投影。</b>
     * 两处各写一遍判断,「一致」就只剩注释在保证 —— 而那正是 {@code TOKEN_EXPIRED}
     * 至今没有出生地的原因:上一版把四种失败在这一层就折叠掉了,
     * 于是上层再想分档也无从分起。
     *
     * <p>⚠️ <b>这一层不查账号状态。</b> {@link TokenCheck.Revoked} 只带 {@code userId} 出去,
     * 由 {@code app} 去查 —— 令牌服务只回答令牌的事({@code M5} §十一)。
     */
    public TokenCheck check(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return new TokenCheck.Invalid();
        }
        // 前缀只用来快速拒绝明显不对的串。真正的作用域来自库里那一行 —— 见 TokenScope.hintFromPrefix。
        if (TokenScope.hintFromPrefix(plaintext) == null) {
            return new TokenCheck.Invalid();
        }
        Optional<AccessToken> found = store.findByHash(sha256(plaintext));
        if (found.isEmpty()) {
            // 🔴 查不到就到此为止 —— 这条路上没有 userId,所以「这个账号注销了」说不出来。
            // 泄露面由这个结构限死,不靠一条要记住的规矩(TokenCheck 的类注释)。
            return new TokenCheck.Invalid();
        }
        AccessToken token = found.get();
        // 🔴 吊销先判:一条既被吊销又已过期的令牌,该说的是「已吊销」那一档 ——
        // 因为注销账号会 revokeAll,而那批令牌迟早也会过期。反过来判的话,
        // 注销满 30 天之后 ACCOUNT_DEACTIVATED 会静默变回 TOKEN_EXPIRED。
        if (token.isRevoked()) {
            return new TokenCheck.Revoked(token.userId());
        }
        Instant now = clock.instant();
        if (!now.isBefore(token.expiresAt())) {
            return new TokenCheck.Expired();
        }
        return new TokenCheck.Valid(slide(token, now));
    }

    /**
     * 明文 → 这条会话,顺带滑动续期。<b>给不关心档位的调用方</b>。
     *
     * <p>四种失败在这里合并成一个空值 —— 需要分档的走 {@link #check}。
     */
    public Optional<AccessToken> verify(String plaintext) {
        return check(plaintext) instanceof TokenCheck.Valid v ? Optional.of(v.token()) : Optional.empty();
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
    public boolean revokeByHash(long userId, String tokenHash) {
        Optional<AccessToken> found = store.findByHash(tokenHash);
        if (found.isEmpty()) {
            return false;
        }
        AccessToken t = found.get();
        if (t.userId() != userId) {
            throw new IllegalArgumentException("这条会话不属于当前账号");
        }
        if (t.isRevoked()) {
            return false;
        }
        store.replace(t.revoked(clock.instant()));
        return true;
    }

    /** 注销账号 / 退出全部设备。 */
    public int revokeAll(long userId) {
        return store.revokeAllOfUser(userId, clock.instant());
    }

    /** 设备管理页的数据源。含已吊销与已过期的,由上层决定显示哪些。 */
    public List<AccessToken> sessionsOf(long userId) {
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
