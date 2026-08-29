package com.kaodian.server.auth;

import com.kaodian.server.auth.vendor.WeChatIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信联合登录 —— docs/10 §7.1 那张场景表,以及表里<b>没有</b>写到的两条。
 *
 * <p>表里写的是「unionid 已存在 → 直接登录;不存在 → 建新账号」。
 * 但 unionid <b>不是从第一天就有的</b>,于是有一条它没覆盖的路:
 * <b>先用 openid 建了账号,后来绑好开放平台,同一个人再登录就被拆成两个账号。</b>
 * 用户什么都没做错 —— 那是 {@code R-33},而且是我们自己造的。
 */
class WeChatFederationTest {

    private static final String PHONE = "13800138000";
    private static final String OPENID_A = "o_entryA_zhangsan";
    private static final String OPENID_B = "o_entryB_zhangsan";
    private static final String UNIONID = "u_zhangsan";

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

    private WeChatIdentity withUnion(String openid) {
        return new WeChatIdentity(openid, UNIONID);
    }

    private WeChatIdentity openIdOnly(String openid) {
        return new WeChatIdentity(openid, "");
    }

    private SmsCodeService.VerifyOutcome.Passed phonePassed() {
        return new SmsCodeService.VerifyOutcome.Passed(PHONE, cipher.hmacOf(PHONE));
    }

    // —— 契约表里的四条 ——

    @Test
    @DisplayName("unionid 已存在 → 直接登录,不建新号")
    void unionIdHitLogsIn() {
        var first = service.loginByWeChat(withUnion(OPENID_A), "手机", null);
        assertTrue(first.isNewAccount());

        var again = service.loginByWeChat(withUnion(OPENID_A), "电脑", null);
        assertFalse(again.isNewAccount());
        assertEquals(first.user().id(), again.user().id());
        assertEquals(1, signups.totalCount());
    }

    @Test
    @DisplayName("换个入口(openid 变了)但 unionid 相同 → 还是同一个账号")
    void differentEntrySameUnionId() {
        var fromMiniProgram = service.loginByWeChat(withUnion(OPENID_A), "小程序", null);
        var fromWebsite = service.loginByWeChat(withUnion(OPENID_B), "网站扫码", null);

        assertEquals(fromMiniProgram.user().id(), fromWebsite.user().id(),
                "unionid 才是跨入口同一个人的锚点");
        assertFalse(fromWebsite.isNewAccount());
        assertEquals(1, signups.totalCount());
        // 两个入口的 openid 都被记下来了 —— 将来任一入口取不到 unionid 也认得出
        assertEquals(3, service.identitiesOf(fromMiniProgram.user().id()).size());
    }

    @Test
    @DisplayName("已登录状态下绑微信是最顺的路 —— 不产生第二个账号")
    void bindWhileLoggedInIsTheSmoothPath() {
        String u = service.loginByPhone(phonePassed(), "手机", null).user().id();
        assertInstanceOf(AccountService.BindResult.Bound.class,
                service.bind(u, IdentityType.WX_UNION, UNIONID, null));

        var wxLogin = service.loginByWeChat(withUnion(OPENID_A), "小程序", null);
        assertFalse(wxLogin.isNewAccount());
        assertEquals(u, wxLogin.user().id());
        assertEquals(1, signups.totalCount());
    }

    @Test
    @DisplayName("微信登录建了新号且没有手机号 → 调用方能看出该引导补绑")
    void newWeChatAccountHasNoPhone() {
        var r = service.loginByWeChat(withUnion(OPENID_A), "小程序", null);
        assertTrue(r.isNewAccount());
        assertTrue(service.maskedPhoneOf(r.user().id()).isEmpty(),
                "没有手机号 = 这个人下次换个入口进来可能又多一个账号");
    }

    // —— 🔴 契约表里没有的那条:unionid 从无到有 ——

    @Test
    @DisplayName("🔴 先用 openid 建号,后来拿到 unionid → 补一行,而不是建第二个账号")
    void openIdAccountIsUpgradedNotDuplicated() {
        // ① 没绑开放平台:只拿得到 openid
        var before = service.loginByWeChat(openIdOnly(OPENID_A), "小程序", null);
        assertTrue(before.isNewAccount());
        assertEquals(1, signups.totalCount());

        // ② 控制台里把小程序绑到了开放平台
        // ③ 同一个人、同一个入口再登录 —— 这次带回了 unionid
        var after = service.loginByWeChat(withUnion(OPENID_A), "小程序", null);

        assertFalse(after.isNewAccount(), "🔴 这一行为 true 就是我们自己造出来的 R-33");
        assertEquals(before.user().id(), after.user().id());
        assertEquals(1, signups.totalCount(), "累计注册数不能凭空多一笔");
        assertNull(after.splitMergeToken(), "这不是分裂,是升级 —— 不该提示合并");

        // unionid 已经补上,下次从别的入口进来也认得出
        assertEquals(before.user().id(),
                accounts.findByIdentity(IdentityType.WX_UNION, UNIONID).orElseThrow().id());
    }

    @Test
    @DisplayName("🔴 分裂已经发生时:登进 unionid 那个,并给出合并建议 —— 但绝不自动合并")
    void existingSplitIsSuggestedNotAutoMerged() {
        // 分裂的造法:入口 A 没绑开放平台时建了账号1;入口 B 绑了,建了账号2
        String acc1 = service.loginByWeChat(openIdOnly(OPENID_A), "入口A", null).user().id();
        String acc2 = service.loginByWeChat(withUnion(OPENID_B), "入口B", null).user().id();
        assertNotEquals(acc1, acc2);
        assertEquals(2, signups.totalCount());

        // 现在入口 A 也绑好了:同一次登录里 unionid 命中账号2、openidA 命中账号1
        var r = service.loginByWeChat(withUnion(OPENID_A), "入口A", null);

        assertEquals(acc2, r.user().id(), "登进 unionid 那个 —— 它是「现在这个人」的权威身份");
        assertNotNull(r.splitMergeToken(), "必须把分裂这件事告诉用户");
        assertFalse(r.isNewAccount());

        // 🔴 什么都还没合并:账号1 还活着,记录还在它那儿
        assertTrue(accounts.findById(acc1).orElseThrow().isActive());
        assertTrue(accounts.mergeLogs().isEmpty());

        // 那个令牌是能用的 —— 用户点了确认才真的合并
        var preview = service.previewMerge(acc2, r.splitMergeToken());
        assertNotNull(preview.expiresAt());
        service.confirmMerge(acc2, r.splitMergeToken());
        assertFalse(accounts.findById(acc1).orElseThrow().isActive());
    }

    // —— 一步登录:两条通道同时落到一个账号 ——

    @Test
    @DisplayName("🔴 老用户(手机号注册)从小程序一步登录 → 登进原账号,零新账号")
    void oneStepLoginFindsExistingPhoneAccount() {
        String existing = service.loginByPhone(phonePassed(), "H5", null).user().id();
        assertEquals(1, signups.totalCount());

        var r = service.loginByWeChatWithPhone(withUnion(OPENID_A), PHONE, "小程序", null);

        assertFalse(r.isNewAccount(), "🔴 这一行为 true 就意味着老用户打开小程序看见的是一片空白");
        assertEquals(existing, r.user().id());
        assertNull(r.splitMergeToken());
        assertEquals(1, signups.totalCount());
        // 微信身份已经挂上去了 —— 两条通道从此在同一个账号上
        assertEquals(existing,
                accounts.findByIdentity(IdentityType.WX_UNION, UNIONID).orElseThrow().id());
    }

    @Test
    @DisplayName("全新用户一步登录 → 建一个号,手机号和微信一起挂上,不需要事后补绑")
    void oneStepLoginForBrandNewUser() {
        var r = service.loginByWeChatWithPhone(withUnion(OPENID_A), PHONE, "小程序", "渠道码X");

        assertTrue(r.isNewAccount());
        assertEquals("138****8000", service.maskedPhoneOf(r.user().id()).orElseThrow());
        assertEquals(3, service.identitiesOf(r.user().id()).size(), "phone + wx_union + wx_open");
        assertNull(r.splitMergeToken(), "R-33 根本没有发生的机会");

        // 注册流水记的是 phone 通道 —— 手机号是这个产品阶段 2 唯一的锚点
        assertEquals(IdentityType.PHONE, signups.all().get(0).channel());
        assertEquals("渠道码X", signups.all().get(0).referrer());
    }

    @Test
    @DisplayName("🔴 两边各有账号时登进【手机号】那个 —— 记录大概率在那边")
    void oneStepPrefersThePhoneAccount() {
        String phoneAcc = service.loginByPhone(phonePassed(), "H5", null).user().id();
        String wxAcc = service.loginByWeChat(withUnion(OPENID_A), "小程序", null).user().id();
        assertNotEquals(phoneAcc, wxAcc);

        var r = service.loginByWeChatWithPhone(withUnion(OPENID_A), PHONE, "小程序", null);

        assertEquals(phoneAcc, r.user().id(),
                "阶段 2 只有手机号通道,微信在关卡 2 后 —— 微信那个必然更晚建、更可能是空号");
        assertNotNull(r.splitMergeToken());
        assertTrue(accounts.findById(wxAcc).orElseThrow().isActive(), "仍然不自动合并");
    }

    @Test
    @DisplayName("一步登录不需要短信验证码 —— 微信那一步已经是运营商级验证")
    void oneStepNeedsNoSmsCode() {
        // 没有任何 SmsCodeService 参与,直接就登进来了
        var r = service.loginByWeChatWithPhone(withUnion(OPENID_A), PHONE, "小程序", null);
        assertTrue(tokens.verify(r.token().plaintext()).isPresent());
    }

    // —— 边界 ——

    @Test
    @DisplayName("注销过的账号不会被联合逻辑复活")
    void deactivatedAccountIsNotReused() {
        String old = service.loginByWeChat(withUnion(OPENID_A), "小程序", null).user().id();
        service.deactivate(old);

        var again = service.loginByWeChat(withUnion(OPENID_A), "小程序", null);
        assertTrue(again.isNewAccount());
        assertNotEquals(old, again.user().id());
        assertEquals(2, signups.totalCount());
    }

    @Test
    @DisplayName("两个人分别用两个微信 → 两个账号,互不串号")
    void twoPeopleStaySeparate() {
        var a = service.loginByWeChat(new WeChatIdentity("o_a", "u_a"), "手机", null);
        var b = service.loginByWeChat(new WeChatIdentity("o_b", "u_b"), "手机", null);
        assertNotEquals(a.user().id(), b.user().id());
        assertEquals(2, signups.totalCount());
        assertNull(a.splitMergeToken());
        assertNull(b.splitMergeToken());
    }

    @Test
    @DisplayName("补挂身份失败不能让登录失败 —— 登录成功是这条路的底线")
    void linkingFailureDoesNotBreakLogin() {
        // 让 openidA 先属于另一个账号
        String other = service.loginByWeChat(openIdOnly(OPENID_A), "别人的入口", null).user().id();
        // 再让一个已有 unionid 的账号带着同一个 openidA 登录 → openid 挂不上去
        String mine = service.loginByWeChat(withUnion(OPENID_B), "我的", null).user().id();

        var r = service.loginByWeChat(withUnion(OPENID_A), "我的另一个入口", null);
        assertNotNull(r.token().plaintext(), "无论如何都要登进来");
        assertEquals(mine, r.user().id());
        assertNotNull(r.splitMergeToken());
        assertTrue(accounts.findById(other).orElseThrow().isActive());
    }
}
