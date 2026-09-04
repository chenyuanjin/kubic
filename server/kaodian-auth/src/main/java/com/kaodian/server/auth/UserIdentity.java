package com.kaodian.server.auth;

import java.time.Instant;

/**
 * 「这个账号可以被这个东西认出来」—— 一个通道一行。
 *
 * <p>唯一约束是 {@code (type, identifier)}(docs/technical/INDEX.md §5.2)。它同时承担两件事:
 * <ul>
 *   <li>登录:拿 {@code (type, identifier)} 查到 {@code userId},查不到就建号</li>
 *   <li>绑定:目标 identity 已属他人 → <b>返回可合并提示,不自动合并</b>(docs/technical/INDEX.md §6.1)</li>
 * </ul>
 *
 * <h2>🔴 手机号这一行里没有手机号</h2>
 *
 * {@code type=PHONE} 时 {@code identifier} 存的是 <b>HMAC</b>,不是 11 位数字。
 * 明文永远只以 AES 密文的形态存在({@link PhoneNumberSecret}),
 * 而且只在<b>要给这个号发短信</b>时才解一次。
 * <p>
 * 为什么不是 SHA-256 而是 HMAC:手机号的取值空间只有约 2×10⁹,
 * 一台笔记本几分钟就能把全部中国手机号的 SHA-256 算完做成彩虹表。
 * <b>不带密钥的哈希对手机号等于没哈希</b> —— HMAC 的那把密钥才是这条防线本身。
 *
 * @param userId     属于哪个账号
 * @param type       通道
 * @param identifier 该通道下的标识。手机号是 HMAC,微信是 unionid/openid 原值
 * @param boundAt    绑定时刻
 */
public record UserIdentity(
        long userId,
        IdentityType type,
        String identifier,
        Instant boundAt
) {

    public UserIdentity {
        if (userId < AppUser.FIRST_USER_ID) {
            throw new IllegalArgumentException("身份必须属于某个账号,实得 userId:" + userId);
        }
        if (type == null) {
            throw new IllegalArgumentException("身份必须有类型");
        }
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("身份必须有标识");
        }
        if (boundAt == null) {
            throw new IllegalArgumentException("身份必须有绑定时刻");
        }
    }

    /** 唯一索引的那把键。 */
    public String uniqueKey() {
        return type.wireName() + ":" + identifier;
    }

    public static String uniqueKey(IdentityType type, String identifier) {
        return type.wireName() + ":" + identifier;
    }
}
