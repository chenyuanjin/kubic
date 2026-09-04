package com.kaodian.server.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 🔴 <b>全仓唯一一处能把订单推进到 {@code PAID} 的地方</b>({@code M7-额度与订单} §3.1)。
 *
 * <h2>三条路,一次幂等写入</h2>
 *
 * <table>
 *   <caption>三条路</caption>
 *   <tr><th>路</th><th>触发</th></tr>
 *   <tr><td>一 · 回调</td><td>{@code POST /billing/notify/wxpay}</td></tr>
 *   <tr><td>二 · 主动查单</td><td>{@code GET /billing/orders/{outTradeNo}}</td></tr>
 *   <tr><td>三 · 定时补偿</td><td>{@link SettleCompensationTask}</td></tr>
 * </table>
 *
 * <b>三条路不各写一段发放代码</b>,全部收敛到 {@link #settle};参数只差 {@link UpstreamState} 从哪来。
 * 归一表也只有一份({@link UpstreamState#ofWeChatTradeState})—— 三条路各自映射会分叉,
 * 而分叉的方向一定是「其中一条把 {@code UNKNOWN} 当成了 {@code NOT_PAID}」,
 * <b>那一刻订单会被关掉,而钱在我方</b>。
 */
@Service
public class PaymentSettleService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettleService.class);

    private final PaymentOrderStore orders;
    private final SubscriptionStore subscriptions;
    private final QuotaStore quotas;
    private final QuotaService quotaService;
    private final BillingProperties properties;
    private final Clock clock;

    public PaymentSettleService(PaymentOrderStore orders, SubscriptionStore subscriptions,
                                QuotaStore quotas, QuotaService quotaService,
                                BillingProperties properties, Clock clock) {
        this.orders = orders;
        this.subscriptions = subscriptions;
        this.quotas = quotas;
        this.quotaService = quotaService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 六步,每一步各有自己的幂等锚点({@code M7} §3.3)。
     *
     * <ol>
     *   <li>载入订单;<b>已是终态 → 立即返回,什么都不做</b> —— 三条路重复到达时最先撞上的那一道</li>
     *   <li>🔴 <b>校金额</b>:与 {@code order.amountFen} 不符 → 拒绝 + 告警 + 不发放,订单不推进</li>
     *   <li>写 {@code transactionId}(唯一键)。撞唯一键 → 视为已处理,不重复发放</li>
     *   <li>{@code state = CONFIRMING},{@code grantState = IN_PROGRESS}</li>
     *   <li><b>同一次原子写</b>:{@code expiresAt} 延长 + {@link QuotaStore#grant} 抬到新档位</li>
     *   <li>{@code state = PAID},{@code paidAt},🔴 <b>{@code grantState} 整个字段清除</b></li>
     * </ol>
     */
    public SettleResult settle(String outTradeNo, UpstreamState upstream) {
        // ① 载入 + 终态直接返回。
        Optional<PaymentOrder> found = orders.findByOutTradeNo(outTradeNo);
        if (found.isEmpty()) {
            return SettleResult.ORDER_NOT_FOUND;
        }
        PaymentOrder order = found.get();
        if (order.state().isTerminal()) {
            return SettleResult.ALREADY_TERMINAL;
        }

        return switch (upstream.status()) {
            case NOT_PAID -> SettleResult.NOT_PAID;               // 🔴 不动
            case PAYING -> markConfirming(order);
            case CLOSED_UPSTREAM -> {
                orders.save(order.withState(OrderState.CLOSED, null));
                yield SettleResult.CLOSED;
            }
            case REFUNDED_UPSTREAM -> {
                // 🔴 额度与会员期一格不动 —— 在退款规则(A/B/C)定下来之前,
                //    不动是唯一一个不预设规则的动作(§6.4 / §十四 第 1 条)。
                orders.save(order.refunded(clock.instant()));
                yield SettleResult.REFUNDED;
            }
            case UNKNOWN -> {
                // 🔴 不动 + 告警。与端「未知 state 按确认中处置,不猜成功也不猜失败」同构。
                log.warn("上游态未识别,订单不推进 outTradeNo={} —— 需要人看一眼", outTradeNo);
                yield SettleResult.UNKNOWN_UPSTREAM;
            }
            case PAID_UPSTREAM -> grant(order, upstream);
        };
    }

    private SettleResult markConfirming(PaymentOrder order) {
        if (order.state() != OrderState.CONFIRMING) {
            orders.save(order.withState(OrderState.CONFIRMING, GrantState.NOT_STARTED));
        }
        return SettleResult.CONFIRMING;
    }

    private SettleResult grant(PaymentOrder order, UpstreamState upstream) {
        // ② 🔴 校金额。回调是外部输入,这是最后一道。
        if (upstream.amountFen() != order.amountFen()) {
            log.error("金额不符,拒绝发放 outTradeNo={} 订单={}分 上游={}分",
                    order.outTradeNo(), order.amountFen(), upstream.amountFen());
            return SettleResult.AMOUNT_MISMATCH;
        }

        // ③ 认领上游交易号(唯一键)。微信与 Apple 落同一列同一唯一键(§4.5)。
        String transactionId = upstream.transactionId();
        if (transactionId == null || transactionId.isBlank()) {
            log.error("上游说已支付却没给交易号,拒绝发放 outTradeNo={}", order.outTradeNo());
            return SettleResult.UNKNOWN_UPSTREAM;
        }
        if (!orders.claimTransactionId(order.outTradeNo(), transactionId)) {
            log.warn("交易号已被别的订单占用,视为已处理,不重复发放 outTradeNo={}", order.outTradeNo());
            return SettleResult.ALREADY_TERMINAL;
        }

        // ④ 收款已确认、发放进行中。
        PaymentOrder confirming = orders.findByOutTradeNo(order.outTradeNo()).orElseThrow()
                .withState(OrderState.CONFIRMING, GrantState.IN_PROGRESS);
        orders.save(confirming);

        // ⑤ 同一次原子写:延长会员期 + 按新档位抬额度。
        try {
            grantEntitlements(confirming);
        } catch (RuntimeException e) {
            // 🔴 IN_PROGRESS 与 FAILED 在服务端必须是两种情况 —— 合并的后果是一笔已经失败过的
            //    发放一直穿着「正在到账」这件衣服(U7.6 §2.2)。
            log.error("发放失败,订单停在 CONFIRMING 等补偿重试 outTradeNo={}", confirming.outTradeNo(), e);
            orders.save(confirming.withState(OrderState.CONFIRMING, GrantState.FAILED));
            return SettleResult.GRANT_FAILED;
        }

        // ⑥ PAID + paidAt,grantState 整个字段清除 —— PAID 本身就说明发放完成。
        orders.save(confirming.paid(clock.instant()));
        return SettleResult.GRANTED;
    }

    /**
     * 🔴 <b>为什么必须原子</b>:续费的语义是「延长到期日的<u>同时</u>按新档位重发本周期额度」
     * ({@code U7.6} §2.6)。
     *
     * <p>两次写分开,中间挂掉就会出现「<b>会员期延长了、额度还是免费档</b>」——
     * 而这个状态<b>没有任何一条路径会发现它</b>,因为订单已经是 {@code PAID}。
     *
     * <p>ponytail: 文件态下「原子」= 这个方法整体在 {@code settle} 的一次调用里跑完,
     * 中途抛异常则订单停在 {@code CONFIRMING}/{@code FAILED} 由补偿任务重跑
     * (重跑安全:{@code grant} 只升不降、{@code expiresAt} 从较晚那点起算)。
     * <b>它不是一个跨两个 store 的事务</b> —— 那要等迁库(B0-1)。
     * 补偿重试就是这个天花板的对价,不是补丁。
     */
    private void grantEntitlements(PaymentOrder order) {
        BillingProperties.Plan plan = properties.plan(order.planCode()).orElseThrow(
                () -> new IllegalStateException("订单上的档位在配置里没有了:" + order.planCode()));

        Instant now = clock.instant();
        Subscription current = subscriptions.find(order.userId())
                .orElse(new Subscription(order.userId(), plan.getCode(), null));
        // 没到期就续,从原到期日接着算;已过期再续从现在算。见 Subscription#extendFrom。
        Instant base = current.extendFrom(now);
        Instant expiresAt = "year".equals(plan.getBillingPeriod())
                ? base.plus(365, ChronoUnit.DAYS)
                : base.plus(30, ChronoUnit.DAYS);
        subscriptions.save(new Subscription(order.userId(), plan.getCode(), expiresAt));

        // 按新档位重发本周期额度。grant 只升不降,所以重跑一次不会把 used 抹掉,
        // 也不会因为重复到达而变成三倍(§3.5 判据 ④)。
        String periodYm = quotaService.currentPeriod();
        for (QuotaType type : QuotaType.values()) {
            quotas.grant(order.userId(), periodYm, type, plan.quotaOf(type));
        }
    }
}
