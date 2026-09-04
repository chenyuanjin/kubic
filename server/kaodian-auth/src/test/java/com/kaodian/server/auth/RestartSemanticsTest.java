package com.kaodian.server.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code M5-2} 重启语义 —— <b>判据只有一句:重启导致它失效,算放松一道防线还是收紧一道防线?</b>
 * ({@code M5-账号与登录通道} §2.3)
 *
 * <table border="1">
 *   <caption>两类不许混成一类</caption>
 *   <tr><th>落点</th><th>重启失效算</th><th>落不落盘</th></tr>
 *   <tr><td>{@code PhoneLock} / 频控计数</td><td><b>放松</b> —— 攻击者重启一次就洗掉锁定</td>
 *       <td>必须落盘({@code SmsCodeServiceTest} 已钉)</td></tr>
 *   <tr><td>微信 {@code state}</td><td>✅ <b>收紧</b> —— 最坏是用户重来一次</td><td><b>不落盘</b>,本类钉</td></tr>
 * </table>
 *
 * <p>🔴 把 {@code state} 落盘不会让它更安全,只会让一个一次性凭证多一份可被翻出来的副本。
 */
class RestartSemanticsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("🔴 微信 state 不落盘 —— 重启后所有在途授权作废(这是收紧,不是放松)")
    void oneTimeStateDoesNotSurviveRestart() {
        TestClock clock = new TestClock("2026-09-01T00:00:00Z");
        OneTimeStateStore before = new OneTimeStateStore(clock);
        String state = before.issue();
        assertTrue(before.consume(state), "同一个进程里当然核销得掉");

        // 「重启」:换一个实例。没有任何文件参与,所以它一定是空的。
        OneTimeStateStore after = new OneTimeStateStore(clock);
        assertFalse(after.consume(state),
                "🔴 state 一旦能挺过重启,它就多了一份可被翻出来的副本 —— 而它是一次性凭证");
    }

    @Test
    @DisplayName("state 是一次性的 —— 核销过就不能再核销")
    void stateIsSingleUse() {
        OneTimeStateStore s = new OneTimeStateStore(new TestClock("2026-09-01T00:00:00Z"));
        String state = s.issue();
        assertTrue(s.consume(state));
        assertFalse(s.consume(state), "重放同一个 state 就是那次 CSRF 绑号本身");
    }

    @Test
    @DisplayName("🔴 M5-14 注册流水少于账号数 → 对上一次账,给出确切条数(不拒绝启动)")
    void signupLedgerReconciliationReportsTheGap() {
        FileAccountStore accounts = new FileAccountStore(dir.resolve("accounts.json"));
        FileSignupLedger signups = new FileSignupLedger(dir.resolve("signups.json"));
        TestClock clock = new TestClock("2026-09-01T00:00:00Z");
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        String key = Base64.getEncoder().encodeToString(k);
        PhoneCipher cipher = new PhoneCipher(key, key);
        AccountService service = new AccountService(accounts, signups,
                new TokenService(new FileTokenStore(dir.resolve("tok.json")), clock), cipher, clock);

        assertEquals(0, service.reconcileSignupLedger(), "空库不该报差");

        // 模拟 R-69:账号建出来了,而流水那一笔没落下(create 成功后崩溃)
        Instant now = clock.instant();
        long id = accounts.nextUserId();
        accounts.create(AppUser.fresh(id, now),
                new UserIdentity(id, IdentityType.WX_OPEN, "o_crashed", now), null);

        assertEquals(1, service.reconcileSignupLedger(),
                "🔴 差 1 条。允许偏差不等于偏差不可见 —— 阶段 3 的「累计注册」据此人工加回");

        // 补上那一笔之后就不该再报 —— 否则这条 WARN 会变成一句每次启动都出现的噪音,
        // 而每次都出现的告警等于没有告警。
        signups.record(new SignupLedger.Entry(id, now, IdentityType.WX_OPEN, null));
        assertEquals(0, service.reconcileSignupLedger());
    }
}
