package com.kaodian.server.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注册即登录、绑定、合并、注销 —— docs/13 §1.7 与 docs/10 §7.1。
 *
 * <p>其中最要紧的一条是 {@link #signupLedgerSurvivesMerge}:
 * <b>合并会把已经发生过的注册从主表里抹掉</b>,而关卡 3 的判据是「累计」。
 */
class AccountServiceTest {

    private static final String PHONE_A = "13800138000";
    private static final String PHONE_B = "13900139000";

    @TempDir
    Path dir;

    private TestClock clock;
    private PhoneCipher cipher;
    private AccountStore accounts;
    private SignupLedger signups;
    private TokenService tokens;
    private AccountService service;

    @BeforeEach
    void setUp() {
        clock = new TestClock("2026-09-01T00:00:00Z");
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        String key = Base64.getEncoder().encodeToString(k);
        cipher = new PhoneCipher(key, key);
        accounts = new FileAccountStore(dir.resolve("acc.json"));
        signups = new FileSignupLedger(dir.resolve("signups.json"));
        tokens = new TokenService(new FileTokenStore(dir.resolve("tok.json")), clock);
        service = new AccountService(accounts, signups, tokens, cipher, clock);
    }


    /** 微信身份:openid 由 unionid 派生,保证两个不同的 unionid 不会共用 openid。 */
    private static com.kaodian.server.auth.vendor.WeChatIdentity wx(String unionid) {
        return new com.kaodian.server.auth.vendor.WeChatIdentity("o_" + unionid, unionid);
    }

    private SmsCodeService.VerifyOutcome.Passed passed(String phone) {
        return new SmsCodeService.VerifyOutcome.Passed(phone, cipher.hmacOf(phone));
    }

    // —— 注册即登录 ——

    @Test
    @DisplayName("号没见过就建号,见过就登进去 —— 没有独立的注册")
    void registrationIsLogin() {
        AccountService.LoginResult first = service.loginByPhone(passed(PHONE_A), "手机", "落地页A");
        assertTrue(first.isNewAccount());
        assertEquals(1, service.totalSignups());

        AccountService.LoginResult second = service.loginByPhone(passed(PHONE_A), "电脑", null);
        assertFalse(second.isNewAccount());
        assertEquals(first.user().id(), second.user().id());
        assertEquals(1, service.totalSignups(), "第二次登录不能算成第二次注册");

        // 多设备并存
        assertEquals(2, tokens.sessionsOf(first.user().id()).size());
    }

    @Test
    @DisplayName("🔴 关卡 3 的累计注册数不能只数登录成功次数")
    void signupCountIsNotLoginCount() {
        service.loginByPhone(passed(PHONE_A), "d1", null);
        service.loginByPhone(passed(PHONE_A), "d2", null);
        service.loginByPhone(passed(PHONE_A), "d3", null);
        service.loginByPhone(passed(PHONE_B), "d4", null);

        assertEquals(2, service.totalSignups(), "4 次登录成功,但只有 2 个人");
    }

    @Test
    @DisplayName("🔴 合并抹掉了一个账号,但累计注册数不能跟着往回走")
    void signupLedgerSurvivesMerge() {
        // 两端各建过一个账号 —— 正是 R-33 描述的那个场面
        String a = service.loginByPhone(passed(PHONE_A), "手机", null).user().id();
        String b = service.loginByWeChat(wx("union_xyz"), "电脑", null).user().id();
        assertEquals(2, service.totalSignups());

        // b 想把 a 的手机号绑过来 → 已属他人 → 给出合并令牌
        var taken = assertInstanceOf(AccountService.BindResult.TakenByAnother.class,
                service.bind(b, IdentityType.PHONE, cipher.hmacOf(PHONE_A), PHONE_A));
        service.confirmMerge(b, taken.pending().token());

        assertFalse(accounts.findById(a).orElseThrow().isActive(), "被并走的账号应当已注销");
        assertEquals(2, service.totalSignups(),
                "从 app_user 数 count(*) 的话这里会变成 1 —— 一个只会单调增长的指标开始往回走");
    }

    // —— 绑定 ——

    @Test
    @DisplayName("目标身份已属他人 → 只给合并令牌,绝不自动合并")
    void bindNeverAutoMerges() {
        String a = service.loginByPhone(passed(PHONE_A), "手机", null).user().id();
        String b = service.loginByWeChat(wx("union_xyz"), "电脑", null).user().id();

        var result = service.bind(b, IdentityType.PHONE, cipher.hmacOf(PHONE_A), PHONE_A);
        assertInstanceOf(AccountService.BindResult.TakenByAnother.class, result);

        // 什么都还没发生:a 还在,手机号还挂在 a 上
        assertTrue(accounts.findById(a).orElseThrow().isActive());
        assertEquals(a, accounts.findByIdentity(IdentityType.PHONE, cipher.hmacOf(PHONE_A))
                .orElseThrow().id());
    }

    @Test
    @DisplayName("已登录状态下绑微信是最顺的那条路 —— 不产生第二个账号")
    void bindWeChatWhileLoggedIn() {
        String u = service.loginByPhone(passed(PHONE_A), "手机", null).user().id();
        assertInstanceOf(AccountService.BindResult.Bound.class,
                service.bind(u, IdentityType.WX_UNION, "union_abc", null));

        assertEquals(1, service.totalSignups());
        assertEquals(u, accounts.findByIdentity(IdentityType.WX_UNION, "union_abc").orElseThrow().id());
        assertEquals(2, service.identitiesOf(u).size());
    }

    @Test
    @DisplayName("重复绑同一个身份是幂等的,不报错")
    void bindIsIdempotent() {
        String u = service.loginByPhone(passed(PHONE_A), "手机", null).user().id();
        service.bind(u, IdentityType.WX_UNION, "union_abc", null);
        assertInstanceOf(AccountService.BindResult.Bound.class,
                service.bind(u, IdentityType.WX_UNION, "union_abc", null));
        assertEquals(2, service.identitiesOf(u).size());
    }

    // —— 合并 ——

    @Test
    @DisplayName("合并令牌一次性,而且别人的令牌用不了")
    void mergeTokenIsSingleUseAndScoped() {
        service.loginByPhone(passed(PHONE_A), "手机", null);
        String b = service.loginByWeChat(wx("u1"), "电脑", null).user().id();
        String c = service.loginByWeChat(wx("u2"), "平板", null).user().id();

        var taken = assertInstanceOf(AccountService.BindResult.TakenByAnother.class,
                service.bind(b, IdentityType.PHONE, cipher.hmacOf(PHONE_A), PHONE_A));
        String token = taken.pending().token();

        assertThrows(IllegalStateException.class, () -> service.confirmMerge(c, token),
                "别人的合并令牌不能用");

        service.confirmMerge(b, token);
        assertThrows(IllegalStateException.class, () -> service.confirmMerge(b, token),
                "一次性:重复提交不能打两次");
    }

    @Test
    @DisplayName("合并令牌 5 分钟过期 —— 它授权的是一件不可逆的事")
    void mergeTokenExpires() {
        service.loginByPhone(passed(PHONE_A), "手机", null);
        String b = service.loginByWeChat(wx("u1"), "电脑", null).user().id();
        var taken = assertInstanceOf(AccountService.BindResult.TakenByAnother.class,
                service.bind(b, IdentityType.PHONE, cipher.hmacOf(PHONE_A), PHONE_A));

        clock.advance(Duration.ofMinutes(6));
        assertThrows(IllegalStateException.class,
                () -> service.previewMerge(b, taken.pending().token()));
    }

    @Test
    @DisplayName("合并会立刻断掉被并走那个账号的全部会话")
    void mergeRevokesSessionsOfMergedAccount() {
        var a = service.loginByPhone(passed(PHONE_A), "手机", null);
        String b = service.loginByWeChat(wx("u1"), "电脑", null).user().id();
        var taken = assertInstanceOf(AccountService.BindResult.TakenByAnother.class,
                service.bind(b, IdentityType.PHONE, cipher.hmacOf(PHONE_A), PHONE_A));

        assertTrue(tokens.verify(a.token().plaintext()).isPresent());
        service.confirmMerge(b, taken.pending().token());
        assertTrue(tokens.verify(a.token().plaintext()).isEmpty());
    }

    @Test
    @DisplayName("预览是只读的:调两次不产生任何副作用")
    void previewHasNoSideEffects() {
        service.loginByPhone(passed(PHONE_A), "手机", null);
        String b = service.loginByWeChat(wx("u1"), "电脑", null).user().id();
        var taken = assertInstanceOf(AccountService.BindResult.TakenByAnother.class,
                service.bind(b, IdentityType.PHONE, cipher.hmacOf(PHONE_A), PHONE_A));

        var p1 = service.previewMerge(b, taken.pending().token());
        var p2 = service.previewMerge(b, taken.pending().token());
        assertEquals(p1.fromLabel(), p2.fromLabel());
        assertEquals("138****8000", p1.fromLabel());
        assertTrue(accounts.mergeLogs().isEmpty());
    }

    // —— 注销 ——

    @Test
    @DisplayName("注销:全部会话立刻失效,identity 一并摘掉")
    void deactivate() {
        var login = service.loginByPhone(passed(PHONE_A), "手机", null);
        service.deactivate(login.user().id());

        assertTrue(tokens.verify(login.token().plaintext()).isEmpty());
        assertTrue(accounts.findByIdentity(IdentityType.PHONE, cipher.hmacOf(PHONE_A)).isEmpty(),
                "手机号会被运营商回收,不摘掉的话它永远登不回来也永远给不了别人");
    }

    @Test
    @DisplayName("注销之后同一个号再来是一次全新的注册,而旧的那笔流水还在")
    void reRegisterAfterDeactivate() {
        String old = service.loginByPhone(passed(PHONE_A), "手机", null).user().id();
        service.deactivate(old);

        var again = service.loginByPhone(passed(PHONE_A), "手机", null);
        assertTrue(again.isNewAccount());
        assertNotEquals(old, again.user().id());
        assertEquals(2, service.totalSignups(),
                "关卡 3 问的是「有多少人注册过」,不是「现在还有多少人在」");
    }

    // —— 落盘 ——

    @Test
    @DisplayName("重启之后账号还在,而且文件里没有手机号明文")
    void survivesRestartWithoutPlaintextPhone() throws Exception {
        String id = service.loginByPhone(passed(PHONE_A), "手机", null).user().id();

        String raw = java.nio.file.Files.readString(dir.resolve("acc.json"));
        assertFalse(raw.contains(PHONE_A), "🔴 账号文件里不能出现手机号明文");
        assertTrue(raw.contains("138****8000"), "打码形态可以有 —— 它只为了显示");

        AccountStore reloaded = new FileAccountStore(dir.resolve("acc.json"));
        assertEquals(id, reloaded.findByIdentity(IdentityType.PHONE, cipher.hmacOf(PHONE_A))
                .orElseThrow().id());
    }
}
