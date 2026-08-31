package com.kaodian.server.auth;

import com.kaodian.server.auth.vendor.CaptchaVerifier;
import com.kaodian.server.auth.vendor.SmsDeliveryException;
import com.kaodian.server.auth.vendor.SmsSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 四道闸的顺序,与四种终态的四句话 —— docs/后端详设 §1.8 那两张图。
 *
 * <p><b>顺序恰恰是这件事的全部。</b> 契约(docs/技术架构 §6.1)把五条约束都列全了,
 * 但没有写它们的先后;把滑块挪到发送之后,前三道闸就只是在给账单排队。
 * 这个测试的作用就是让那个顺序无法被悄悄改掉。
 */
class SmsCodeServiceTest {

    private static final String PHONE = "13800138000";
    private static final String IP = "203.0.113.7";

    @TempDir
    Path dir;

    private TestClock clock;
    private RecordingSender sender;
    private TogglableCaptcha captcha;
    private SmsCodeStore store;
    private SmsRateLimiter limiter;
    private SmsCodeService service;
    private PhoneCipher cipher;

    private PhoneCipher cipherOf() {
        return cipher;
    }

    /** 记下每一次「花钱」。用它来断言「拒绝时一分钱没花」。 */
    private static final class RecordingSender implements SmsSender {

        final List<String> sent = new ArrayList<>();
        SmsDeliveryException failWith;

        @Override
        public void sendVerificationCode(String e164Phone, String code) throws SmsDeliveryException {
            if (failWith != null) {
                throw failWith;
            }
            sent.add(code);
        }

        @Override
        public boolean isReal() {
            return false;
        }

        String lastCode() {
            return sent.get(sent.size() - 1);
        }
    }

    private static final class TogglableCaptcha implements CaptchaVerifier {

        boolean pass = true;
        int calls;

        @Override
        public Verdict verify(String ticket, String randstr, String userIp) {
            calls++;
            return pass ? Verdict.pass() : Verdict.fail("测试里判不通过");
        }

        @Override
        public boolean isReal() {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new TestClock("2026-09-01T02:00:00Z");     // 北京时间 10:00
        sender = new RecordingSender();
        captcha = new TogglableCaptcha();
        store = new FileSmsCodeStore(dir.resolve("sms.json"));
        limiter = new FileSmsRateLimiter(dir.resolve("quota.json"), ZoneId.of("Asia/Shanghai"));
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        String key = Base64.getEncoder().encodeToString(k);
        cipher = new PhoneCipher(key, key);
        service = new SmsCodeService(store, limiter, captcha, sender, cipher, clock);
    }

    private SmsCodeService.SendOutcome send() {
        return service.send(PHONE, SmsPurpose.LOGIN, "ticket", "randstr", IP);
    }

    // —— ① 滑块 ——

    @Test
    @DisplayName("🔴 滑块不通过 → 一条短信都没发出去,一分钱没花")
    void captchaIsTheFirstGate() {
        captcha.pass = false;

        assertInstanceOf(SmsCodeService.SendOutcome.CaptchaFailed.class, send());
        assertTrue(sender.sent.isEmpty(), "拦要拦在花钱那一步之前");

        // 而且没占用日额度:被滑块挡掉的请求不该消耗用户当天的 10 条
        captcha.pass = true;
        assertInstanceOf(SmsCodeService.SendOutcome.Sent.class, send());
    }

    // —— ②③ 频控 ——

    @Test
    @DisplayName("单号 1/60s,而且告诉用户准确的时点")
    void perPhoneCooldown() {
        assertInstanceOf(SmsCodeService.SendOutcome.Sent.class, send());

        var again = assertInstanceOf(SmsCodeService.SendOutcome.TooFrequent.class, send());
        assertEquals(clock.instant().plusSeconds(60), again.retryAt());
        assertEquals(1, sender.sent.size());

        clock.advance(Duration.ofSeconds(61));
        assertInstanceOf(SmsCodeService.SendOutcome.Sent.class, send());
    }

    @Test
    @DisplayName("单号 10/日,重置时点按北京时间的自然日 —— 用户说的「明天」是北京的明天")
    void perPhoneDailyLimit() {
        for (int i = 0; i < 10; i++) {
            assertInstanceOf(SmsCodeService.SendOutcome.Sent.class, send(), "第 " + (i + 1) + " 条");
            clock.advance(Duration.ofSeconds(61));
        }
        var out = assertInstanceOf(SmsCodeService.SendOutcome.DailyExhausted.class, send());
        assertTrue(out.perPhone());
        assertEquals(10, out.limit());
        // 北京时间 9-02 00:00 == UTC 9-01 16:00
        assertEquals("2026-09-01T16:00:00Z", out.resetAt().toString());
        assertEquals(10, sender.sent.size());
    }

    // —— ④ 花钱那一步的失败 ——

    @Test
    @DisplayName("我们自己的配置错误(签名没批)不该吃掉用户的日额度")
    void refundsDailyQuotaOnOurOwnFailure() {
        sender.failWith = new SmsDeliveryException(
                "签名未报备", "FailedOperation.SignatureIncorrectOrUnapproved", true);

        var failed = assertInstanceOf(SmsCodeService.SendOutcome.SendFailed.class, send());
        assertTrue(failed.quotaRefunded());

        // 日额度还回去了,但 60 秒冷却没还 —— 不能对着一个正在故障的接口连打
        assertInstanceOf(SmsCodeService.SendOutcome.TooFrequent.class, send());

        clock.advance(Duration.ofSeconds(61));
        sender.failWith = null;
        assertInstanceOf(SmsCodeService.SendOutcome.Sent.class, send());
    }

    @Test
    @DisplayName("不确定有没有发出去时,额度不还 —— 还回去等于允许再发一条")
    void doesNotRefundWhenUncertain() {
        sender.failWith = new SmsDeliveryException("超时", new java.io.IOException("timeout"));
        var failed = assertInstanceOf(SmsCodeService.SendOutcome.SendFailed.class, send());
        assertFalse(failed.quotaRefunded());
    }

    // —— 四种终态 ——

    @Test
    @DisplayName("正常路径:发→验→建号信息带回来")
    void happyPath() {
        send();
        var passed = assertInstanceOf(SmsCodeService.VerifyOutcome.Passed.class,
                service.verify(PHONE, sender.lastCode(), SmsPurpose.LOGIN));
        assertEquals(PHONE, passed.phone());
        assertNotEquals(PHONE, passed.phoneHmac(), "带出去的必须是哈希,不是号码");
    }

    @Test
    @DisplayName("单次使用:同一条码验第二次不再通过")
    void singleUse() {
        send();
        String code = sender.lastCode();
        assertInstanceOf(SmsCodeService.VerifyOutcome.Passed.class,
                service.verify(PHONE, code, SmsPurpose.LOGIN));
        assertInstanceOf(SmsCodeService.VerifyOutcome.NoneOutstanding.class,
                service.verify(PHONE, code, SmsPurpose.LOGIN));
    }

    @Test
    @DisplayName("🔴 过期与作废必须是两种不同的回答,不能都说「验证码错误」")
    void expiredAndSupersededAreDistinct() {
        send();
        String first = sender.lastCode();

        // 已作废:同一个号又发了一条新的 → 用户该做的是「用新的那条」
        clock.advance(Duration.ofSeconds(61));
        send();
        assertInstanceOf(SmsCodeService.VerifyOutcome.Superseded.class,
                service.verify(PHONE, first, SmsPurpose.LOGIN));

        // 已过期 → 用户该做的是「重发」
        clock.advance(SmsCodeService.CODE_TTL.plusSeconds(1));
        assertInstanceOf(SmsCodeService.VerifyOutcome.Expired.class,
                service.verify(PHONE, sender.lastCode(), SmsPurpose.LOGIN));
    }

    @Test
    @DisplayName("过期不靠定时任务改状态,靠时间推出来 —— 重启一次也不会让过期的码复活")
    void expiryIsDerivedFromTime() {
        send();
        String code = sender.lastCode();
        clock.advance(SmsCodeService.CODE_TTL.plusSeconds(1));

        // 没有任何后台任务跑过。换一个新的 store 实例(= 重新从文件载入,相当于重启)之后
        // 那条码在库里的 state 仍然写着 SENT —— 判过期的是时间,不是某个改状态的任务。
        SmsCodeStore reloaded = new FileSmsCodeStore(dir.resolve("sms.json"));
        SmsCodeService afterRestart = new SmsCodeService(
                reloaded, limiter, captcha, sender, cipherOf(), clock);
        assertInstanceOf(SmsCodeService.VerifyOutcome.Expired.class,
                afterRestart.verify(PHONE, code, SmsPurpose.LOGIN));
    }

    // —— 错 5 次锁定 ——

    @Test
    @DisplayName("连续错 5 次 → 锁 30 分钟,而且期间连发新码都不给发")
    void locksAfterFiveFailures() {
        send();
        String wrong = wrongCodeOtherThan(sender.lastCode());

        for (int i = 1; i <= 4; i++) {
            var w = assertInstanceOf(SmsCodeService.VerifyOutcome.Wrong.class,
                    service.verify(PHONE, wrong, SmsPurpose.LOGIN), "第 " + i + " 次");
            assertEquals(PhoneLock.MAX_FAILURES - i, w.remainingAttempts(),
                    "要让用户知道还有几次,而不是撞到锁定才发现");
        }
        var locked = assertInstanceOf(SmsCodeService.VerifyOutcome.Locked.class,
                service.verify(PHONE, wrong, SmsPurpose.LOGIN));
        assertEquals(clock.instant().plus(PhoneLock.LOCK_WINDOW), locked.unlockAt());

        // 锁着的时候再发一条也没意义 —— 而那一条是要花钱的
        clock.advance(Duration.ofSeconds(61));
        int before = sender.sent.size();
        assertInstanceOf(SmsCodeService.SendOutcome.PhoneLocked.class, send());
        assertEquals(before, sender.sent.size());

        clock.advance(PhoneLock.LOCK_WINDOW.plusSeconds(1));
        assertInstanceOf(SmsCodeService.SendOutcome.Sent.class, send());
    }

    @Test
    @DisplayName("解锁后仍有 5 次机会 —— 计数在锁上的那一刻归零")
    void failureCounterResetsOnLock() {
        send();
        String wrong = wrongCodeOtherThan(sender.lastCode());
        for (int i = 0; i < 5; i++) {
            service.verify(PHONE, wrong, SmsPurpose.LOGIN);
        }
        clock.advance(PhoneLock.LOCK_WINDOW.plusSeconds(1));
        clock.advance(Duration.ofSeconds(61));
        send();

        var w = assertInstanceOf(SmsCodeService.VerifyOutcome.Wrong.class,
                service.verify(PHONE, wrongCodeOtherThan(sender.lastCode()), SmsPurpose.LOGIN));
        assertEquals(4, w.remainingAttempts(), "否则解锁后第一次输错会立刻再锁 30 分钟");
    }

    @Test
    @DisplayName("验证成功即清零错误计数")
    void successResetsFailureCounter() {
        send();
        String wrong = wrongCodeOtherThan(sender.lastCode());
        service.verify(PHONE, wrong, SmsPurpose.LOGIN);
        service.verify(PHONE, wrong, SmsPurpose.LOGIN);
        service.verify(PHONE, sender.lastCode(), SmsPurpose.LOGIN);

        clock.advance(Duration.ofSeconds(61));
        send();
        var w = assertInstanceOf(SmsCodeService.VerifyOutcome.Wrong.class,
                service.verify(PHONE, wrongCodeOtherThan(sender.lastCode()), SmsPurpose.LOGIN));
        assertEquals(4, w.remainingAttempts());
    }

    // —— 用途隔离 ——

    @Test
    @DisplayName("🔴 登录的码换不了绑定 —— 防跨场景重放")
    void purposeIsCheckedToo() {
        send();     // purpose = LOGIN
        assertInstanceOf(SmsCodeService.VerifyOutcome.Wrong.class,
                service.verify(PHONE, sender.lastCode(), SmsPurpose.BIND));
    }

    // —— 其它 ——

    @Test
    @DisplayName("没发过码时说「请先获取」,不说「验证码错误」")
    void noneOutstanding() {
        assertInstanceOf(SmsCodeService.VerifyOutcome.NoneOutstanding.class,
                service.verify(PHONE, "123456", SmsPurpose.LOGIN));
    }

    @Test
    @DisplayName("六位数字,含前导零 —— 截掉前导零会让 10% 的码变成五位")
    void codeIsAlwaysSixDigits() {
        for (int i = 0; i < 40; i++) {
            send();
            assertEquals(6, sender.lastCode().length(), "第 " + i + " 条:" + sender.lastCode());
            assertTrue(sender.lastCode().chars().allMatch(Character::isDigit));
            clock.advance(Duration.ofSeconds(61));
            if ((i + 1) % 10 == 0) {
                clock.advance(Duration.ofDays(1));      // 跨日重置 10 条/日
            }
        }
    }

    @Test
    @DisplayName("🔴 号码锁定必须挺过一次重启 —— 否则绕过它的方法是「等一次发版」")
    void lockSurvivesRestart() {
        send();
        String wrong = wrongCodeOtherThan(sender.lastCode());
        for (int i = 0; i < 5; i++) {
            service.verify(PHONE, wrong, SmsPurpose.LOGIN);
        }

        SmsCodeStore reloaded = new FileSmsCodeStore(dir.resolve("sms.json"));
        SmsCodeService afterRestart = new SmsCodeService(
                reloaded, limiter, captcha, sender, cipher, clock);
        assertInstanceOf(SmsCodeService.VerifyOutcome.Locked.class,
                afterRestart.verify(PHONE, wrong, SmsPurpose.LOGIN));

        // 🔴 光断言「锁着」不够:锁上的那一刻 failedCount 必须归零,而这个性质也要挺过重启。
        // 不归零的话,解锁后第一次输错会【立刻再锁 30 分钟】——
        // 而用户完全不知道自己为什么只有一次机会。
        PhoneLock afterLock = reloaded.lockOf(cipher.hmacOf(PHONE));
        assertEquals(0, afterLock.failedCount(), "锁上时计数必须归零,且要挺过重启");
        assertEquals(PhoneLock.MAX_FAILURES, afterLock.remainingAttempts(),
                "解锁之后应当仍有满额的重试次数");
    }

    @Test
    @DisplayName("🔴 日额度计数也必须挺过重启 —— 这条链路的另一端连着真实账单")
    void dailyQuotaSurvivesRestart() {
        for (int i = 0; i < 10; i++) {
            send();
            clock.advance(Duration.ofSeconds(61));
        }
        SmsRateLimiter reloaded = new FileSmsRateLimiter(
                dir.resolve("quota.json"), ZoneId.of("Asia/Shanghai"));
        SmsCodeService afterRestart = new SmsCodeService(
                store, reloaded, captcha, sender, cipher, clock);
        assertInstanceOf(SmsCodeService.SendOutcome.DailyExhausted.class,
                afterRestart.send(PHONE, SmsPurpose.LOGIN, "t", "r", IP));
    }

    private static String wrongCodeOtherThan(String actual) {
        return actual.equals("000000") ? "111111" : "000000";
    }
}
