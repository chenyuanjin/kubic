package com.kaodian.server.auth;

import com.kaodian.server.auth.vendor.CaptchaVerifier;
import com.kaodian.server.auth.vendor.SmsSender;
import com.kaodian.server.auth.vendor.WeChatIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发路径 —— <b>这一组是外部审阅逼出来的。</b>
 *
 * <p>它们全都不是「理论上可能」:用户在登录页连点两次、前端在网络抖动时重试一次,
 * 就是这些用例模拟的东西。而在补上它们之前,229 个测试<b>一个都撞不到</b> ——
 * 单线程跑完永远是绿的。
 *
 * <p>这条本身值得记下来:<b>「测试全绿」对并发缺陷没有任何证明力,
 * 除非测试自己是并发的。</b>
 */
class ConcurrencyTest {

    private static final String PHONE = "13800138000";
    private static final int THREADS = 8;

    @TempDir
    Path dir;

    private TestClock clock;
    private PhoneCipher cipher;
    private AccountStore accounts;
    private SignupLedger signups;
    private TokenService tokens;
    private AccountService service;
    private SmsCodeStore codes;
    private SmsCodeService sms;
    private final List<String> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new TestClock("2026-09-01T02:00:00Z");
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        String key = Base64.getEncoder().encodeToString(k);
        cipher = new PhoneCipher(key, key);
        accounts = new FileAccountStore(dir.resolve("acc.json"));
        signups = new FileSignupLedger(dir.resolve("signups.json"));
        tokens = new TokenService(new FileTokenStore(dir.resolve("tok.json")), clock);
        service = new AccountService(accounts, signups, tokens, cipher, clock);
        codes = new FileSmsCodeStore(dir.resolve("sms.json"));
        sms = new SmsCodeService(codes,
                new FileSmsRateLimiter(dir.resolve("quota.json"), ZoneId.of("Asia/Shanghai")),
                new CaptchaVerifier() {
                    @Override
                    public Verdict verify(String t, String r, String ip) {
                        return Verdict.pass();
                    }

                    @Override
                    public boolean isReal() {
                        return true;
                    }
                },
                new SmsSender() {
                    @Override
                    public void sendVerificationCode(String e164Phone, String code) {
                        sent.add(code);
                    }

                    @Override
                    public boolean isReal() {
                        return false;
                    }
                },
                cipher, clock);
    }

    /** 让 N 个线程在同一瞬间起跑,尽量把窗口撞开。 */
    private <T> List<T> raceAll(Callable<T> task) throws Exception {
        CyclicBarrier gate = new CyclicBarrier(THREADS);
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }

    // —— 审阅 #1:并发建号 ——

    @Test
    @DisplayName("🔴 同一个号并发登录 8 次 → 一个账号、一笔注册、零异常")
    void concurrentFirstLoginDoesNotThrow() throws Exception {
        var passed = new SmsCodeService.VerifyOutcome.Passed(PHONE, cipher.hmacOf(PHONE));

        List<AccountService.LoginResult> results = raceAll(
                () -> service.loginByPhone(passed, "设备", null));

        // 在修好之前,这里会有 7 个线程拿到 IdentityTakenException(→ 线上就是 500)
        assertEquals(THREADS, results.size());
        long id = results.get(0).user().id();
        for (var r : results) {
            assertEquals(id, r.user().id(), "🔴 同一个手机号不能登进两个不同的账号");
            assertNotNull(r.token().plaintext(), "每一个请求都必须真的登进来");
        }
        assertEquals(1, results.stream().filter(AccountService.LoginResult::isNewAccount).count(),
                "只有一次算新注册");
        assertEquals(1, signups.totalCount(),
                "🔴 阶段 3 的累计注册数不能因为用户连点两次就多记");
    }

    @Test
    @DisplayName("🔴 微信通道并发首登同样只建一个账号")
    void concurrentWeChatFirstLogin() throws Exception {
        WeChatIdentity wx = new WeChatIdentity("o_race", "u_race");
        List<AccountService.LoginResult> results = raceAll(
                () -> service.loginByWeChat(wx, "设备", null));

        long id = results.get(0).user().id();
        for (var r : results) {
            assertEquals(id, r.user().id());
        }
        assertEquals(1, signups.totalCount());
    }

    @Test
    @DisplayName("🔴 一步登录并发时,那 0.03 元已经花了 —— 更不能回 500")
    void concurrentOneStepLogin() throws Exception {
        WeChatIdentity wx = new WeChatIdentity("o_step", "u_step");
        List<AccountService.LoginResult> results = raceAll(
                () -> service.loginByWeChatWithPhone(wx, PHONE, "小程序", null));

        long id = results.get(0).user().id();
        for (var r : results) {
            assertEquals(id, r.user().id());
            assertNotNull(r.token().plaintext());
        }
        assertEquals(1, signups.totalCount());
    }

    // —— 审阅 #2:验证码核销 ——

    @Test
    @DisplayName("🔴 同一条验证码并发校验 8 次 → 只有一次通过(单次使用)")
    void codeIsConsumedExactlyOnce() throws Exception {
        sms.send(PHONE, SmsPurpose.LOGIN, "t", "r", "203.0.113.9");
        String code = sent.get(sent.size() - 1);

        List<SmsCodeService.VerifyOutcome> outcomes = raceAll(
                () -> sms.verify(PHONE, code, SmsPurpose.LOGIN));

        long passed = outcomes.stream()
                .filter(o -> o instanceof SmsCodeService.VerifyOutcome.Passed).count();
        assertEquals(1, passed,
                "🔴 「单次使用」在并发下也必须成立 —— 否则同一条码能换出多个会话");

        // 抢输的那些拿到的是「没有待验证的码」,不是「验证码错误」——
        // 后者会把用户往「我是不是输错了」的方向带,而他其实已经登录成功了。
        assertTrue(outcomes.stream()
                        .filter(o -> !(o instanceof SmsCodeService.VerifyOutcome.Passed))
                        .allMatch(o -> o instanceof SmsCodeService.VerifyOutcome.NoneOutstanding),
                "抢输的应当是 NoneOutstanding");
    }

    @Test
    @DisplayName("🔴 并发输错不能把「错 5 次锁定」变成「错 8 次仍不锁」")
    void failureCounterDoesNotLoseUpdates() throws Exception {
        sms.send(PHONE, SmsPurpose.LOGIN, "t", "r", "203.0.113.9");
        String wrong = sent.get(sent.size() - 1).equals("000000") ? "111111" : "000000";

        // 8 个并发错误猜测。读-改-写不原子的话,计数只会前进 1~2 格,号不会被锁。
        raceAll(() -> sms.verify(PHONE, wrong, SmsPurpose.LOGIN));

        assertTrue(codes.lockOf(cipher.hmacOf(PHONE)).isLockedAt(clock.instant()),
                "🔴 8 次失败必须锁上 —— 计数丢更新等于让攻击者用并发把这道闸打对折");
    }

    // —— 令牌 ——

    @Test
    @DisplayName("并发签发不会互相盖掉:8 条会话都在,而且都能用")
    void concurrentIssueKeepsAllSessions() throws Exception {
        long userId = service.loginByPhone(
                new SmsCodeService.VerifyOutcome.Passed(PHONE, cipher.hmacOf(PHONE)),
                "第一台", null).user().id();

        List<IssuedToken> issued = raceAll(
                () -> tokens.issue(userId, TokenScope.FULL, "并发设备"));

        for (IssuedToken t : issued) {
            assertTrue(tokens.verify(t.plaintext()).isPresent(), "每一条签出来的令牌都必须可用");
        }
        assertEquals(THREADS + 1, tokens.sessionsOf(userId).size(), "多设备并存,不能丢写");
    }

    @Test
    @DisplayName("并发预约短信名额不会超发 —— 日限是硬的")
    void rateLimiterDoesNotOverGrant() throws Exception {
        SmsRateLimiter limiter =
                new FileSmsRateLimiter(dir.resolve("q2.json"), ZoneId.of("Asia/Shanghai"));
        AtomicInteger allowed = new AtomicInteger();
        // 同一个 IP、不同的号,绕开 60 秒冷却,只压 IP 那一维(20/日)
        raceAll(() -> {
            for (int i = 0; i < 5; i++) {
                var d = limiter.reserve("p" + Thread.currentThread().threadId() + "_" + i,
                        "198.51.100.7", clock.instant());
                if (d instanceof SmsRateLimiter.Decision.Allowed) {
                    allowed.incrementAndGet();
                }
            }
            return null;
        });
        assertTrue(allowed.get() <= 20,
                "🔴 单 IP 20/日 在并发下也必须是上限,实际放行 " + allowed.get());
    }
}
