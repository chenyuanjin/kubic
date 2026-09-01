package com.kaodian.server.auth;

/**
 * 一个手机号在库里的两种形态 —— <b>哈希用于查,密文用于发短信</b>(docs/technical/INDEX.md §5.2)。
 *
 * <p>这是整个后端唯一持有「能还原出手机号」的东西的地方。它不是一张表的行,
 * 而是 {@link PhoneCipher} 的输出:{@code hmac} 进 {@link UserIdentity#identifier},
 * {@code ciphertext} 单独存。
 *
 * <h2>为什么要存密文,不能只存 HMAC</h2>
 *
 * HMAC 不可逆,而「重新给这个用户发一条短信」需要真实号码。只存 HMAC 的后果是
 * <b>用户每次收短信都必须自己再输一遍号码</b> —— 这在验证码登录场景恰好成立
 * (用户本来就要输),但在「账号安全提醒」「注销倒计时通知」这类场景不成立。
 * <p>
 * 所以两样都留。代价是密钥一旦泄露,库里的手机号就是明文的 —— 这一点不遮掩,
 * 见 {@link PhoneCipher} 关于密钥保管的那一段。
 *
 * @param hmac       带密钥的哈希,十六进制。<b>唯一索引建在它上面</b>
 * @param ciphertext AES-GCM 密文(含随机 IV),base64。解开它需要密钥
 * @param masked     打码形态,如 {@code 138****6027}。<b>只为了显示,不参与任何判断</b>
 */
public record PhoneNumberSecret(String hmac, String ciphertext, String masked) {

    public PhoneNumberSecret {
        if (hmac == null || hmac.isBlank()) {
            throw new IllegalArgumentException("手机号哈希不能为空");
        }
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("手机号密文不能为空");
        }
    }
}
