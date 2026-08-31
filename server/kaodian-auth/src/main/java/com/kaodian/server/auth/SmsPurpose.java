package com.kaodian.server.auth;

/**
 * 这条验证码是拿来干什么的。
 *
 * <h2>为什么必须分开,而不是「一个码通用」</h2>
 *
 * 通用码可以被<b>跨场景重放</b>:攻击者诱导已登录用户去点「登录」拿一条码,
 * 拿到后用它去调 {@code /auth/bind/phone},把受害者的账号绑到自己的号上。
 * 校验时连用途一起比,这条路就是死的。
 * <p>
 * 代价是一个枚举值,收益是一整类漏洞不存在 —— 这与 {@code TokenScope} 前缀参与哈希是同一条。
 */
public enum SmsPurpose {

    /** 登录。号码没见过就建号(docs/后端详设 §1.7 注册即登录)。 */
    LOGIN("login"),

    /** 给已登录账号绑手机号。<b>目标号已属他人 → 返回可合并提示,不自动合并</b>(docs/技术架构 §6.1)。 */
    BIND("bind");

    private final String wireName;

    SmsPurpose(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static SmsPurpose ofWireName(String s) {
        for (SmsPurpose v : values()) {
            if (v.wireName.equals(s)) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知的验证码用途:" + s);
    }
}
