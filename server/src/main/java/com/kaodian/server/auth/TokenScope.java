package com.kaodian.server.auth;

/**
 * 令牌能干什么 —— <b>前缀参与语义,不只是装饰</b>(docs/10 §7.4)。
 *
 * <h2>{@code ro_} 不是「被判断为不许写」,是「换不出写能力」</h2>
 *
 * docs/13 §1.9 的原话。区别在于失败的形状:
 * <ul>
 *   <li>「被判断为不许写」→ 判断写在某个 if 里 → 少写一个 if 就漏一个口子</li>
 *   <li>「换不出写能力」→ 令牌本身携带的作用域就没有写 → 新增写端点<b>默认</b>关着</li>
 * </ul>
 *
 * MCP 与 CLI 一律只发 {@code ro_}。docs/10 §6.7.3 已定死:<b>支付与额度一律不进 MCP 白名单</b>。
 */
public enum TokenScope {

    /** 应用令牌。三端 App/Web 用,可读可写。 */
    FULL("full", "at_"),

    /** 只读令牌。MCP / CLI 用 —— 01 §2.6 的开放性是<b>只读</b>的开放性。 */
    READONLY("readonly", "ro_");

    private final String wireName;
    private final String prefix;

    TokenScope(String wireName, String prefix) {
        this.wireName = wireName;
        this.prefix = prefix;
    }

    public String wireName() {
        return wireName;
    }

    /** 明文令牌的前缀,含下划线。 */
    public String prefix() {
        return prefix;
    }

    public boolean canWrite() {
        return this == FULL;
    }

    public static TokenScope ofWireName(String s) {
        for (TokenScope v : values()) {
            if (v.wireName.equals(s)) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知的令牌作用域:" + s);
    }

    /**
     * 从明文令牌的前缀推作用域。
     *
     * <p>🔴 <b>这个结果只用来快速拒绝明显不对的串,不作为授权依据。</b>
     * 真正的作用域来自库里那一行 —— 否则任何人把 {@code ro_} 改成 {@code at_}
     * 就升权了。前缀是给人看的,库是给机器看的。
     */
    public static TokenScope hintFromPrefix(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        for (TokenScope v : values()) {
            if (plaintext.startsWith(v.prefix)) {
                return v;
            }
        }
        return null;
    }
}
