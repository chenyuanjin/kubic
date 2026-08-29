package com.kaodian.server.auth;

/**
 * 两把手机号密钥的指纹 —— <b>盖在数据上,用来发现「换了密钥」这件事</b>。
 *
 * <h2>为什么必须盖章</h2>
 *
 * {@code R-59}:HMAC 密钥换一把,同一个手机号算出的 {@code identifier} 就变了,
 * 于是<b>每一次登录都会走「查不到 → 建号」分支</b>。不报错,只是所有老用户
 * 从此各自多出一个空账号,记录留在那个再也查不到的旧账号上。
 * <p>
 * 这是这一层唯一一个<b>会毁数据而且全程无声</b>的失败模式。
 * 指纹的作用就是把它从「无声」变成「启动期的一次响亮失败」——
 * 与 {@code AuthBeans#checkVendorPairing} 同一条:<b>警告会被划过去,数据丢了回不来。</b>
 *
 * <h2>指纹本身泄露不了密钥</h2>
 *
 * 它是<b>用密钥对一个固定常量算 HMAC</b> 的前 16 个十六进制字符。
 * 常量是公开的,但没有密钥就算不出这 16 个字符;反过来,拿到这 16 个字符
 * 也推不回 256 位的密钥。所以它可以明文躺在数据文件里。
 *
 * <h2>🔴 两把分开记,因为丢一把和丢两把的后果完全不同</h2>
 *
 * <table border="1">
 *   <caption>丢失后果</caption>
 *   <tr><th>丢的是</th><th>还能不能救</th><th>怎么救</th></tr>
 *   <tr><td>只有 HMAC 密钥</td><td><b>能,而且零损失</b></td>
 *       <td>AES 密文还在 → 解出手机号 → 用新密钥重算 HMAC。见 {@link PhoneKeyGuard}</td></tr>
 *   <tr><td>AES 密钥(无论 HMAC 在不在)</td><td><b>不能</b></td>
 *       <td>手机号明文再也拿不回来,{@code identifier} 也就再也算不出来</td></tr>
 * </table>
 *
 * 合成一个指纹的话,这两种情况长得一模一样 —— 而<b>第一种本可以自动治好</b>。
 *
 * @param hmacKeyId HMAC 密钥的指纹
 * @param aesKeyId  AES 密钥的指纹
 */
public record PhoneKeyFingerprint(String hmacKeyId, String aesKeyId) {

    public PhoneKeyFingerprint {
        if (hmacKeyId == null || hmacKeyId.isBlank() || aesKeyId == null || aesKeyId.isBlank()) {
            throw new IllegalArgumentException("密钥指纹不能为空");
        }
    }

    /** 两把都没变。 */
    public boolean matches(PhoneKeyFingerprint other) {
        return other != null
                && hmacKeyId.equals(other.hmacKeyId())
                && aesKeyId.equals(other.aesKeyId());
    }

    /** AES 没变 —— 这是「能不能自动治好」的<b>唯一判据</b>。 */
    public boolean sameAesAs(PhoneKeyFingerprint other) {
        return other != null && aesKeyId.equals(other.aesKeyId());
    }

    @Override
    public String toString() {
        return "hmac=" + hmacKeyId + " aes=" + aesKeyId;
    }
}
