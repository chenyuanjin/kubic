package com.kaodian.server.api.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kaodian.server.billing.OrderState;
import com.kaodian.server.billing.PaymentOrder;

import java.time.Instant;

/**
 * {@code GET /api/v1/billing/orders/{outTradeNo}} 的响应({@code M7-额度与订单} §8.2 / §4.1)。
 *
 * <p>详情比列表多三项:{@code paidAt} / {@code outTradeNo} / {@code grantState}。
 *
 * <h2>🔴 {@code grantState} 只在 {@code CONFIRMING} 出现,其余四态整个 key 不存在</h2>
 *
 * ({@code 接口契约} §8.6.1 + §1.1 空值规则)。落法是本类上的 {@code @JsonInclude(NON_NULL)}
 * + {@code state != CONFIRMING} 时置 {@code null} —— <b>不是返回 {@code "grantState": null}</b>。
 * 留一个 {@code null},端上就会写出一句 {@code if ('grantState' in order)} 然后永远为真。
 *
 * <p>{@code outTradeNo} 🔴 <b>完整不截断</b>。
 *
 * <p>⚠ 这个 GET <b>会触发一次上游反查并可能推进订单状态</b>(三条路的路二,§3.1)。
 * 它仍然是幂等的:多次调用结果相同,发放本身撞唯一键。
 * <b>不为它另建一个 {@code POST /orders/{no}/query}</b> —— 那会让端多记一条规矩,
 * 而 {@code U7.4} 要的就是「支付后必须主动查一次」。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderDetailResponse(
        String outTradeNo,
        String productName,
        int amountFen,
        String state,
        String grantState,
        Instant createdAt,
        Instant paidAt) {

    public static OrderDetailResponse of(PaymentOrder order) {
        // 不变式在 PaymentOrder 的构造器里已经守住了(非 CONFIRMING 时 grantState 必为 null),
        // 这里再判一次是因为「响应形状」这条契约不该依赖另一个类的构造器还在。
        String grantState = order.state() == OrderState.CONFIRMING && order.grantState() != null
                ? order.grantState().name()
                : null;
        return new OrderDetailResponse(order.outTradeNo(), order.productName(), order.amountFen(),
                order.state().name(), grantState, order.createdAt(), order.paidAt());
    }
}
