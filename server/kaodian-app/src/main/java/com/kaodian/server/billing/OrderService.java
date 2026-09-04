package com.kaodian.server.billing;

import com.kaodian.server.api.dto.common.ErrorCode;
import com.kaodian.server.api.support.ApiException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 下单、关单与归属校验({@code M7-额度与订单} §3.4 / §4.4)。
 *
 * <h2>🔴 {@code outTradeNo} 由服务端生成,端不参与</h2>
 *
 * 它同时是路径参数({@code close} / {@code receipt/verify})与自然键 ——
 * <b>让端生成等于把幂等的锚点交给端</b>。
 */
@Service
public class OrderService {

    /** 商户前缀。它让订单号<b>可枚举</b>,而那正是归属校验取 {@code 404} 不取 {@code 403} 的理由(§4.4)。 */
    private static final String PREFIX = "KD";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentOrderStore orders;
    private final PaymentGateway gateway;
    private final BillingProperties properties;
    private final Clock clock;

    public OrderService(PaymentOrderStore orders, PaymentGateway gateway,
                        BillingProperties properties, Clock clock) {
        this.orders = orders;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    /** 下单的结果:订单本身 + 调起参数。🔴 服务端不解释 {@code payParams},端也不解析(§8.2)。 */
    public record Created(PaymentOrder order, Map<String, Object> payParams) {
    }

    /**
     * 下单 —— 幂等按 §3.4 那张表:
     *
     * <table>
     *   <caption>四种情形</caption>
     *   <tr><th>情形</th><th>处置</th></tr>
     *   <tr><td>同一人同一档已有 {@code PENDING}</td><td>🔴 <b>复用那一笔</b>,同一个 {@code outTradeNo}
     *       + 重新拉取的调起参数。<b>不新建</b></td></tr>
     *   <tr><td>同一人同一档已有 {@code CONFIRMING}</td><td>{@code 409 IN_PROGRESS} ——
     *       上一笔还在确认中,{@code U7.6} 已定「确认中不给重新支付」</td></tr>
     *   <tr><td>同一个 {@code Idempotency-Key} 重放</td><td>{@code IdempotencyGuard} 返回上次结果
     *       (在 controller 那一层),🔴 不产生第二笔</td></tr>
     *   <tr><td>连点两次、两个不同的 {@code Idempotency-Key}</td><td>落到第一行「同档位复用」,
     *       <b>最多一笔待支付</b>({@code U7.4} §2.5)</td></tr>
     * </table>
     */
    public Created create(long userId, String planCode, Channel channel) {
        BillingProperties.Plan plan = properties.plan(planCode)
                .filter(BillingProperties.Plan::isPurchasable)
                .orElseThrow(() -> new ApiException(ErrorCode.PLAN_NOT_PURCHASABLE,
                        "这个档位买不了 —— 它可能已经下架了,刷新一下档位列表。"));

        if (!properties.getChannels().contains(channel)) {
            throw new ApiException(ErrorCode.CHANNEL_UNAVAILABLE,
                    "这个端此刻用不了这个支付方式,换一个。");
        }

        List<PaymentOrder> open = orders.findOpenByUser(userId).stream()
                .filter(o -> o.planCode().equals(planCode))
                .toList();

        Optional<PaymentOrder> confirming = open.stream()
                .filter(o -> o.state() == OrderState.CONFIRMING).findFirst();
        if (confirming.isPresent()) {
            throw new ApiException(ErrorCode.IN_PROGRESS,
                    "上一笔还在确认中,先等它有结果。");
        }

        PaymentOrder order = open.stream()
                .filter(o -> o.state() == OrderState.PENDING)
                .findFirst()
                .orElseGet(() -> orders.save(newOrder(userId, plan, channel)));

        // 复用那一笔时也要重新拉一次调起参数:上一次拿到的可能已经过期,
        // 而 expireAt 是订单自己的那一个数,不因为重新拉取而变(§8.2)。
        return new Created(order, gateway.prepay(order));
    }

    private PaymentOrder newOrder(long userId, BillingProperties.Plan plan, Channel channel) {
        Instant now = clock.instant();
        return new PaymentOrder(
                nextOutTradeNo(now),
                userId,
                plan.getCode(),
                plan.getName(),
                plan.getPriceFen(),
                channel,
                OrderState.PENDING,
                null,
                null,
                now,
                // 🔴 一个数字两个用途:写进订单,同时下发给支付平台当过期时点(§8.5)。
                now.plus(properties.getOrder().getExpireMinutes(), ChronoUnit.MINUTES),
                null,
                null);
    }

    private static String nextOutTradeNo(Instant now) {
        StringBuilder tail = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            tail.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return PREFIX + STAMP.format(now) + tail;
    }

    /**
     * 归属校验 —— 🔴 <b>「不属于本人」返回 {@code 404} 不是 {@code 403}</b>(§4.4)。
     *
     * <p>订单号是<b>可枚举的</b>(带商户前缀与时间戳),{@code 403} 等于确认「这个号存在」。
     * 这与 {@code 接口契约} §6.8 给 agent 会话定的「不属本人 → {@code 403} 不是 {@code 404}」
     * 方向相反,<b>理由也相反</b>:会话 id 是不可枚举的随机串。
     * 两处都对,是<b>两条规则不是一条</b>(§契约增量 4)。
     */
    public PaymentOrder require(long userId, String outTradeNo) {
        return orders.findByOutTradeNo(outTradeNo)
                .filter(o -> o.userId() == userId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "找不到这一笔订单。"));
    }

    /**
     * 主动关单(§4.4)。
     *
     * <table>
     *   <caption>四档</caption>
     *   <tr><th>情形</th><th>HTTP</th><th>{@code code}</th></tr>
     *   <tr><td>{@code PENDING} → 关闭成功</td><td>200</td><td>——</td></tr>
     *   <tr><td>本来就是 {@code CLOSED}</td><td>200</td><td>——(幂等)</td></tr>
     *   <tr><td>{@code PAID} / {@code REFUNDED}</td><td>409</td><td>{@code ORDER_ALREADY_PAID}</td></tr>
     *   <tr><td>{@code CONFIRMING}</td><td>409</td><td>🆕 {@code ORDER_NOT_CLOSEABLE}</td></tr>
     * </table>
     *
     * 🔴 {@code CONFIRMING} <b>不复用 {@code ORDER_ALREADY_PAID}</b>:钱可能已在我方但尚未确认,
     * 界面要说的是「这一笔还在确认中」而<b>接着的动作是「再查一次」</b> ——
     * 与「已支付」那一档的文案和动作都不同,合并就等于让端自己编一句话去解释它。
     */
    public PaymentOrder close(long userId, String outTradeNo) {
        PaymentOrder order = require(userId, outTradeNo);
        return switch (order.state()) {
            case PENDING -> orders.save(order.withState(OrderState.CLOSED, null));
            case CLOSED -> order;                                       // 幂等
            case PAID, REFUNDED -> throw new ApiException(ErrorCode.ORDER_ALREADY_PAID,
                    "这一笔已经支付,关不掉。");
            case CONFIRMING -> throw new ApiException(ErrorCode.ORDER_NOT_CLOSEABLE,
                    "这一笔还在确认中,现在关不掉 —— 过一会儿再查一次。");
        };
    }
}
