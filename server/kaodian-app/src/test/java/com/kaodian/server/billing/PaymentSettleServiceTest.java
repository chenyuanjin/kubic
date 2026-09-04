package com.kaodian.server.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三条路一次幂等写入({@code M7-额度与订单} §3.5 判据 ④ 与「金额不符」那一条)。
 *
 * <p>这里跑的是 {@link PaymentSettleService} 本体,不经 HTTP —— 三条路的区别只在
 * {@link UpstreamState} 从哪来,而那一层在这条判据里不是变量。
 */
class PaymentSettleServiceTest {

    private static final long USER = 10001L;
    private static final Instant NOW = Instant.parse("2026-09-04T02:00:00Z");
    private static final int PLUS_FEN = 990;
    private static final int PLUS_CAPTURE = 300;
    private static final int PLUS_ASK = 50;

    @TempDir
    Path dir;

    private FilePaymentOrderStore orders;
    private FileSubscriptionStore subscriptions;
    private FileQuotaStore quotas;
    private PaymentSettleService settle;
    private OrderService orderService;
    private QuotaService quotaService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        BillingProperties properties = properties();

        orders = new FilePaymentOrderStore(dir.resolve("billing-orders.json"));
        subscriptions = new FileSubscriptionStore(dir.resolve("billing-subscriptions.json"));
        quotas = new FileQuotaStore(dir.resolve("billing-quota.json"));
        quotaService = new QuotaService(quotas, subscriptions, properties, clock);
        settle = new PaymentSettleService(orders, subscriptions, quotas, quotaService, properties, clock);
        orderService = new OrderService(orders, new FakeGateway(), properties, clock);
    }

    /** 🔴 §3.5 判据 ④ —— 本节的核心判据。 */
    @Test
    void 回调与查单与补偿三条路各到一次_只发放一次() {
        String no = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();
        UpstreamState up = paid("wx-tx-1");

        assertEquals(SettleResult.GRANTED, settle.settle(no, up));          // 路一 · 回调
        assertEquals(SettleResult.ALREADY_TERMINAL, settle.settle(no, up)); // 路二 · 主动查单
        assertEquals(SettleResult.ALREADY_TERMINAL, settle.settle(no, up)); // 路三 · 定时补偿

        Instant expiresAt = subscriptions.find(USER).orElseThrow().expiresAt();
        assertEquals(NOW.plus(java.time.Duration.ofDays(30)), expiresAt,
                "🔴 一个月,不是三个月 —— 三条路各到一次不该各延一次");

        String ym = quotaService.currentPeriod();
        assertEquals(PLUS_CAPTURE, quotas.find(USER, ym, QuotaType.AI_CAPTURE).orElseThrow().granted(),
                "🔴 不是三倍");
        assertEquals(PLUS_ASK, quotas.find(USER, ym, QuotaType.AI_ASK).orElseThrow().granted());
    }

    /** 🔴 金额不符 → 拒绝 + 不发放,订单不推进(§3.3 步 ②)。 */
    @Test
    void 金额不符时拒绝并且不发放() {
        String no = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();

        assertEquals(SettleResult.AMOUNT_MISMATCH,
                settle.settle(no, new UpstreamState(UpstreamState.UpstreamStatus.PAID_UPSTREAM, 1, "wx-tx-2")));

        assertNotEquals(OrderState.PAID, orders.findByOutTradeNo(no).orElseThrow().state());
        assertTrue(subscriptions.find(USER).isEmpty(), "一分权益都不许发出去");
    }

    /** {@code PAID} 之后 {@code grantState} 整个字段清除 —— {@code PAID} 本身就说明发放完成(步 ⑥)。 */
    @Test
    void 付款完成之后不再带发放进度() {
        String no = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();
        settle.settle(no, paid("wx-tx-3"));

        PaymentOrder order = orders.findByOutTradeNo(no).orElseThrow();
        assertEquals(OrderState.PAID, order.state());
        assertNull(order.grantState(), "🔴 grantState 只在 CONFIRMING 出现");
        assertEquals(NOW, order.paidAt());
    }

    /** 上游说「支付中」→ {@code CONFIRMING} / {@code NOT_STARTED},不发放。 */
    @Test
    void 支付中只推进到确认中() {
        String no = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();

        assertEquals(SettleResult.CONFIRMING,
                settle.settle(no, new UpstreamState(UpstreamState.UpstreamStatus.PAYING, PLUS_FEN, null)));

        PaymentOrder order = orders.findByOutTradeNo(no).orElseThrow();
        assertEquals(OrderState.CONFIRMING, order.state());
        assertEquals(GrantState.NOT_STARTED, order.grantState());
        assertTrue(subscriptions.find(USER).isEmpty());
    }

    /** 🔴 未识别的上游态 → 不动 + 告警。不猜成功也不猜失败(§3.2)。 */
    @Test
    void 未识别的上游态一格不动() {
        String no = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();

        assertEquals(SettleResult.UNKNOWN_UPSTREAM, settle.settle(no,
                UpstreamState.ofWeChatTradeState("这是什么", PLUS_FEN, "wx-tx-4")));

        assertEquals(OrderState.PENDING, orders.findByOutTradeNo(no).orElseThrow().state(),
                "🔴 未识别时把订单关掉,就是把一笔钱在我方的单变成不可查");
    }

    /** {@code PAYERROR} 归 {@code NOT_PAID} —— 「支付失败」是端本地态,订单留在 {@code PENDING}。 */
    @Test
    void 支付失败不是一个服务端状态() {
        String no = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();

        assertEquals(SettleResult.NOT_PAID, settle.settle(no,
                UpstreamState.ofWeChatTradeState("PAYERROR", PLUS_FEN, null)));

        assertEquals(OrderState.PENDING, orders.findByOutTradeNo(no).orElseThrow().state());
    }

    /** 🔴 进 {@code REFUNDED} 之后额度与会员期一格不动(§6.4:A/B/C 未选,不动是唯一不预设规则的动作)。 */
    @Test
    void 退款之后额度与会员期一格不动() {
        String no = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();
        settle.settle(no, paid("wx-tx-5"));

        Instant before = subscriptions.find(USER).orElseThrow().expiresAt();
        String ym = quotaService.currentPeriod();
        int grantedBefore = quotas.find(USER, ym, QuotaType.AI_CAPTURE).orElseThrow().granted();

        // PAID 是终态,退款只能从上游那一侧到达一笔还没终结的单;这里直接构一笔来跑这条边。
        PaymentOrder open = orders.findByOutTradeNo(no).orElseThrow()
                .withState(OrderState.CONFIRMING, GrantState.NOT_STARTED);
        orders.save(open);

        assertEquals(SettleResult.REFUNDED, settle.settle(no,
                UpstreamState.ofWeChatTradeState("REFUND", PLUS_FEN, "wx-tx-5")));

        assertEquals(OrderState.REFUNDED, orders.findByOutTradeNo(no).orElseThrow().state());
        assertEquals(before, subscriptions.find(USER).orElseThrow().expiresAt(), "会员期不动");
        assertEquals(grantedBefore, quotas.find(USER, ym, QuotaType.AI_CAPTURE).orElseThrow().granted(),
                "额度不动");
    }

    /** 同档位复用,不新建 —— 最多一笔待支付(§3.4)。 */
    @Test
    void 同档位再次下单复用那一笔() {
        String first = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();
        String second = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();

        assertEquals(first, second, "🔴 复用,不新建");
        assertEquals(1, orders.findOpenByUser(USER).size(), "最多一笔待支付");
    }

    /** 续费从原到期日接着算,不从现在起算 —— 否则白吃掉用户没用完的那一段。 */
    @Test
    void 未到期续费从原到期日接着算() {
        String first = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();
        settle.settle(first, paid("wx-tx-6"));
        Instant afterFirst = subscriptions.find(USER).orElseThrow().expiresAt();

        // 第一笔已经是 PAID(终态),所以下一次下单会新建而不是复用 —— 这正是「续费」那条路。
        String second = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();
        assertNotEquals(first, second);
        settle.settle(second, paid("wx-tx-7"));

        assertEquals(afterFirst.plus(java.time.Duration.ofDays(30)),
                subscriptions.find(USER).orElseThrow().expiresAt());
    }

    /** 补偿重试能续上自己那次没做完的发放 —— 交易号已被自己占着不算「撞唯一键」。 */
    @Test
    void 发放失败之后补偿重试能续上() {
        String no = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();
        PaymentOrder stuck = orders.findByOutTradeNo(no).orElseThrow()
                .withState(OrderState.CONFIRMING, GrantState.FAILED)
                .withTransactionId("wx-tx-8");
        orders.save(stuck);

        assertEquals(SettleResult.GRANTED, settle.settle(no, paid("wx-tx-8")),
                "🔴 自己占着的交易号不是撞唯一键 —— 否则这一单永远停在『收了钱没发货』");
        assertEquals(OrderState.PAID, orders.findByOutTradeNo(no).orElseThrow().state());
    }

    /** 别人占着同一个交易号才是真的撞唯一键。 */
    @Test
    void 别人占着的交易号不给发放() {
        String mine = orderService.create(USER, "plus", Channel.WX_JSAPI).order().outTradeNo();
        PaymentOrder other = orders.findByOutTradeNo(mine).orElseThrow();
        orders.save(new PaymentOrder("KD-someone-else", 10002L, "plus", "记多点", PLUS_FEN,
                Channel.WX_JSAPI, OrderState.PAID, null, "wx-tx-9",
                NOW, NOW.plusSeconds(7200), NOW, null));

        assertEquals(SettleResult.ALREADY_TERMINAL, settle.settle(mine, paid("wx-tx-9")));
        assertFalse(subscriptions.find(other.userId()).isPresent(), "不许替别人那笔再发一次货");
    }

    // ——————————————————— 夹具 ———————————————————

    private static UpstreamState paid(String transactionId) {
        return new UpstreamState(UpstreamState.UpstreamStatus.PAID_UPSTREAM, PLUS_FEN, transactionId);
    }

    private static BillingProperties properties() {
        BillingProperties p = new BillingProperties();
        p.setZone(ZoneOffset.UTC);
        p.setDefaultPlan("free");
        p.setChannels(List.of(Channel.WX_JSAPI, Channel.WX_VIRTUAL_IOS, Channel.APPLE_IAP));

        BillingProperties.Plan free = new BillingProperties.Plan();
        free.setCode("free");
        free.setName("免费");
        free.setPriceFen(0);
        free.setPurchasable(false);
        free.setQuota(quota(30, 5));

        BillingProperties.Plan plus = new BillingProperties.Plan();
        plus.setCode("plus");
        plus.setName("记多点");
        plus.setPriceFen(PLUS_FEN);
        plus.setPurchasable(true);
        plus.setBillingPeriod("month");
        plus.setBadge("推荐");
        plus.setQuota(quota(PLUS_CAPTURE, PLUS_ASK));

        p.setPlans(List.of(free, plus));
        return p;
    }

    private static Map<QuotaType, Integer> quota(int capture, int ask) {
        Map<QuotaType, Integer> quota = new LinkedHashMap<>();
        quota.put(QuotaType.AI_CAPTURE, capture);
        quota.put(QuotaType.AI_ASK, ask);
        return quota;
    }

    /** 只回一组调起参数;这条判据不测上游,测的是我方三条路收敛到同一个 settle。 */
    private static final class FakeGateway implements PaymentGateway {
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
            return new UpstreamState(UpstreamState.UpstreamStatus.UNKNOWN, order.amountFen(), null);
        }

        @Override
        public Optional<Notification> verifyNotification(Map<String, String> headers, String body) {
            return Optional.empty();
        }
    }
}
