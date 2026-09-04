package com.kaodian.server.api;

import com.kaodian.server.auth.TokenScope;
import com.kaodian.server.auth.TokenService;
import com.kaodian.server.billing.QuotaService;
import com.kaodian.server.billing.QuotaStore;
import com.kaodian.server.billing.QuotaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 🔴 <b>{@code R-37} 的防线:关卡数字与额度物理隔离</b>({@code M7-额度与订单} §2.8 判据 ②)。
 *
 * <h2>要证的那一句</h2>
 *
 * <b>两类余量都为 0 时,关卡的三个动作一步不少。</b>
 *
 * <table>
 *   <caption>关卡要的那三个数,一个都不经过额度</caption>
 *   <tr><th>关卡要的那个数</th><th>经不经过额度</th></tr>
 *   <tr><td>「今天记了几条」</td><td><b>不经过</b> —— 记录动作不调外部模型</td></tr>
 *   <tr><td>「主动查看盲区的人数」</td><td><b>不经过</b> —— 差集是一次计算</td></tr>
 *   <tr><td>「点进去看的比例」</td><td><b>不经过</b> —— 盲区诊断永不上锁</td></tr>
 * </table>
 *
 * <p>这是 {@code U7.2} §四 那条「走『记一条 → 看盲区 → 导出』三步全部走通」的<b>服务端一半</b>。
 * 界面那一半(过程中没有任何一屏出现付费入口)不在后端,本文不认领。
 *
 * <p>⚠ 判据 ①(结构层,{@code grep 'billing|[Qq]uota' domain} 期望 0)不在这里 ——
 * 它在 {@code BillingStructureTest#商业化不进另外三个模块},而且那一条今天<b>还红着一半</b>:
 * {@code TouchKind.consumesAiQuota()} 是 {@code domain} 里的一个商业化命名,
 * 改名归 {@code M1}(KUBI-99),本模块不跨手去改(§11.3)。
 * 本类扫的是「有没有引到商业化<u>类型</u>」,那一条今天是绿的。
 *
 * <h2>⚠️ 这个类<b>不能</b> {@code @Import(ApiTestAuth.class)}</h2>
 *
 * 那份配置里的 {@code tokenService} 与 {@code AuthBeans} 的同名 bean 撞车,整个上下文起不来
 * ({@code BeanDefinitionOverrideException})—— 它是给 {@code @WebMvcTest} <b>切片</b>用的,
 * 切片里没有 {@code AuthBeans}。全上下文这一侧自己从真的 {@code TokenService} 签一条令牌。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 不要碰真实的 ~/.kaodian —— 这个上下文会起 FileTouchStore 与 OrphanGuard。
        // 🔴 目录带 ${random.uuid}:三个 store 都是【落盘】的,固定目录会让上一次跑剩下的
        //    billing-quota.json 成为这一次的初始状态 —— 实测就是这么被「granted=30」卡住的
        //    (前一次跑的时候免费档还是 30/5,而 grant 只升不降,新的 0 覆盖不掉旧的 30)。
        "kaodian.data.dir=${java.io.tmpdir}/kaodian-quota-gate-${random.uuid}",
        "kaodian.agent.storage.root=${java.io.tmpdir}/kaodian-quota-gate-${random.uuid}/agent",
        // 🔴 把免费档本身调成 0/0,这条用例的前提才是真的。
        //    只在 @BeforeEach 里调 grant(…, 0) 不够:grant 只升不降(§2.3),而首次访问会按
        //    免费档懒发放 30/5 —— 「余量为 0」这个前提在第二条用例里就没了。
        //    实测:漏掉它时前置断言判红(expected: <0> but was: <30>)。
        //
        // ⚠️ 整个 plans 列表都要在这里重写一遍,不能只覆盖那两个 quota 键:
        //    Spring Boot 绑定集合时【不跨 property source 合并】—— 优先级最高的那一份整个赢。
        //    只写两行的话,plans 会变成一个 code 为 null 的单元素列表(实测 NPE)。
        "kaodian.billing.default-plan=free",
        "kaodian.billing.plans[0].code=free",
        "kaodian.billing.plans[0].name=free",
        "kaodian.billing.plans[0].price-fen=0",
        "kaodian.billing.plans[0].purchasable=false",
        "kaodian.billing.plans[0].quota.ai-capture=0",
        "kaodian.billing.plans[0].quota.ai-ask=0"
})
class QuotaGateIsolationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    QuotaStore quotas;

    @Autowired
    QuotaService quotaService;

    @Autowired
    TokenService tokens;

    private String periodYm;
    private String bearer;
    private long userId;

    @BeforeEach
    void 把两个池子都清空() {
        // 从真的 TokenService 签一条 —— 请求照样经过 ApiAuthFilter 的全部判断,只是它会通过。
        userId = 10001L;
        bearer = "Bearer " + tokens.issue(userId, TokenScope.FULL, "额度隔离判据").plaintext();

        periodYm = quotaService.currentPeriod();
        quotaService.provision(userId, periodYm);   // 按有效档位懒发放 —— 这个上下文里它是 0/0

        assertEquals(0, quotas.find(userId, periodYm, QuotaType.AI_CAPTURE)
                .orElseThrow().remaining(), "前置没成立:这条用例要的是余量真的为 0");
        assertEquals(0, quotas.find(userId, periodYm, QuotaType.AI_ASK)
                .orElseThrow().remaining(), "前置没成立:这条用例要的是余量真的为 0");
    }

    @Test
    @DisplayName("🔴 额度为零时「记一条 → 看盲区 → 导出」三步全通")
    void 额度为零时记录看盲区导出三步全通() throws Exception {
        // ① 记一条 —— 记录动作不经额度(`接口契约` §6.7.2 约束 4:额度耗尽时记录照样落库)
        mvc.perform(post("/api/v1/records")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualTouch()))
                .andExpect(status().isCreated());

        // ② 看盲区 —— 差集是一次计算,盲区诊断永不上锁
        mvc.perform(get("/api/v1/coverage/summary")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());

        // ③ 导出 —— 「你的东西你随时能拿走」不因为没额度而变
        mvc.perform(get("/api/v1/export").param("format", "json")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("额度为零时 GET /quota 与预检照常回答,只是余量是 0")
    void 额度为零时额度端点自己照常回答() throws Exception {
        mvc.perform(get("/api/v1/quota")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotas.ai_capture.remaining", org.hamcrest.Matchers.is(0)))
                .andExpect(jsonPath("$.quotas.ai_ask.remaining", org.hamcrest.Matchers.is(0)));

        mvc.perform(post("/api/v1/quota/precheck")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quotaType\":\"ai_capture\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", org.hamcrest.Matchers.is("QUOTA_EXHAUSTED")))
                // 🔴 免费兜底动作的标识必须给出来 —— 手动那条路一直开着
                .andExpect(jsonPath("$.details.manualEntry", org.hamcrest.Matchers.is("manual_tag")));
    }

    /** 一条纯手动记录:不调外部模型,所以它这条路上一格额度都不该用到。 */
    private static String manualTouch() {
        return """
                {"kind":"MANUAL","sourceName":"\u81EA\u5DF1\u770B\u4E86\u4E00\u904D",\
                 "nodeCode":"average-calc"}""";
    }
}
