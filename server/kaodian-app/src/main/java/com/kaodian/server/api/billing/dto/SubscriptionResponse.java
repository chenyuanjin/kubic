package com.kaodian.server.api.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kaodian.server.billing.PaymentOrder;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/v1/billing/subscription} 的响应({@code M7-额度与订单} §5.3 / §8.4)。
 *
 * @param planCode      当前档位
 * @param autoRenew     🔴 恒 {@code false},取 {@link PlansResponse#AUTO_RENEW} ——
 *                      全仓只有那一处赋值
 * @param expiresAt     🔴 <b>为空表示免费档</b>,界面那一行整行不渲染({@code U7.6} §2.6)。
 *                      <b>不返回「永久有效」这类字符串</b>
 * @param pendingOrders {@code state ∈ {PENDING, CONFIRMING}} 的单,按 {@code createdAt} 倒序。
 *                      🔴 <b>一笔都没有时整个 key 不出现</b>,不是空数组(§1.1)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscriptionResponse(
        String planCode,
        boolean autoRenew,
        Instant expiresAt,
        List<PendingOrderDto> pendingOrders) {

    public static SubscriptionResponse of(String planCode, Instant expiresAt, List<PaymentOrder> open) {
        // 🔴 空数组与「没有」在契约上是两件事:整个 key 不出现。
        List<PendingOrderDto> pending = open.isEmpty()
                ? null
                : open.stream().map(PendingOrderDto::of).toList();
        return new SubscriptionResponse(planCode, PlansResponse.AUTO_RENEW, expiresAt, pending);
    }

    /**
     * 「继续上一笔」那个按钮要的四个字段。
     *
     * <p>🔴 <b>为什么这里是数组而不是一个对象</b>:今天实际长度恒为 0 或 1(同档位复用,§3.4);
     * 写成对象的话,<b>重开第二个付费档那天这里就是一次契约变更</b>。
     */
    public record PendingOrderDto(String outTradeNo, String planCode, String state, Instant createdAt) {

        static PendingOrderDto of(PaymentOrder order) {
            return new PendingOrderDto(order.outTradeNo(), order.planCode(),
                    order.state().name(), order.createdAt());
        }
    }
}
