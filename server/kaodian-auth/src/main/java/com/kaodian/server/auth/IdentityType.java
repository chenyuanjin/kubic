package com.kaodian.server.auth;

/**
 * 一个账号可以被哪几种东西认出来。
 *
 * <h2>为什么是一张多态 identity 表,而不是 {@code app_user} 上的两个列</h2>
 *
 * docs/technical/INDEX.md §7.2:阶段 2 只做手机号,微信在阶段 2 后。但 {@code 1.3.1.2.2} 要求
 * <b>阶段 2 就把 openid/unionid 的位置留出来</b>。
 * <p>
 * 「留位」不是加两个空列 —— 那样阶段 2 后接微信是一次表结构迁移;
 * 用多态 identity 表,接微信只是<b>插入一行数据</b>。两者的成本在今天一样低,
 * 在那天差一个数量级。
 *
 * <h2>🔴 账号的锚点是 {@code AppUser.id},不是这里的任何一个</h2>
 *
 * 手机号会被运营商回收,微信会被借用登录。把任何一个当成账号本身,
 * 都会在某一天把两个人的记录并到一起 —— 而覆盖率就是这个产品(docs/technical/INDEX.md §7.1)。
 */
public enum IdentityType {

    /**
     * 手机号。
     *
     * <p>🔴 {@code UserIdentity.identifier} 存的<b>不是手机号明文,是 HMAC</b>。
     * docs/technical/INDEX.md §5.2 定的是「{@code phone_hash}(HMAC,唯一)用于查、{@code phone_enc}(AES)用于发短信」,
     * 而 {@code (type, identifier)} 唯一索引正好就是那个「用于查」。
     * 见 {@link PhoneCipher}。
     */
    PHONE("phone"),

    /**
     * 微信开放平台下的用户唯一标识 unionid —— <b>跨小程序/公众号/网站应用同一个人</b>。
     *
     * <p>有 unionid 就一律用 unionid,不用 openid。openid 是「这个人在这个应用里」的 id,
     * 同一个人在小程序和网站应用下的 openid 不同 —— 用它做账号锚点,
     * 用户换个入口登录就会多出一个账号,而那正是 {@code R-33}(行为层被拆两半)。
     */
    WX_UNION("wx_union"),

    /**
     * 微信 openid —— <b>仅在拿不到 unionid 时的退路</b>。
     *
     * <p>拿不到 unionid 的唯一原因是小程序/网站应用<b>没有绑定到同一个微信开放平台账号</b>。
     * 那是一次控制台配置,不是代码问题。所以这个取值存在的意义是:
     * <b>让「没绑开放平台」这件事表现为数据里的一个可见取值,而不是一个静默降级</b>。
     */
    WX_OPEN("wx_open");

    private final String wireName;

    IdentityType(String wireName) {
        this.wireName = wireName;
    }

    /** 落盘与接口上用的名字,与 docs/technical/INDEX.md §5.2 的 {@code type} 取值逐字一致。 */
    public String wireName() {
        return wireName;
    }

    public static IdentityType ofWireName(String s) {
        for (IdentityType t : values()) {
            if (t.wireName.equals(s)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的身份类型:" + s);
    }
}
