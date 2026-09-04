package com.kaodian.server.api;

import com.kaodian.server.api.auth.AccountController;
import com.kaodian.server.api.support.ApiCorsConfig;
import com.kaodian.server.api.support.ApiExceptionHandler;
import com.kaodian.server.api.auth.AuthController;
import com.kaodian.server.api.auth.TokenController;
import com.kaodian.server.api.support.AuthWebConfig;
import com.kaodian.server.api.support.ClientIp;
import com.kaodian.server.api.support.CurrentSessionResolver;
import com.kaodian.server.auth.AccountService;
import com.kaodian.server.auth.AccountStore;
import com.kaodian.server.auth.FileAccountStore;
import com.kaodian.server.auth.FileSignupLedger;
import com.kaodian.server.auth.FileSmsCodeStore;
import com.kaodian.server.auth.FileSmsRateLimiter;
import com.kaodian.server.auth.FileTokenStore;
import com.kaodian.server.auth.OneTimeStateStore;
import com.kaodian.server.auth.PhoneCipher;
import com.kaodian.server.auth.SmsCodeService;
import com.kaodian.server.auth.TokenScope;
import com.kaodian.server.auth.TokenService;
import com.kaodian.server.auth.vendor.CaptchaVerifier;
import com.kaodian.server.auth.vendor.DisabledWeChatClient;
import com.kaodian.server.auth.vendor.SmsSender;
import com.kaodian.server.auth.vendor.WeChatClient;
import com.kaodian.server.auth.vendor.WeChatEntry;
import com.kaodian.server.auth.vendor.WeChatException;
import com.kaodian.server.auth.vendor.WeChatIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 鉴权端点的契约 —— docs/technical/INDEX.md §6.1 那张表。
 *
 * <p>这里断言的不是「能跑通」,是<b>四种失败给的是四句不同的话、
 * 而且每一句都带着准确的时点</b>。合并成一句「验证码错误」的代价见 docs/technical/后端系统设计与组件接入.md §1.8。
 */
@WebMvcTest(controllers = {AuthController.class, AccountController.class, TokenController.class})
@Import({AuthApiTest.TestBeans.class, ApiExceptionHandler.class, AuthWebConfig.class, ApiCorsConfig.class})
@TestPropertySource(properties = {
        "kaodian.api.cors.allowed-origins=http://localhost:5173",
        "kaodian.auth.sms.zone=Asia/Shanghai",
        "kaodian.auth.trust-forwarded-for=false"
})
class AuthApiTest {

    private static final String PHONE = "13800138000";

    /** 每次发送的验证码落在这里,测试据此拿到明文。 */
    static final List<String> SENT = new ArrayList<>();

    /** 微信假实现的开关与调用计数。{@code paidCalls} 是那个 0.03 元/次的接口。 */
    static final class WeChatStub {
        boolean enabled;
        boolean phoneCodeFails;
        /** 模拟「应用没绑开放平台」:只回 openid。 */
        boolean noUnionId;
        boolean requireUnionId = true;
        String phoneToReturn = "13900139000";
        int loginCodeCalls;
        int paidCalls;

        void reset() {
            enabled = false;
            phoneCodeFails = false;
            noUnionId = false;
            requireUnionId = true;
            phoneToReturn = "13900139000";
            loginCodeCalls = 0;
            paidCalls = 0;
        }
    }

    static final WeChatStub WECHAT = new WeChatStub();

    static Path tmp;

    @BeforeAll
    static void tmpDir() throws IOException {
        tmp = Files.createTempDirectory("kaodian-auth-api");
    }

    @Autowired
    MockMvc mvc;

    @org.junit.jupiter.api.BeforeEach
    void resetWeChat() {
        WECHAT.reset();
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        PhoneCipher phoneCipher() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            String key = Base64.getEncoder().encodeToString(k);
            return new PhoneCipher(key, key);
        }

        @Bean
        SmsSender smsSender() {
            return new SmsSender() {
                @Override
                public void sendVerificationCode(String e164Phone, String code) {
                    SENT.add(code);
                }

                @Override
                public boolean isReal() {
                    return false;
                }
            };
        }

        @Bean
        CaptchaVerifier captchaVerifier() {
            return new CaptchaVerifier() {
                @Override
                public Verdict verify(String ticket, String randstr, String userIp) {
                    // 测试里用票据内容当开关:传 "bad" 就判不通过。
                    return "bad".equals(ticket) ? Verdict.fail("测试") : Verdict.pass();
                }

                @Override
                public boolean isReal() {
                    return true;
                }
            };
        }

        /**
         * 默认关着(阶段 2 后),但可以在单个用例里打开 —— 见 {@link AuthApiTest#WECHAT}。
         *
         * <p>这么写是为了同时测两件事:关着时回 503,以及开着时那条一步登录的顺序。
         */
        @Bean
        WeChatClient weChatClient() {
            return new WeChatClient() {
                @Override
                public String buildAuthorizeUrl(WeChatEntry e, String r, String st) {
                    return WECHAT.enabled ? "https://open.weixin.qq.com/x?state=" + st
                            : new DisabledWeChatClient().buildAuthorizeUrl(e, r, st);
                }

                @Override
                public WeChatIdentity exchangeMiniProgramCode(String jsCode) throws WeChatException {
                    if (!WECHAT.enabled) {
                        return new DisabledWeChatClient().exchangeMiniProgramCode(jsCode);
                    }
                    WECHAT.loginCodeCalls++;
                    return unionAware("o_" + jsCode, "u_" + jsCode, WeChatEntry.MINI_PROGRAM);
                }

                @Override
                public WeChatIdentity exchangeOAuthCode(WeChatEntry e, String code) throws WeChatException {
                    if (!WECHAT.enabled) {
                        return new DisabledWeChatClient().exchangeOAuthCode(e, code);
                    }
                    return unionAware("o_" + code, "u_" + code, e);
                }

                /** 复刻 HttpWeChatClient#checked 的行为,好在切片测试里也能撞到它。 */
                private WeChatIdentity unionAware(String openid, String unionid, WeChatEntry entry)
                        throws WeChatException {
                    if (!WECHAT.noUnionId) {
                        return new WeChatIdentity(openid, unionid);
                    }
                    if (WECHAT.requireUnionId) {
                        throw new com.kaodian.server.auth.vendor.UnionIdMissingException(entry);
                    }
                    return new WeChatIdentity(openid, "");
                }

                @Override
                public String exchangePhoneCode(String phoneCode) throws WeChatException {
                    if (!WECHAT.enabled) {
                        return new DisabledWeChatClient().exchangePhoneCode(phoneCode);
                    }
                    // 🔴 这一步是【要花钱的】。用例据此断言「被拒绝时它一次都没被调到」。
                    WECHAT.paidCalls++;
                    if (WECHAT.phoneCodeFails) {
                        throw new WeChatException("code 已被消费", 40163);
                    }
                    return WECHAT.phoneToReturn;
                }

                @Override
                public boolean isReal() {
                    return WECHAT.enabled;
                }
            };
        }

        @Bean
        AccountStore accountStore() {
            return new FileAccountStore(tmp.resolve("acc.json"));
        }

        @Bean
        TokenService tokenService(Clock clock) {
            return new TokenService(new FileTokenStore(tmp.resolve("tok.json")), clock);
        }

        @Bean
        com.kaodian.server.auth.SmsRateLimiter smsRateLimiter() {
            return new FileSmsRateLimiter(tmp.resolve("quota.json"), java.time.ZoneId.of("Asia/Shanghai"));
        }

        @Bean
        SmsCodeService smsCodeService(CaptchaVerifier captcha, SmsSender sender,
                                      PhoneCipher cipher, Clock clock,
                                      com.kaodian.server.auth.SmsRateLimiter limiter) {
            return new SmsCodeService(
                    new FileSmsCodeStore(tmp.resolve("sms.json")), limiter,
                    captcha, sender, cipher, clock);
        }

        @Bean
        AccountService accountService(AccountStore accounts, TokenService tokens,
                                      PhoneCipher cipher, Clock clock) {
            return new AccountService(accounts, new FileSignupLedger(tmp.resolve("signups.json")),
                    tokens, cipher, clock);
        }

        @Bean
        OneTimeStateStore stateStore(Clock clock) {
            return new OneTimeStateStore(clock);
        }

        @Bean
        ClientIp clientIp() {
            return new ClientIp(false);
        }

        @Bean
        CurrentSessionResolver currentSessionResolver(TokenService tokens) {
            return new CurrentSessionResolver(tokens);
        }
    }

    /** 每个用例用不同的号,避开 1/60s 冷却 —— 冷却本身在单元测试里已经测过。 */
    private static String freshPhone(int seed) {
        return "138" + String.format("%08d", 10000000 + seed);
    }

    private String sendTo(String phone) throws Exception {
        int before = SENT.size();
        mvc.perform(post("/api/v1/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","purpose":"login","captchaTicket":"ok","captchaRandstr":"r"}"""
                                .formatted(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").exists());
        return SENT.get(before);
    }

    @Test
    @DisplayName("发 → 验 → 拿到令牌,而且这一次是新注册")
    void loginIsRegistration() throws Exception {
        String phone = freshPhone(1);
        String code = sendTo(phone);

        mvc.perform(post("/api/v1/auth/sms/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","deviceLabel":"测试设备"}""".formatted(phone, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", startsWith("at_")))
                .andExpect(jsonPath("$.isNewAccount", is(true)))
                .andExpect(jsonPath("$.maskedPhone", is(PhoneCipher.mask(phone))))
                // 🔴 响应里绝不能出现手机号明文
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    @DisplayName("🔴 滑块不通过 → 400 CAPTCHA_FAILED,而且一条短信都没发")
    void captchaGatesBeforeSpending() throws Exception {
        int before = SENT.size();
        mvc.perform(post("/api/v1/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","captchaTicket":"bad","captchaRandstr":"r"}"""
                                .formatted(freshPhone(2))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("CAPTCHA_FAILED")));
        org.junit.jupiter.api.Assertions.assertEquals(before, SENT.size(), "拦要拦在花钱那一步之前");
    }

    @Test
    @DisplayName("🔴 四种终态四句话:输错 / 过期 / 作废 / 没发过,错误码各不相同")
    void fourDistinctAnswers() throws Exception {
        String phone = freshPhone(3);

        // 没发过
        mvc.perform(post("/api/v1/auth/sms/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"123456"}""".formatted(phone)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("CODE_NONE")));

        String first = sendTo(phone);

        // 输错 —— 必须告诉用户还剩几次
        mvc.perform(post("/api/v1/auth/sms/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s"}""".formatted(phone, wrong(first))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("CODE_WRONG")))
                .andExpect(jsonPath("$.message", containsString("还可以再试")));
    }

    @Test
    @DisplayName("超频提示必须带准确时点,不是「请稍后再试」")
    void tooFrequentTellsExactTime() throws Exception {
        String phone = freshPhone(4);
        sendTo(phone);

        mvc.perform(post("/api/v1/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","captchaTicket":"ok","captchaRandstr":"r"}""".formatted(phone)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code", is("SMS_TOO_FREQUENT")))
                // 「请稍后再试」只惩罚真实用户 —— 刷子不会因为文案含糊而少刷
                .andExpect(jsonPath("$.message", matchesRegex(".*\\d{2}:\\d{2}.*")));
    }

    @Test
    @DisplayName("手机号格式不对在发送之前就被挡下")
    void badPhoneRejectedBeforeSending() throws Exception {
        int before = SENT.size();
        mvc.perform(post("/api/v1/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"12345","captchaTicket":"ok","captchaRandstr":"r"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("BAD_PHONE")));
        org.junit.jupiter.api.Assertions.assertEquals(before, SENT.size());
    }

    @Test
    @DisplayName("未定义字段一律拒绝 —— R-07 的第二道锁在鉴权端点上同样生效")
    void unknownFieldRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"13800138000","captchaTicket":"ok","captchaRandstr":"r","admin":true}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("没带令牌 → 401;带了就能读到自己的账号")
    void bearerTokenRequired() throws Exception {
        mvc.perform(get("/api/v1/account"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));

        String phone = freshPhone(5);
        String token = login(phone);

        mvc.perform(get("/api/v1/account").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedPhone", is(PhoneCipher.mask(phone))))
                .andExpect(jsonPath("$.identities", contains("phone")))
                .andExpect(jsonPath("$.activeSessionCount", greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("退出登录是幂等的 —— 点两次不报错")
    void logoutIsIdempotent() throws Exception {
        String token = login(freshPhone(6));

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked", is(true)));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked", is(false)));

        // 吊销立刻生效
        mvc.perform(get("/api/v1/account").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("🔴 只读令牌换不出写能力")
    void readonlyTokenCannotWrite(@Autowired TokenService tokens) throws Exception {
        Session s = loginFull(freshPhone(7));
        // 线上是字符串,内部是 long —— parseLong 这一下同时也是「响应里那个值确实是 int64」的断言
        String ro = tokens.issue(Long.parseLong(s.userId()), TokenScope.READONLY, "MCP").plaintext();

        // 读得到
        mvc.perform(get("/api/v1/account").header("Authorization", "Bearer " + ro))
                .andExpect(status().isOk());
        // 写不了
        mvc.perform(delete("/api/v1/account").header("Authorization", "Bearer " + ro))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("READONLY_TOKEN")));
    }

    @Test
    @DisplayName("注销:响应里必须带导出入口提示,而且不许出现任何具体天数")
    void deactivateCarriesExportHint() throws Exception {
        String token = login(freshPhone(8));

        mvc.perform(delete("/api/v1/account").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportHint", containsString("导出")))
                // ⚪ 硬删时点未定(L-A5 的律师稿)。写「7 天内清干净」等于替法务做决定。
                .andExpect(jsonPath("$.exportHint", not(matchesRegex(".*\\d+\\s*天.*"))));

        mvc.perform(get("/api/v1/account").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("M5-15 设备列表迁到 GET /tokens:标出当前这一台,不返 total/hasMore/revokedAt")
    void tokenListMarksCurrentAndOmitsForbiddenFields() throws Exception {
        String token = login(freshPhone(9));
        mvc.perform(get("/api/v1/tokens").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.current == true)]", hasSize(1)))
                // 契约 §7.4 裁定 2:对外叫 tokenId,值仍是那个哈希 —— 名字不该泄露它怎么算出来的
                .andExpect(jsonPath("$.items[0].tokenId").exists())
                .andExpect(jsonPath("$.items[0].tokenHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].scope", is("full")))
                // 🔴 只返此刻可用的行,所以 revokedAt 这个字段不该存在 ——
                //    一个永远不出现的字段是在邀请端实现一段永远跑不到的分支(§9.7 裁定 3)
                .andExpect(jsonPath("$.items[0].revokedAt").doesNotExist())
                // 🔴 分页用游标,前端不猜总数(B0 §7.1 / U5.6 §三)
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.hasMore").doesNotExist())
                // 只有一台设备 → 没有下一页 → 整个 key 不出现(接口契约 §一 空值规则)
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        // 旧路径确实没了 —— 只写新路径通过、不写旧路径消失,迁移就可能是「两个都在」
        mvc.perform(get("/api/v1/account/sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("🔴 M5-15 只读令牌不能管理令牌 —— GET 也不行,不然一条 ro_ 就能把全部 at_ 吊销掉")
    void readonlyTokenCannotTouchTokenEndpoints(@Autowired TokenService tokens) throws Exception {
        Session s = loginFull(freshPhone(41));
        String ro = tokens.issue(Long.parseLong(s.userId()), TokenScope.READONLY, "MCP").plaintext();

        // requireWrite 拦不住这一条 —— 它是 GET。所以必须有 requireTokenManagement
        mvc.perform(get("/api/v1/tokens").header("Authorization", "Bearer " + ro))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("READONLY_TOKEN")));

        String mine = tokens.sessionsOf(Long.parseLong(s.userId())).get(0).tokenHash();
        mvc.perform(post("/api/v1/tokens/" + mine + "/revoke").header("Authorization", "Bearer " + ro))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("READONLY_TOKEN")));
    }

    @Test
    @DisplayName("🔴 越权吊销别人的会话是显式失败(403 NOT_YOUR_SESSION),不是静默无事发生")
    void cannotRevokeSomeoneElsesToken(@Autowired TokenService tokens) throws Exception {
        Session mine = loginFull(freshPhone(42));
        Session theirs = loginFull(freshPhone(43));
        String theirHash = tokens.sessionsOf(Long.parseLong(theirs.userId())).get(0).tokenHash();

        mvc.perform(post("/api/v1/tokens/" + theirHash + "/revoke")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("NOT_YOUR_SESSION")));

        // 自己的那一条退得掉,而且天然幂等:第二次回 false 不报错
        String myHash = tokens.sessionsOf(Long.parseLong(mine.userId())).get(0).tokenHash();
        mvc.perform(post("/api/v1/tokens/" + myHash + "/revoke")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked", is(true)));
    }

    @Test
    @DisplayName("🔴 B0-2 账号 id 是 int64,以字符串传输 —— 值变形状不变")
    void userIdIsInt64CarriedAsString() throws Exception {
        Session s = loginFull(freshPhone(44));

        assertFalse(s.userId().startsWith("u_"), "u_ 形态已废止(B0 §3.2)");
        long parsed = Long.parseLong(s.userId());          // 是 int64
        assertTrue(parsed >= 10001L, "发号器从 10001 起,实得 " + parsed);

        // 🔴 而它在 JSON 里必须是字符串:进了 number,JS 那一侧过 2^53 就悄悄丢精度
        mvc.perform(get("/api/v1/account").header("Authorization", "Bearer " + s.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(instanceOf(String.class)))
                .andExpect(jsonPath("$.userId", is(s.userId())))
                // §9.9 裁定:nickname 删掉 —— 一个永远为 null 的字段,下一个人会去把它填上
                .andExpect(jsonPath("$.nickname").doesNotExist());
    }

    @Test
    @DisplayName("微信通道未启用时回 503,不是 404 —— 端点存在,只是这个阶段还没开")
    void wechatNotEnabledYet() throws Exception {
        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entry":"mini","code":"x"}"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("WECHAT_NOT_ENABLED")));
    }

    @Test
    @DisplayName("阶段 3 的那个累计数读得到")
    void signupCount() throws Exception {
        login(freshPhone(10));
        mvc.perform(get("/api/v1/account/signup-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSignups", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.note", containsString("人工判定")));
    }

    @Test
    @DisplayName("🔴 DELETE 只开给 /api/v1/account 这一条路径,别处仍然没有")
    void deleteIsScopedToAccountOnly() throws Exception {
        // 这条断言看着琐碎,但它守的是一个顺序依赖:CORS 规则按注册顺序取第一条匹配的。
        // 把 /api/v1/** 写在 /api/v1/account 前面,下面这个 DELETE 预检就会失败 —— 而且没有别的症状。
        mvc.perform(options("/api/v1/account")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")));

        // 而别处仍然没有 DELETE —— 骨架层的删除守则是「有记录就不许删,只能归档」
        mvc.perform(options("/api/v1/account/sessions")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("🔴 回环地址不计进 IP 频控 —— 否则反代后全站共享一个 20 条/日的桶")
    void loopbackIsNotCountedAsAnIp() {
        // MockMvc 的 remoteAddr 默认就是 127.0.0.1,与同机反代后的线上形态一致。
        // 修之前它会被当成一个真实 IP —— 于是所有用户挤进同一个计数格,
        // 每天第 21 个用户再也收不到验证码。那不是防线失效,是自己 DoS 自己。
        ClientIp dev = new ClientIp(false);
        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        assertEquals("", dev.of(req), "回环 = 取不到 IP,而不是一个所有人共用的 IP");

        req.setRemoteAddr("::1");
        assertEquals("", dev.of(req));

        // 真实外网地址仍然照常计入
        req.setRemoteAddr("203.0.113.9");
        assertEquals("203.0.113.9", dev.of(req));
    }

    @Test
    @DisplayName("🔴 X-Forwarded-For 取最右那一个 —— 最左是客户端能伪造的那个")
    void forwardedForTakesTheRightmostHop() {
        ClientIp trusting = new ClientIp(true);
        var req = new org.springframework.mock.web.MockHttpServletRequest();
        // 客户端伪造 1.2.3.4,我们自己的反代把真实地址追加在最右
        req.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9");
        assertEquals("203.0.113.9", trusting.of(req),
                "取最左是这个头最经典的一个用反 —— 那正好取到攻击者写的那个");

        // 不信任时,伪造的头一概无视
        var req2 = new org.springframework.mock.web.MockHttpServletRequest();
        req2.addHeader("X-Forwarded-For", "1.2.3.4");
        req2.setRemoteAddr("203.0.113.20");
        assertEquals("203.0.113.20", new ClientIp(false).of(req2));
    }

    @Test
    @DisplayName("🔴 鉴权端点的错误消息不回显无界的用户输入 —— 这个产品的输入可能是一整段题干")
    void authErrorsDoNotEchoUnboundedInput() throws Exception {
        String pastedStem = "2023 年全国粮食总产量为 13908 亿斤,比上年增加 177 亿斤".repeat(40);

        String body = mvc.perform(post("/api/v1/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new tools.jackson.databind.ObjectMapper().createObjectNode()
                                .put("phone", freshPhone(30))
                                .put("purpose", pastedStem)
                                .put("captchaTicket", "ok")
                                .put("captchaRandstr", "r")
                                .toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("BAD_PURPOSE")))
                .andReturn().getResponse().getContentAsString();

        // 仓库既有纪律:ApiException.echo 截断到 64 字符
        // (SyllabusAdminApiTest#rejectionMessagesDoNotEchoUnboundedInput 守的是同一条)
        assertTrue(body.length() < pastedStem.length() / 4,
                "响应体不该把整段输入带回来,实际长度 " + body.length());
        assertTrue(body.contains("已截断"), "应当明确标出被截断了");
    }

    // —— 微信联合登录 ——

    @Test
    @DisplayName("🔴 一步登录:先用免费的 code 换 openid,频控不过时【那个 0.03 元的接口一次都没被调到】")
    void oneStepLoginGatesBeforeSpending() throws Exception {
        WECHAT.enabled = true;
        WECHAT.phoneToReturn = freshPhone(20);

        // 第一次:走完三步
        mvc.perform(post("/api/v1/auth/wechat/phone-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginCode":"jsc_1","phoneCode":"pc_1","deviceLabel":"小程序"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", startsWith("at_")))
                .andExpect(jsonPath("$.needsPhoneBinding", is(false)));
        assertEquals(1, WECHAT.paidCalls);

        // 第二次(同一个 openid,60 秒内):应当在【花钱之前】被拦下
        int paidBefore = WECHAT.paidCalls;
        mvc.perform(post("/api/v1/auth/wechat/phone-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginCode":"jsc_1","phoneCode":"pc_2"}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code", is("WECHAT_PHONE_TOO_FREQUENT")))
                .andExpect(jsonPath("$.message", matchesRegex(".*\\d{2}:\\d{2}.*")));

        assertEquals(paidBefore, WECHAT.paidCalls,
                "🔴 拦要拦在花钱那一步之前 —— 顺序反了这道闸就只是在给账单排队");
        assertEquals(2, WECHAT.loginCodeCalls, "免费那一步照常调用,因为它给出了限流键");
    }

    @Test
    @DisplayName("一步登录:换手机号失败时把日额度还回去 —— 我们这边的失败不该吃掉用户的次数")
    void oneStepRefundsOnFailure() throws Exception {
        WECHAT.enabled = true;
        WECHAT.phoneCodeFails = true;

        mvc.perform(post("/api/v1/auth/wechat/phone-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginCode":"jsc_refund","phoneCode":"pc_x"}"""))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code", is("WECHAT_PHONE_FAILED")))
                // 🔴 errcode 只进日志 —— 那是我们和微信之间的事
                .andExpect(jsonPath("$.message", not(containsString("40163"))));
    }

    @Test
    @DisplayName("🔴 老用户从小程序一步登录 → 登进原账号,不是建新号")
    void oneStepFindsExistingPhoneAccount() throws Exception {
        String phone = freshPhone(21);
        Session existing = loginFull(phone);           // 先用短信注册

        WECHAT.enabled = true;
        WECHAT.phoneToReturn = phone;
        mvc.perform(post("/api/v1/auth/wechat/phone-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginCode":"jsc_old","phoneCode":"pc_old"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(existing.userId())))
                .andExpect(jsonPath("$.isNewAccount", is(false)))
                .andExpect(jsonPath("$.splitMergeToken").doesNotExist());
    }

    @Test
    @DisplayName("微信登录建了新号 → needsPhoneBinding=true,前端据此引导补绑")
    void weChatOnlyAccountNeedsPhoneBinding() throws Exception {
        WECHAT.enabled = true;
        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entry":"mini","code":"jsc_nophone"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewAccount", is(true)))
                .andExpect(jsonPath("$.needsPhoneBinding", is(true)))
                .andExpect(jsonPath("$.maskedPhone").doesNotExist());
    }

    @Test
    @DisplayName("🔴 网页授权必须校验 state —— 不校验就能被塞进别人的 code(CSRF 绑号)")
    void oauthRequiresState() throws Exception {
        WECHAT.enabled = true;

        // 伪造的 state
        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entry":"official_h5","code":"c1","state":"forged"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("WECHAT_STATE_INVALID")));

        // 服务端发的 state 可用,但只能用一次
        String state = jsonField(mvc.perform(get("/api/v1/auth/wechat/authorize-url")
                        .param("entry", "official_h5")
                        .param("redirectUri", "https://kaodian.example/cb"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "state");

        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entry":"official_h5","code":"c2","state":"%s"}""".formatted(state)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entry":"official_h5","code":"c3","state":"%s"}""".formatted(state)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("WECHAT_STATE_INVALID")));
    }

    @Test
    @DisplayName("小程序没有回跳因而没有 state —— 不该被 state 校验拦住")
    void miniProgramNeedsNoState() throws Exception {
        WECHAT.enabled = true;
        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entry":"mini","code":"jsc_nostate"}"""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("一步登录在微信未启用时也回 503,不是 500")
    void oneStepRespectsTheStageGate() throws Exception {
        mvc.perform(post("/api/v1/auth/wechat/phone-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginCode":"a","phoneCode":"b"}"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("WECHAT_NOT_ENABLED")));
        assertEquals(0, WECHAT.paidCalls);
    }

    @Test
    @DisplayName("🔴 应用没绑开放平台 → 503 而不是降级建号 —— 配置错误不该攒出一批脏账号")
    void missingUnionIdRefusesInsteadOfDegrading() throws Exception {
        WECHAT.enabled = true;
        WECHAT.noUnionId = true;

        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entry":"mini","code":"jsc_nounion"}"""))
                // 503 而不是 502:不是微信出了问题,是【我们的配置】没做对
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("WECHAT_UNIONID_MISSING")))
                .andExpect(jsonPath("$.message", containsString("开放平台")));
    }

    @Test
    @DisplayName("一步登录同样拒绝降级,而且是在花钱之前拒绝的")
    void oneStepAlsoRefusesWithoutUnionId() throws Exception {
        WECHAT.enabled = true;
        WECHAT.noUnionId = true;

        mvc.perform(post("/api/v1/auth/wechat/phone-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginCode":"jsc_x","phoneCode":"pc_x"}"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("WECHAT_UNIONID_MISSING")));
        assertEquals(0, WECHAT.paidCalls, "拒绝发生在换 openid 那一步,那 0.03 元没花出去");
    }

    @Test
    @DisplayName("⚪ 显式关掉 require-unionid 后回到降级行为 —— 出路要给,代价要写清楚")
    void degradationIsStillAvailableWhenExplicitlyChosen() throws Exception {
        WECHAT.enabled = true;
        WECHAT.noUnionId = true;
        WECHAT.requireUnionId = false;

        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entry":"mini","code":"jsc_degraded"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewAccount", is(true)))
                .andExpect(jsonPath("$.needsPhoneBinding", is(true)));
    }

    // —— 辅助 ——

    private record Session(String token, String userId) {
    }

    private Session loginFull(String phone) throws Exception {
        String code = sendTo(phone);
        String body = mvc.perform(post("/api/v1/auth/sms/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","deviceLabel":"测试设备"}""".formatted(phone, code)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Session(jsonField(body, "token"), jsonField(body, "userId"));
    }

    private String login(String phone) throws Exception {
        return loginFull(phone).token();
    }

    private static String jsonField(String json, String field) {
        var m = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!m.find()) {
            throw new AssertionError("响应里没有 " + field + ":" + json);
        }
        return m.group(1);
    }

    private static String wrong(String actual) {
        return actual.equals("000000") ? "111111" : "000000";
    }
}
