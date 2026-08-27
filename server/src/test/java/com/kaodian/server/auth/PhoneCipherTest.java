package com.kaodian.server.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 手机号的三种形态。
 *
 * <p>这里钉住的是 docs/10 §5.2 那一行:<b>手机号不明文落库,哈希用于查、密文用于发短信。</b>
 */
class PhoneCipherTest {

    private static String key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    private final PhoneCipher cipher = new PhoneCipher(key(), key());

    @Test
    @DisplayName("规整必须在哈希之前:带空格和 +86 的同一个号只能得到一个 identifier")
    void normalizesBeforeHashing() {
        // 用户从通讯录粘过来的号常常带这些。少了规整这一步,同一个人会有好几个账号 ——
        // 而且每个账号各持一部分记录,覆盖率在每一个上都是错的。
        String canonical = cipher.hmacOfOpaque(PhoneCipher.normalize("13800138000"));
        for (String variant : new String[]{
                "138 0013 8000", "+8613800138000", "8613800138000", "138-0013-8000", " 13800138000 "}) {
            assertEquals(canonical, cipher.hmacOfOpaque(PhoneCipher.normalize(variant)), variant);
        }
    }

    @Test
    @DisplayName("非中国大陆手机号在发送之前就被挡住 —— 挡在花钱那一步之前")
    void rejectsNonMobile() {
        for (String bad : new String[]{"12345678901", "1380013800", "023-88886666", "abc", ""}) {
            assertThrows(IllegalArgumentException.class, () -> PhoneCipher.normalize(bad), bad);
        }
    }

    @Test
    @DisplayName("密文能解回明文,而且同一个号每次加密结果都不同(随机 IV)")
    void encryptsWithRandomIv() {
        PhoneNumberSecret a = cipher.protect("13800138000");
        PhoneNumberSecret b = cipher.protect("13800138000");

        assertEquals(a.hmac(), b.hmac(), "哈希必须稳定,否则查不到号");
        assertNotEquals(a.ciphertext(), b.ciphertext(), "密文必须每次不同,否则相同号码在库里一眼可见");
        assertEquals("13800138000", cipher.reveal(a.ciphertext()));
        assertEquals("13800138000", cipher.reveal(b.ciphertext()));
    }

    @Test
    @DisplayName("打码形态就是界面上那个 138****6027")
    void masks() {
        assertEquals("138****8000", PhoneCipher.mask("13800138000"));
        assertEquals("+8613800138000", PhoneCipher.toE164("13800138000"));
    }

    @Test
    @DisplayName("换一把密钥,同一个号算出的 identifier 就变了 —— 这是本模块最坏的失败模式")
    void differentKeyMeansDifferentIdentity() {
        // 它无声:每一次登录都会走「查不到 → 建号」这条分支,老用户各自多出一个空账号,
        // 而他们的记录留在那个再也查不到的旧账号上。密钥文件必须当数据备份。
        PhoneCipher other = new PhoneCipher(key(), key());
        assertNotEquals(cipher.hmacOf("13800138000"), other.hmacOf("13800138000"));
    }
}
