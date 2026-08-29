package com.kaodian.server.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code R-59} 的防线 —— <b>把这一层唯一一个会无声毁数据的失败模式钉住。</b>
 *
 * <p>没有这道守卫时的表现是:接口全部 200、日志一条没有,
 * 而每一次登录都在建新账号 —— 老用户的记录留在再也查不到的旧账号上,
 * 同时 {@link SignupLedger} 上多出一批假注册(那是关卡 3 判据的数据源)。
 *
 * <p>所以这里最要紧的一个用例是 {@link #hmacLostButAesIntactHealsItself}:
 * <b>最常见的那种丢失本来就不该让任何人半夜爬起来。</b>
 */
class PhoneKeyGuardTest {

    private static final String PHONE = "13800138000";

    @TempDir
    Path dir;

    private String hmacKey;
    private String aesKey;

    private static String newKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @BeforeEach
    void setUp() {
        hmacKey = newKey();
        aesKey = newKey();
    }

    private FileAccountStore store() {
        return new FileAccountStore(dir.resolve("acc.json"));
    }

    /** 用给定的两把密钥建一个手机号账号,返回 userId。 */
    private String seedAccount(String hmac, String aes) {
        PhoneCipher cipher = new PhoneCipher(hmac, aes);
        FileAccountStore accounts = store();
        new PhoneKeyGuard(accounts, cipher, null, false).check();       // 盖章

        var clock = new TestClock("2026-09-01T00:00:00Z");
        var service = new AccountService(accounts,
                new FileSignupLedger(dir.resolve("signups.json")),
                new TokenService(new FileTokenStore(dir.resolve("tok.json")), clock),
                cipher, clock);
        return service.loginByPhone(
                new SmsCodeService.VerifyOutcome.Passed(PHONE, cipher.hmacOf(PHONE)),
                "手机", null).user().id();
    }

    // —— 一、正常 ——

    @Test
    @DisplayName("密钥没变 → 放行")
    void unchanged() {
        seedAccount(hmacKey, aesKey);
        var outcome = new PhoneKeyGuard(store(), new PhoneCipher(hmacKey, aesKey), null, false).check();
        assertInstanceOf(PhoneKeyGuard.Outcome.Unchanged.class, outcome);
    }

    @Test
    @DisplayName("空库直接盖章 —— 下一次换钥就能被发现")
    void stampsEmptyStore() {
        var outcome = new PhoneKeyGuard(store(), new PhoneCipher(hmacKey, aesKey), null, false).check();
        assertInstanceOf(PhoneKeyGuard.Outcome.StampedOnEmpty.class, outcome);
        assertTrue(store().keyFingerprint().isPresent());
    }

    @Test
    @DisplayName("指纹推不回密钥,而且两把密钥各算各的")
    void fingerprintLeaksNothing() {
        PhoneKeyFingerprint fp = new PhoneCipher(hmacKey, aesKey).fingerprint();
        assertEquals(16, fp.hmacKeyId().length());
        assertNotEquals(fp.hmacKeyId(), fp.aesKeyId(), "两把用不同的常量算,不能撞在一起");
        assertFalse(hmacKey.contains(fp.hmacKeyId()));
        assertFalse(aesKey.contains(fp.aesKeyId()));

        // 换一把密钥 → 指纹必变
        assertNotEquals(fp.hmacKeyId(), new PhoneCipher(newKey(), aesKey).fingerprint().hmacKeyId());
    }

    // —— 二、🔴 最要紧的一条:HMAC 丢了但 AES 还在 ——

    @Test
    @DisplayName("🔴 HMAC 丢了但 AES 还在 → 自动换钥,零账号丢失,而且用户照样登得进去")
    void hmacLostButAesIntactHealsItself() {
        String userId = seedAccount(hmacKey, aesKey);

        // HMAC 换了一把(比如 auth-keys.properties 被部分覆盖后重新生成了 hmac)
        String newHmac = newKey();
        PhoneCipher after = new PhoneCipher(newHmac, aesKey);
        FileAccountStore accounts = store();

        var outcome = assertInstanceOf(PhoneKeyGuard.Outcome.Rekeyed.class,
                new PhoneKeyGuard(accounts, after, null, false).check());
        assertEquals(1, outcome.phoneCount());

        // 决定性断言:同一个手机号,用【新】密钥仍然查得到【原来那个】账号
        assertEquals(userId,
                accounts.findByIdentity(IdentityType.PHONE, after.hmacOf(PHONE)).orElseThrow().id(),
                "自愈之后必须还是原来那个账号 —— 否则这道守卫等于没做");

        // 密文也已经用新密钥重新加过,能解回明文
        assertEquals(PHONE, after.reveal(accounts.phoneSecretOf(userId).orElseThrow().ciphertext()));

        // 而且不会重复换 —— 第二次启动是 Unchanged
        assertInstanceOf(PhoneKeyGuard.Outcome.Unchanged.class,
                new PhoneKeyGuard(store(), after, null, false).check());
    }

    @Test
    @DisplayName("自愈之后再登录不会建新账号 —— 这正是 R-59 要防的那件事")
    void noPhantomAccountAfterHealing() {
        String userId = seedAccount(hmacKey, aesKey);
        FileSignupLedger ledger = new FileSignupLedger(dir.resolve("signups.json"));
        assertEquals(1, ledger.totalCount());

        PhoneCipher after = new PhoneCipher(newKey(), aesKey);
        FileAccountStore accounts = store();
        new PhoneKeyGuard(accounts, after, null, false).check();

        var clock = new TestClock("2026-09-02T00:00:00Z");
        var service = new AccountService(accounts, ledger,
                new TokenService(new FileTokenStore(dir.resolve("tok2.json")), clock), after, clock);
        var login = service.loginByPhone(
                new SmsCodeService.VerifyOutcome.Passed(PHONE, after.hmacOf(PHONE)), "手机", null);

        assertFalse(login.isNewAccount(), "🔴 这一行为 true 就意味着 R-59 正在发生");
        assertEquals(userId, login.user().id());
        assertEquals(1, ledger.totalCount(), "关卡 3 的累计注册数不能凭空多一笔");
    }

    // —— 三、两把都变了 ——

    @Test
    @DisplayName("🔴 AES 也变了且没给旧密钥 → 拒绝启动,而且把三条出路都说清楚")
    void refusesToStartWhenAesAlsoChanged() {
        seedAccount(hmacKey, aesKey);

        var ex = assertThrows(PhoneKeyGuard.KeyMismatchException.class,
                () -> new PhoneKeyGuard(store(), new PhoneCipher(newKey(), newKey()), null, false).check());

        // 一个只说「不匹配」的异常,会让人直接去找怎么关掉它
        String msg = ex.getMessage();
        assertTrue(msg.contains("auth-keys.properties"), "要说清楚最可能的原因");
        assertTrue(msg.contains("phone-hmac-previous"), "要给出有计划轮换的那条路");
        assertTrue(msg.contains("accept-key-loss"), "要给出确实丢了的那条路");
        assertTrue(msg.contains("1 个"), "要说清楚影响几个账号");

        // 拒绝启动之后数据没被动过
        assertEquals(1, store().phoneIdentityCount());
    }

    @Test
    @DisplayName("给了旧密钥 → 有计划轮换,零丢失")
    void plannedRotationWithPreviousKeys() {
        String userId = seedAccount(hmacKey, aesKey);

        PhoneCipher after = new PhoneCipher(newKey(), newKey());
        PhoneCipher previous = after.previousOf(hmacKey, aesKey);
        FileAccountStore accounts = store();

        var outcome = assertInstanceOf(PhoneKeyGuard.Outcome.Rekeyed.class,
                new PhoneKeyGuard(accounts, after, previous, false).check());
        assertEquals(1, outcome.phoneCount());
        assertEquals(userId,
                accounts.findByIdentity(IdentityType.PHONE, after.hmacOf(PHONE)).orElseThrow().id());
        assertEquals(PHONE, after.reveal(accounts.phoneSecretOf(userId).orElseThrow().ciphertext()));
    }

    @Test
    @DisplayName("只换了一把时,另一把沿用当前的 —— 不必把没变的那把也抄一遍")
    void previousOfFillsInTheUnchangedKey() {
        seedAccount(hmacKey, aesKey);

        PhoneCipher after = new PhoneCipher(newKey(), aesKey);        // 只换 HMAC
        PhoneCipher previous = after.previousOf(hmacKey, "");          // AES 留空
        assertTrue(previous.fingerprint().matches(new PhoneCipher(hmacKey, aesKey).fingerprint()));
    }

    @Test
    @DisplayName("给错了旧密钥仍然拒绝启动 —— 不能「试一下不行就算了」")
    void wrongPreviousKeyStillRefuses() {
        seedAccount(hmacKey, aesKey);
        PhoneCipher after = new PhoneCipher(newKey(), newKey());
        assertThrows(PhoneKeyGuard.KeyMismatchException.class,
                () -> new PhoneKeyGuard(store(), after, after.previousOf(newKey(), newKey()), false).check());
    }

    // —— 四、确实丢了 ——

    @Test
    @DisplayName("accept-key-loss 是「我确认这些账号找不回来」,不是「跳过检查」")
    void acceptKeyLossIsExplicit() {
        String orphaned = seedAccount(hmacKey, aesKey);
        PhoneCipher lost = new PhoneCipher(newKey(), newKey());
        FileAccountStore accounts = store();

        var outcome = assertInstanceOf(PhoneKeyGuard.Outcome.AcceptedLoss.class,
                new PhoneKeyGuard(accounts, lost, null, true).check());
        assertEquals(1, outcome.orphanedCount());

        // 章已改盖成新的,下次启动不再拦
        assertInstanceOf(PhoneKeyGuard.Outcome.Unchanged.class,
                new PhoneKeyGuard(store(), lost, null, false).check());

        // 而那个账号确实成了孤儿:它还在库里,但那个手机号再也查不到它
        assertTrue(store().findById(orphaned).isPresent());
        assertTrue(store().findByIdentity(IdentityType.PHONE, lost.hmacOf(PHONE)).isEmpty(),
                "这正是「不可逆」的具体含义 —— 所以它必须由人显式确认");
    }

    @Test
    @DisplayName("空库即使指纹对不上也不拦 —— 没有数据可丢,别拦住启动")
    void emptyStoreNeverBlocks() {
        // 先用一把密钥盖章,但不建任何账号
        new PhoneKeyGuard(store(), new PhoneCipher(hmacKey, aesKey), null, false).check();
        var outcome = new PhoneKeyGuard(store(), new PhoneCipher(newKey(), newKey()), null, false).check();
        assertInstanceOf(PhoneKeyGuard.Outcome.StampedOnEmpty.class, outcome);
    }

    // —— 五、向后兼容 ——

    @Test
    @DisplayName("老数据没有指纹 → 补盖 + WARN,不拒绝启动")
    void legacyDataWithoutFingerprint() {
        // 造一份没有指纹的账号数据:直接用 store 建号,不走守卫
        PhoneCipher cipher = new PhoneCipher(hmacKey, aesKey);
        FileAccountStore accounts = store();
        var secret = cipher.protect(PHONE);
        accounts.create(AppUser.fresh("u_legacy", java.time.Instant.parse("2026-08-01T00:00:00Z")),
                new UserIdentity("u_legacy", IdentityType.PHONE, secret.hmac(),
                        java.time.Instant.parse("2026-08-01T00:00:00Z")),
                secret);
        assertTrue(accounts.keyFingerprint().isEmpty());

        var outcome = assertInstanceOf(PhoneKeyGuard.Outcome.StampedOnLegacyData.class,
                new PhoneKeyGuard(store(), cipher, null, false).check());
        assertEquals(1, outcome.phoneCount());
        assertTrue(store().keyFingerprint().isPresent());
    }

    // —— 六、换钥本身的原子性 ——

    @Test
    @DisplayName("🔴 换钥必须维持「identifier == secret.hmac()」这条不变式")
    void rekeyKeepsTheInvariant() {
        String userId = seedAccount(hmacKey, aesKey);
        PhoneCipher after = new PhoneCipher(newKey(), aesKey);
        FileAccountStore accounts = store();
        new PhoneKeyGuard(accounts, after, null, false).check();

        var secret = accounts.phoneSecretOf(userId).orElseThrow();
        var identity = accounts.identitiesOf(userId).stream()
                .filter(i -> i.type() == IdentityType.PHONE).findFirst().orElseThrow();
        assertEquals(secret.hmac(), identity.identifier(),
                "对不上的那一条就是一个谁也登不进去的账号,而且不报错");
    }

    @Test
    @DisplayName("换钥之后文件里仍然没有手机号明文")
    void rekeyDoesNotLeakPlaintext() throws Exception {
        seedAccount(hmacKey, aesKey);
        new PhoneKeyGuard(store(), new PhoneCipher(newKey(), aesKey), null, false).check();
        String raw = java.nio.file.Files.readString(dir.resolve("acc.json"));
        assertFalse(raw.contains(PHONE));
    }
}
