package com.kaodian.server.api;

import com.kaodian.server.api.billing.BillingCatalogController;
import com.kaodian.server.api.billing.BillingExceptionHandler;
import com.kaodian.server.api.billing.OrderController;
import com.kaodian.server.api.billing.QuotaController;
import com.kaodian.server.api.billing.WxPayNotifyController;
import com.kaodian.server.api.support.IdempotencyGuard;
import com.kaodian.server.billing.BillingProperties;
import com.kaodian.server.billing.Channel;
import com.kaodian.server.billing.FilePaymentOrderStore;
import com.kaodian.server.billing.FileQuotaStore;
import com.kaodian.server.billing.FileSubscriptionStore;
import com.kaodian.server.billing.GrantState;
import com.kaodian.server.billing.OrderService;
import com.kaodian.server.billing.OrderState;
import com.kaodian.server.billing.PaymentGateway;
import com.kaodian.server.billing.PaymentOrder;
import com.kaodian.server.billing.PaymentOrderStore;
import com.kaodian.server.billing.PaymentSettleService;
import com.kaodian.server.billing.QuotaService;
import com.kaodian.server.billing.QuotaStore;
import com.kaodian.server.billing.QuotaType;
import com.kaodian.server.billing.SubscriptionStore;
import com.kaodian.server.billing.UpstreamState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商业化十一个端点的接口层判据({@code M7-额度与订单} §4.6 / §7.2 / §8.2 / §8.3 / §10.1)。
 *
 * <p>「无令牌 → 401」那一条<b>不在这里</b>:{@link ApiTestAuth} 给这个切片的每一个请求都装了
 * 默认令牌,所以本类<b>写不出</b>不带令牌那一条。它在 {@code BillingAuthChainTest} ——
 * 那个类不装这份配置,于是它看到的是没有令牌的世界。
 */
@WebMvcTest(controllers = {BillingCatalogController.class, OrderController.class,
        QuotaController.class, WxPayNotifyController.class},
        // 🔴 只覆盖「这一端开了哪些通道」这一行:档位、时区、过期分钟数一律走
        //    application.properties 的真值。夹具里再抄一份 plans,就是定价的第二真源,
        //    而这些用例断言的 30/5/300/50/990 正是要证明那一份配置真的被读进来了。
        //    apple_iap 故意不开 —— CHANNEL_UNAVAILABLE 那条用例靠它。
        properties = "kaodian.billing.channels=wx_jsapi")
@Import({ApiTestAuth.class, BillingExceptionHandler.class, BillingApiTest.Fixtures.class})
// 🔴 三个 store 都是单例,而它们装着账本与订单 —— 上下文复用就是「上一条用例买过的东西
//    在下一条用例里还在」。实测漏掉这一行时 quotaShape 读到的是 300/50(上一条用例升过档),
//    subscriptionOmitsEmptyPendingOrders 读到一笔别人下的单。每条用例一份干净的世界。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class BillingApiTest {

    private static final long USER = ApiTestAuth.USER_ID;
    private static final Instant NOW = Instant.parse("2026-09-04T02:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentOrderStore orders;

    @Autowired
    QuotaStore quotas;

    @Autowired
    QuotaService quotaService;

    private String periodYm;

    @BeforeEach
    void setUp() {
        periodYm = quotaService.currentPeriod();
    }

    // ——————————————————— §8.4 档位 / 通道 / 订阅 ———————————————————

    @Test
    @DisplayName("档位列表:autoRenew 恒 false,quota 是键为闭集两值的对象")
    void plansShape() throws Exception {
        mvc.perform(get("/api/v1/billing/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoRenew", is(false)))
                .andExpect(jsonPath("$.plans[0].code", is("free")))
                .andExpect(jsonPath("$.plans[0].quota.ai_capture", is(30)))
                .andExpect(jsonPath("$.plans[0].quota.ai_ask", is(5)))
                .andExpect(jsonPath("$.plans[0].purchasable", is(false)))
                // 🔴 配置里没写 billingPeriod / badge → 整个 key 不出现,不是 null
                .andExpect(jsonPath("$.plans[0].billingPeriod").doesNotExist())
                .andExpect(jsonPath("$.plans[0].badge").doesNotExist())
                .andExpect(jsonPath("$.plans[1].billingPeriod", is("month")))
                .andExpect(jsonPath("$.plans[1].badge", is("推荐")))
                // 🔴 任何「别端价格」「划线价」字段都不存在
                .andExpect(jsonPath("$.plans[1].originalPriceFen").doesNotExist());
    }

    @Test
    @DisplayName("通道列表:三个取值之内,不含支付宝")
    void channelsShape() throws Exception {
        mvc.perform(get("/api/v1/billing/channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[0].code", is("wx_jsapi")))
                .andExpect(jsonPath("$.channels[0].name", is("微信支付")))
                // 🔴 这个端点不返回任何其它能力位 —— 那会长成可下发端矩阵
                .andExpect(jsonPath("$.features").doesNotExist());
    }

    @Test
    @DisplayName("🔴 一笔待支付都没有时 pendingOrders 整个 key 不出现,不是空数组")
    void subscriptionOmitsEmptyPendingOrders() throws Exception {
        mvc.perform(get("/api/v1/billing/subscription"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode", is("free")))
                .andExpect(jsonPath("$.autoRenew", is(false)))
                // 免费档:expiresAt 整个 key 不出现,不返回「永久有效」这类字符串
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.pendingOrders").doesNotExist());
    }

    @Test
    @DisplayName("有待支付的单时 pendingOrders 出现,带四个字段")
    void subscriptionListsPendingOrders() throws Exception {
        createOrder();
        mvc.perform(get("/api/v1/billing/subscription"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingOrders[0].state", is("PENDING")))
                .andExpect(jsonPath("$.pendingOrders[0].planCode", is("plus")));
    }

    // ——————————————————— §8.2 订单 ———————————————————

    @Test
    @DisplayName("下单:服务端定价、服务端出订单号,expireAt 与下发给平台的是同一个值")
    void createOrder_pricedByServer() throws Exception {
        mvc.perform(post("/api/v1/billing/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"plus\",\"channel\":\"wx_jsapi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountFen", is(990)))
                .andExpect(jsonPath("$.productName", is("记多点")))
                .andExpect(jsonPath("$.state", is("PENDING")))
                .andExpect(jsonPath("$.payParams.prepayId").exists())
                .andExpect(jsonPath("$.expireAt").exists());
    }

    @Test
    @DisplayName("🔴 body 送金额当场 400 —— 请求体不接受未定义字段")
    void createOrder_rejectsClientSuppliedAmount() throws Exception {
        mvc.perform(post("/api/v1/billing/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"plus\",\"channel\":\"wx_jsapi\",\"amountFen\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("UNKNOWN_FIELD")));
    }

    @Test
    @DisplayName("🔴 body 送订单号当场 400 —— 幂等的锚点不交给端")
    void createOrder_rejectsClientSuppliedOutTradeNo() throws Exception {
        mvc.perform(post("/api/v1/billing/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"plus\",\"channel\":\"wx_jsapi\",\"outTradeNo\":\"KD-mine\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("UNKNOWN_FIELD")));
    }

    @Test
    @DisplayName("没带 Idempotency-Key → 400 IDEMPOTENCY_KEY_REQUIRED")
    void createOrder_requiresIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/billing/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"plus\",\"channel\":\"wx_jsapi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REQUIRED")));
    }

    @Test
    @DisplayName("同一个 Idempotency-Key 重放 → 返回上次结果,不产生第二笔")
    void createOrder_replaysOnSameKey() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = createOrderWithKey(key);
        String second = createOrderWithKey(key);
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
        org.junit.jupiter.api.Assertions.assertEquals(1, orders.findOpenByUser(USER).size());
    }

    @Test
    @DisplayName("档位不可购 → 422 PLAN_NOT_PURCHASABLE")
    void createOrder_planNotPurchasable() throws Exception {
        mvc.perform(post("/api/v1/billing/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"free\",\"channel\":\"wx_jsapi\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("PLAN_NOT_PURCHASABLE")));
    }

    @Test
    @DisplayName("通道在这一端不可用 → 422 CHANNEL_UNAVAILABLE")
    void createOrder_channelUnavailable() throws Exception {
        mvc.perform(post("/api/v1/billing/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"plus\",\"channel\":\"apple_iap\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("CHANNEL_UNAVAILABLE")));
    }

    @Test
    @DisplayName("🚫 支付宝不是一个取值 → 400,不是「暂不支持」")
    void createOrder_rejectsAlipay() throws Exception {
        mvc.perform(post("/api/v1/billing/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"plus\",\"channel\":\"alipay\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("🔴 列表只有四类字段,没有 total / hasMore / planCode / outTradeNo")
    void orderList_hasFourFieldsOnly() throws Exception {
        createOrder();
        mvc.perform(get("/api/v1/billing/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName", is("记多点")))
                .andExpect(jsonPath("$.items[0].amountFen", is(990)))
                .andExpect(jsonPath("$.items[0].state", is("PENDING")))
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[0].planCode").doesNotExist())
                .andExpect(jsonPath("$.items[0].outTradeNo").doesNotExist())
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.hasMore").doesNotExist())
                // 没有下一页时整个 key 不出现
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("limit 超界 → 400 INVALID_LIMIT;游标解不开 → 400 INVALID_CURSOR 且不回显原值")
    void orderList_boundedPaging() throws Exception {
        mvc.perform(get("/api/v1/billing/orders").param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_LIMIT")));

        String huge = "x".repeat(500);
        mvc.perform(get("/api/v1/billing/orders").param("cursor", huge))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_CURSOR")))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(huge))));
    }

    @Test
    @DisplayName("详情比列表多三项;非 CONFIRMING 时 grantState 整个 key 不出现")
    void orderDetail_omitsGrantStateOutsideConfirming() throws Exception {
        String no = createOrder();
        mvc.perform(get("/api/v1/billing/orders/" + no))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outTradeNo", is(no)))
                .andExpect(jsonPath("$.state", is("PENDING")))
                .andExpect(jsonPath("$.grantState").doesNotExist())
                .andExpect(jsonPath("$.paidAt").doesNotExist());
    }

    @Test
    @DisplayName("CONFIRMING 时 grantState 三值之一必须出现")
    void orderDetail_showsGrantStateWhenConfirming() throws Exception {
        String no = givenOrderIn(OrderState.CONFIRMING, GrantState.IN_PROGRESS);
        mvc.perform(get("/api/v1/billing/orders/" + no))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("CONFIRMING")))
                .andExpect(jsonPath("$.grantState", is("IN_PROGRESS")));
    }

    @Test
    @DisplayName("🔴 别人的订单 → 404 不是 403(订单号可枚举,§4.4)")
    void orderDetail_foreignOrderIs404() throws Exception {
        PaymentOrder foreign = new PaymentOrder("KD20260904000000009999", ApiTestAuth.OTHER_USER_ID,
                "plus", "记多点", 990, Channel.WX_JSAPI, OrderState.PENDING, null, null,
                NOW, NOW.plusSeconds(7200), null, null);
        orders.save(foreign);

        mvc.perform(get("/api/v1/billing/orders/" + foreign.outTradeNo()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ORDER_NOT_FOUND")));
    }

    // ——————————————————— §4.4 关单 ———————————————————

    @Test
    @DisplayName("🔴 §4.6 判据 ④ —— 确认中的订单关不掉")
    void 确认中的订单关不掉() throws Exception {
        String no = givenOrderIn(OrderState.CONFIRMING, GrantState.NOT_STARTED);
        mvc.perform(post("/api/v1/billing/orders/" + no + "/close")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ORDER_NOT_CLOSEABLE")));
    }

    @Test
    @DisplayName("已支付的订单关不掉 → 409 ORDER_ALREADY_PAID(与上一条是两句话两个动作)")
    void closePaidOrder() throws Exception {
        String no = givenOrderIn(OrderState.PAID, null);
        mvc.perform(post("/api/v1/billing/orders/" + no + "/close")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ORDER_ALREADY_PAID")));
    }

    @Test
    @DisplayName("关单幂等:待支付关掉是 200,已经关掉的再关一次还是 200")
    void closeIsIdempotent() throws Exception {
        String no = createOrder();
        mvc.perform(post("/api/v1/billing/orders/" + no + "/close")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("CLOSED")));

        mvc.perform(post("/api/v1/billing/orders/" + no + "/close")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("CLOSED")));
    }

    // ——————————————————— §4.5 收据校验 ———————————————————

    @Test
    @DisplayName("🔴 收据无效 → 422 RECEIPT_INVALID,订单不动,而且不说「失败」")
    void invalidReceiptIs422() throws Exception {
        String no = createOrder();
        mvc.perform(post("/api/v1/billing/orders/" + no + "/receipt/verify")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipt\":\"这不是一张收据\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("RECEIPT_INVALID")));

        org.junit.jupiter.api.Assertions.assertEquals(OrderState.PENDING,
                orders.findByOutTradeNo(no).orElseThrow().state());
    }

    @Test
    @DisplayName("🔴 网络/上游错误 → 502 SERVER_ERROR,与 422 靠状态分类就分得开")
    void upstreamFailureIs502() throws Exception {
        String no = createOrder();
        mvc.perform(post("/api/v1/billing/orders/" + no + "/receipt/verify")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipt\":\"" + FakeGateway.BOOM + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code", is("SERVER_ERROR")));

        org.junit.jupiter.api.Assertions.assertEquals(OrderState.PENDING,
                orders.findByOutTradeNo(no).orElseThrow().state(), "🔴 不调 settle,订单不动");
    }

    @Test
    @DisplayName("收据校验通过 → 走 settle,订单落 PAID")
    void validReceiptSettles() throws Exception {
        String no = createOrder();
        mvc.perform(post("/api/v1/billing/orders/" + no + "/receipt/verify")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipt\":\"" + FakeGateway.GOOD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("PAID")))
                .andExpect(jsonPath("$.grantState").doesNotExist());
    }

    // ——————————————————— §7.3 回调 ———————————————————

    @Test
    @DisplayName("🔴 验签不过 → 拒,响应体是平台那一套不是 ApiError")
    void notifyRejectsBadSignature() throws Exception {
        mvc.perform(post("/api/v1/billing/notify/wxpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"随便\":\"什么\"}"))
                .andExpect(jsonPath("$.code", is("FAIL")))
                .andExpect(jsonPath("$.traceId").doesNotExist());
    }

    @Test
    @DisplayName("验签过 → 回 {\"code\":\"SUCCESS\"},订单推进")
    void notifySettlesAndReturnsPlatformShape() throws Exception {
        String no = createOrder();
        mvc.perform(post("/api/v1/billing/notify/wxpay")
                        .header(FakeGateway.SIGNATURE_HEADER, "ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(no))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("SUCCESS")))
                .andExpect(jsonPath("$.message").doesNotExist());

        org.junit.jupiter.api.Assertions.assertEquals(OrderState.PAID,
                orders.findByOutTradeNo(no).orElseThrow().state());
    }

    // ——————————————————— §8.3 额度 ———————————————————

    @Test
    @DisplayName("GET /quota:periodYm 由服务端给,quotas 是对象不是数组,三个数一起返回")
    void quotaShape() throws Exception {
        mvc.perform(get("/api/v1/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodYm", is(periodYm)))
                .andExpect(jsonPath("$.quotas.ai_capture.granted", is(30)))
                .andExpect(jsonPath("$.quotas.ai_capture.used", is(0)))
                .andExpect(jsonPath("$.quotas.ai_capture.remaining", is(30)))
                .andExpect(jsonPath("$.quotas.ai_ask.granted", is(5)))
                // 🔴 是对象不是数组:数组下标取不到东西
                .andExpect(jsonPath("$.quotas[0]").doesNotExist());
    }

    @Test
    @DisplayName("预检还有余量 → 200,五个字段")
    void precheckWithRemaining() throws Exception {
        mvc.perform(post("/api/v1/quota/precheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quotaType\":\"ai_capture\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaType", is("ai_capture")))
                .andExpect(jsonPath("$.granted", is(30)))
                .andExpect(jsonPath("$.remaining", is(30)))
                .andExpect(jsonPath("$.periodYm", is(periodYm)));
    }

    @Test
    @DisplayName("🔴 预检剩 0 → 403 QUOTA_EXHAUSTED,必须带 details,而 details 里没有任何购买入口")
    void precheckExhaustedCarriesDetails() throws Exception {
        exhaust(QuotaType.AI_ASK);

        mvc.perform(post("/api/v1/quota/precheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quotaType\":\"ai_ask\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("QUOTA_EXHAUSTED")))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.details.quotaType", is("ai_ask")))
                .andExpect(jsonPath("$.details.periodYm", is(periodYm)))
                .andExpect(jsonPath("$.details.manualEntry", is("copy_context")))
                // 🔴 details 里不出现任何指向购买的东西
                .andExpect(jsonPath("$.details.planCode").doesNotExist())
                .andExpect(jsonPath("$.details.upgradeUrl").doesNotExist());
    }

    @Test
    @DisplayName("🔴 预检只读不扣减 —— 连问十次,used 一格不动")
    void precheckNeverConsumes() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/quota/precheck")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quotaType\":\"ai_capture\"}"))
                    .andExpect(status().isOk());
        }
        org.junit.jupiter.api.Assertions.assertEquals(0,
                quotas.find(USER, periodYm, QuotaType.AI_CAPTURE).orElseThrow().used());
    }

    @Test
    @DisplayName("不认识的额度类型 → 400,而且回声截断")
    void precheckRejectsUnknownQuotaType() throws Exception {
        mvc.perform(post("/api/v1/quota/precheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quotaType\":\"AI_CAPTURE\"}"))
                .andExpect(status().isBadRequest());
    }

    // ——————————————————— 夹具 ———————————————————

    private String createOrder() throws Exception {
        return createOrderWithKey(UUID.randomUUID().toString());
    }

    private String createOrderWithKey(String key) throws Exception {
        String json = mvc.perform(post("/api/v1/billing/orders")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"plus\",\"channel\":\"wx_jsapi\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.outTradeNo");
    }

    private String givenOrderIn(OrderState state, GrantState grantState) throws Exception {
        String no = createOrder();
        orders.save(orders.findByOutTradeNo(no).orElseThrow().withState(state, grantState));
        return no;
    }

    private void exhaust(QuotaType type) {
        quotaService.provision(USER, periodYm);
        int granted = quotas.find(USER, periodYm, type).orElseThrow().granted();
        for (int i = 0; i < granted; i++) {
            quotas.consume(USER, periodYm, type, new com.kaodian.server.billing.AiCallLog(
                    0L, USER, type, "/api/v1/ai/ask", "burn-" + i, "p", "m",
                    com.kaodian.server.billing.CallStatus.SUCCESS, 1, 1L, NOW));
        }
    }

    /**
     * ⚠️ <b>这里没有一个手写的 {@code BillingProperties} bean,而那是踩过一次才写下来的</b>:
     * {@code @ConfigurationProperties} 标注的 bean 会被 Spring Boot 的绑定后处理器<b>再绑一次</b>,
     * 于是 {@code @Bean} 方法里调的那些 setter 会被 {@code application.properties} 的值<b>原样盖掉</b>——
     * 表现是 11 条用例一起 {@code 422 CHANNEL_UNAVAILABLE},而夹具里明明写着开了微信。
     * <p>
     * 所以配置走 {@code @WebMvcTest(properties = …)} 覆盖<b>那一行</b>,其余读真配置。
     * 顺带证明两件事:{@code kaodian.billing.zone} 能绑成 {@link java.time.ZoneId},
     * {@code plans[n].quota.ai-capture} 能松散绑成 {@link QuotaType#AI_CAPTURE}。
     */
    @TestConfiguration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(BillingProperties.class)
    static class Fixtures {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        PaymentOrderStore paymentOrderStore() {
            return new FilePaymentOrderStore(tempFile("billing-orders.json"));
        }

        @Bean
        SubscriptionStore subscriptionStore() {
            return new FileSubscriptionStore(tempFile("billing-subscriptions.json"));
        }

        @Bean
        QuotaStore quotaStore() {
            return new FileQuotaStore(tempFile("billing-quota.json"));
        }

        @Bean
        PaymentGateway paymentGateway() {
            return new FakeGateway();
        }

        @Bean
        IdempotencyGuard idempotencyGuard(Clock clock) {
            return new IdempotencyGuard(clock);
        }

        @Bean
        QuotaService quotaService(QuotaStore quotas, SubscriptionStore subscriptions,
                                  BillingProperties properties, Clock clock) {
            return new QuotaService(quotas, subscriptions, properties, clock);
        }

        @Bean
        OrderService orderService(PaymentOrderStore orders, PaymentGateway gateway,
                                  BillingProperties properties, Clock clock) {
            return new OrderService(orders, gateway, properties, clock);
        }

        @Bean
        PaymentSettleService paymentSettleService(PaymentOrderStore orders, SubscriptionStore subscriptions,
                                                  QuotaStore quotas, QuotaService quotaService,
                                                  BillingProperties properties, Clock clock) {
            return new PaymentSettleService(orders, subscriptions, quotas, quotaService, properties, clock);
        }

        private static Path tempFile(String name) {
            try {
                return Files.createTempDirectory("kaodian-billing").resolve(name);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * 假网关。它<b>不测上游</b>,测的是我方在三档结果上各走哪条路。
     *
     * <p>回调那一侧:带签名头就当验签过,body 直接当成 {@code outTradeNo} ——
     * 真实的验签与解密是平台的事,而这里要钉的是「验不过就不进业务」和「响应体是平台那一套」。
     */
    static final class FakeGateway implements PaymentGateway {

        static final String SIGNATURE_HEADER = "Wechatpay-Signature";
        static final String GOOD = "good-receipt";
        static final String BOOM = "boom-receipt";

        @Override
        public Map<String, Object> prepay(PaymentOrder order) {
            return Map.of("prepayId", "wx-prepay-" + order.outTradeNo());
        }

        @Override
        public UpstreamState query(PaymentOrder order) {
            return new UpstreamState(UpstreamState.UpstreamStatus.NOT_PAID, order.amountFen(), null);
        }

        @Override
        public UpstreamState verifyReceipt(PaymentOrder order, String receipt) {
            if (BOOM.equals(receipt)) {
                throw new PaymentGatewayException("上游够不着");
            }
            return GOOD.equals(receipt)
                    ? new UpstreamState(UpstreamState.UpstreamStatus.PAID_UPSTREAM,
                            order.amountFen(), "apple-tx-" + order.outTradeNo())
                    : new UpstreamState(UpstreamState.UpstreamStatus.UNKNOWN, order.amountFen(), null);
        }

        @Override
        public Optional<Notification> verifyNotification(Map<String, String> headers, String body) {
            boolean signed = headers.keySet().stream().anyMatch(SIGNATURE_HEADER::equalsIgnoreCase);
            return signed
                    ? Optional.of(new Notification(body.trim(), "SUCCESS", 990, "wx-tx-" + body.trim()))
                    : Optional.empty();
        }
    }
}
