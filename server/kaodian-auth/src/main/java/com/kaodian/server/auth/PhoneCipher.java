package com.kaodian.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 手机号的哈希与加解密 —— docs/technical/INDEX.md §5.2「手机号不明文落库」的全部实现。
 *
 * <h2>两件事,两把密钥</h2>
 *
 * <table border="1">
 *   <caption>用途</caption>
 *   <tr><th>形态</th><th>算法</th><th>用途</th></tr>
 *   <tr><td>{@code hmac}</td><td>HMAC-SHA256</td><td><b>查</b>:唯一索引与登录查号</td></tr>
 *   <tr><td>{@code ciphertext}</td><td>AES-256-GCM</td><td><b>发</b>:要给这个号发短信时解一次</td></tr>
 * </table>
 *
 * 两把密钥分开,是因为它们的泄露后果不同:HMAC 密钥泄露 → 攻击者能<b>验证</b>某个号在不在库里;
 * AES 密钥泄露 → 攻击者能<b>读出</b>库里所有号。分开保管时,一次泄露不等于两件事同时发生。
 *
 * <h2>🔴 密钥丢了,所有人都登不进来</h2>
 *
 * HMAC 密钥换一把,同一个手机号算出的 {@code identifier} 就变了,
 * 于是<b>每一次登录都会走「查不到 → 建号」这条分支</b> —— 不报错,只是所有老用户
 * 从此各自多出一个空账号,而他们的记录留在那个再也查不到的旧账号上。
 * <p>
 * 这是本模块最坏的失败模式,而且它<b>完全无声</b>。所以:
 * <ul>
 *   <li>密钥优先从配置/环境变量读({@code KAODIAN_AUTH_KEYS_PHONE_HMAC} 等)</li>
 *   <li>没有配置时,自动生成一次并落到数据目录下的 {@code auth-keys.properties}(权限 600),
 *       同时打一条 WARN。<b>这是为了本机开发能直接跑起来,不是生产形态</b></li>
 *   <li>那个文件<b>属于数据,不属于配置</b> —— 备份数据目录时它必须一起被备份</li>
 * </ul>
 *
 * <h2>为什么不用 JCEKS / KeyStore</h2>
 *
 * KeyStore 自己也要一个口令,而那个口令又要放在某个地方 —— 在单机单进程的阶段 0/1,
 * 这一圈绕下来防护力没有增加,只增加了一个「口令忘了怎么办」。
 * 到了要上 KMS 的那天,换的是这个类的内部,{@link PhoneNumberSecret} 的形状不变。
 */
@Component
public class PhoneCipher {

    private static final Logger log = LoggerFactory.getLogger(PhoneCipher.class);

    private static final String KEY_FILE = "auth-keys.properties";
    private static final String PROP_HMAC = "phone.hmac.key";
    private static final String PROP_AES = "phone.aes.key";

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String AES_TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /**
     * 中国大陆手机号。<b>只认这一种</b>。
     *
     * <p>不做「宽进严出」:一个格式不对的号收不到短信,而短信是要花钱的 ——
     * 在发送前就挡掉,比发出去再看运营商回什么便宜。
     * 港澳台与海外不在阶段 2 范围内(docs/technical/INDEX.md §七 只写了国内签名与模板)。
     */
    private static final Pattern CN_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    private final SecureRandom random = new SecureRandom();

    private final SecretKeySpec hmacKey;
    private final SecretKeySpec aesKey;

    // 🔴 这个类有两个构造器,Spring 挑不出来 —— 少了这个注解,启动期报的是
    // 「No default constructor found」,而那句话和真正的原因(构造器歧义)毫无关系。
    // 另一个构造器是给测试用的:它直接收 Path,不碰配置也不碰用户目录。
    @Autowired
    public PhoneCipher(
            @Value("${kaodian.auth.keys.phone-hmac:}") String configuredHmac,
            @Value("${kaodian.auth.keys.phone-aes:}") String configuredAes,
            @Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(configuredHmac, configuredAes, Path.of(dataDir).resolve(KEY_FILE));
    }

    /** 测试直接给两把 base64 密钥,不落文件。 */
    public PhoneCipher(String hmacBase64, String aesBase64) {
        this.hmacKey = new SecretKeySpec(requireKeyBytes(hmacBase64, "phone-hmac"), HMAC_ALGO);
        this.aesKey = new SecretKeySpec(requireKeyBytes(aesBase64, "phone-aes"), "AES");
    }

    /**
     * 🔴 密钥必须正好 {@value #KEY_BYTES} 字节。
     *
     * <p>不校验的话,一把 16 字节的密钥会让 AES-256-GCM <b>静默降级成 AES-128</b> ——
     * 能加密、能解密、所有测试全绿,只是强度少了一半而且没人会发现。
     * 这与 {@link PhoneKeyGuard} 是同一条:<b>配置错误要响亮地失败。</b>
     */
    private static byte[] requireKeyBytes(String base64, String which) {
        byte[] k;
        try {
            k = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("密钥 " + which + " 不是合法 base64", e);
        }
        if (k.length != KEY_BYTES) {
            // 🔴 消息里绝不带密钥本身,只带长度。
            throw new IllegalStateException("密钥 " + which + " 必须是 " + KEY_BYTES
                    + " 字节(base64 解码后),实际 " + k.length
                    + " 字节 —— 短密钥会让 AES-256 静默降级");
        }
        return k;
    }

    private PhoneCipher(SecretKeySpec hmacKey, SecretKeySpec aesKey) {
        this.hmacKey = hmacKey;
        this.aesKey = aesKey;
    }

    /**
     * 用「上一把密钥」造一个只用来解旧密文的实例 —— 有计划轮换时用({@link PhoneKeyGuard})。
     *
     * <p>🔴 <b>没有 {@code hmacKeyBase64()} 这类取值方法,是有意的。</b>
     * 只换了一把时,另一把需要沿用当前的 —— 最省事的写法是把当前密钥暴露出去让调用方拼一个,
     * 而那等于给密钥开了一个 public 出口,{@code log.debug(cipher.key())} 从此只差有人写下它。
     * 所以派生这件事在这个类<b>内部</b>完成,密钥字节一步都不出去。
     *
     * @param previousHmacBase64 旧 HMAC 密钥;空表示这一把没换
     * @param previousAesBase64  旧 AES 密钥;空表示这一把没换
     */
    public PhoneCipher previousOf(String previousHmacBase64, String previousAesBase64) {
        return new PhoneCipher(
                emptyToNull(previousHmacBase64) == null
                        ? hmacKey
                        : new SecretKeySpec(requireKeyBytes(previousHmacBase64, "phone-hmac-previous"), HMAC_ALGO),
                emptyToNull(previousAesBase64) == null
                        ? aesKey
                        : new SecretKeySpec(requireKeyBytes(previousAesBase64, "phone-aes-previous"), "AES"));
    }

    PhoneCipher(String configuredHmac, String configuredAes, Path keyFile) {
        Properties fromFile = null;
        String hmac = emptyToNull(configuredHmac);
        String aes = emptyToNull(configuredAes);

        if (hmac == null || aes == null) {
            fromFile = loadOrCreate(keyFile);
            if (hmac == null) {
                hmac = fromFile.getProperty(PROP_HMAC);
            }
            if (aes == null) {
                aes = fromFile.getProperty(PROP_AES);
            }
            log.warn("手机号密钥未在配置里给出,已从 {} 读取/生成。"
                    + "这是本机开发形态 —— 生产请用环境变量 KAODIAN_AUTH_KEYS_PHONE_HMAC / _AES,"
                    + "并把这个文件当【数据】一起备份:换一把 HMAC 密钥 = 全部老用户静默登不回原账号。", keyFile);
        }

        this.hmacKey = new SecretKeySpec(requireKeyBytes(hmac, "phone-hmac"), HMAC_ALGO);
        this.aesKey = new SecretKeySpec(requireKeyBytes(aes, "phone-aes"), "AES");
    }

    /**
     * 这两把密钥的指纹 —— 盖在数据上,用来发现「换了密钥」这件事({@code R-59})。
     *
     * <p>指纹是<b>用密钥对一个固定常量算 HMAC</b> 的前 16 个十六进制字符。
     * 常量公开,但没有密钥就算不出这 16 个字符;拿到这 16 个字符也推不回 256 位的密钥。
     * 所以它可以明文躺在数据文件里。
     *
     * <p>两把分开算:丢 HMAC 能自动治好,丢 AES 治不好 —— 合成一个指纹的话这两种情况长得一样。
     * 见 {@link PhoneKeyFingerprint}。
     */
    public PhoneKeyFingerprint fingerprint() {
        return new PhoneKeyFingerprint(
                keyIdOf(hmacKey.getEncoded(), "kaodian:key-id:phone-hmac:v1"),
                keyIdOf(aesKey.getEncoded(), "kaodian:key-id:phone-aes:v1"));
    }

    private static String keyIdOf(byte[] key, String sentinel) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            return HexFormat.of().formatHex(mac.doFinal(sentinel.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("密钥指纹计算失败", e);
        }
    }

    /**
     * 规整成 11 位手机号。
     *
     * <p>去掉空格、连字符和 {@code +86}/{@code 86} 前缀 —— 用户从通讯录粘过来的号常常带这些。
     * 规整必须在哈希<b>之前</b>做:{@code 138 0013 8000} 和 {@code 13800138000} 是同一个号,
     * 但哈希出来是两个 identifier,于是同一个人会有两个账号。
     *
     * @throws IllegalArgumentException 不是中国大陆手机号
     */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("手机号不能为空");
        }
        String s = raw.replaceAll("[\\s\\-()]", "");
        if (s.startsWith("+86")) {
            s = s.substring(3);
        } else if (s.startsWith("86") && s.length() == 13) {
            s = s.substring(2);
        }
        if (!CN_MOBILE.matcher(s).matches()) {
            throw new IllegalArgumentException("手机号格式不对,只支持中国大陆手机号");
        }
        return s;
    }

    /** E.164,腾讯云短信 {@code PhoneNumberSet} 要的形态。 */
    public static String toE164(String normalized) {
        return "+86" + normalized;
    }

    /** {@code 138****6027}。<b>只为了显示</b> —— 界面 D11 / D23 用的就是这个形态。 */
    public static String mask(String normalized) {
        return normalized.substring(0, 3) + "****" + normalized.substring(7);
    }

    /** 手机号 → 三种形态。传进来的可以是用户原样输入的串。 */
    public PhoneNumberSecret protect(String rawPhone) {
        String normalized = normalize(rawPhone);
        return new PhoneNumberSecret(hmac(normalized), encrypt(normalized), mask(normalized));
    }

    /**
     * 只算哈希 —— 查号用。
     *
     * <p>登录查号不需要密文,单独留一个方法是为了让「查」这条路上<b>根本不产生密文</b>:
     * 少一次加密就少一次明文在内存里多待的机会。
     */
    public String hmacOf(String rawPhone) {
        return hmac(normalize(rawPhone));
    }

    /**
     * 密文 → 手机号明文。
     *
     * <p>🔴 <b>调用点应当是可数的</b>。目前只有一处:给已有账号补发短信。
     * 登录流程不走这里 —— 那条路上手机号本来就在请求里。
     */
    public String reveal(String ciphertext) {
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(all, 0, iv, 0, GCM_IV_BYTES);
            Cipher c = Cipher.getInstance(AES_TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = c.doFinal(all, GCM_IV_BYTES, all.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 不把密文本身写进异常消息 —— 它会流进日志。
            throw new IllegalStateException("手机号密文解不开,密钥可能已被更换", e);
        }
    }

    /**
     * 给任意短串算 HMAC。验证码用它 —— 见 {@link SmsCodeService}。
     *
     * <p>复用同一把密钥是有意的:验证码的生命周期只有 5 分钟,
     * 为它单独引入第三把密钥,增加的是「又一个能丢的东西」,而不是安全性。
     */
    public String hmacOfOpaque(String value) {
        return hmac(value);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private String encrypt(String plain) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(AES_TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(body, 0, all, iv.length, body.length);
            return Base64.getEncoder().encodeToString(all);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("手机号加密失败", e);
        }
    }

    // —— 密钥文件 ——

    private static Properties loadOrCreate(Path keyFile) {
        Properties p = new Properties();
        try {
            if (Files.exists(keyFile)) {
                try (InputStream in = Files.newInputStream(keyFile)) {
                    p.load(in);
                }
                if (p.getProperty(PROP_HMAC) != null && p.getProperty(PROP_AES) != null) {
                    return p;
                }
                // 文件在但缺键:补齐比报错好,但绝不覆盖已有的那一把 —— 覆盖 = 全员静默失联。
            }
            Path parent = keyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            SecureRandom rnd = new SecureRandom();
            if (p.getProperty(PROP_HMAC) == null) {
                p.setProperty(PROP_HMAC, newKey(rnd));
            }
            if (p.getProperty(PROP_AES) == null) {
                p.setProperty(PROP_AES, newKey(rnd));
            }
            try (OutputStream out = Files.newOutputStream(keyFile)) {
                p.store(out, "自动生成的本机开发密钥 —— 属于数据,备份数据目录时必须一起备份");
            }
            tightenPermissions(keyFile);
            return p;
        } catch (IOException e) {
            throw new IllegalStateException("鉴权密钥文件读写失败:" + keyFile, e);
        }
    }

    private static String newKey(SecureRandom rnd) {
        byte[] k = new byte[KEY_BYTES];
        rnd.nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    /** 600。失败不致命(比如 Windows 上没有 POSIX 权限),但要吵一声。 */
    private static void tightenPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("无法把 {} 的权限收紧到 600,请自行确认它不可被其他用户读取", file);
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
