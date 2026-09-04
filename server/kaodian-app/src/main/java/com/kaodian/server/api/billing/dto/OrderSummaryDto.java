package com.kaodian.server.api.billing.dto;

import com.kaodian.server.billing.PaymentOrder;

import java.time.Instant;

/**
 * {@code GET /api/v1/billing/orders} 列表里的一条({@code M7-额度与订单} §8.2)。
 *
 * <h2>🔴 只有四类字段,多一类就要回答它服务于哪一个关卡</h2>
 *
 * <b>列表里没有 {@code planCode}</b> —— 档位屏那个「继续上一笔」按钮走
 * {@code GET /billing/subscription} 的 {@code pendingOrders}(§5.3),
 * <b>不为一个按钮撑破列表的字段约束</b>({@code 接口契约} §8.9)。
 *
 * <p>也没有 {@code outTradeNo}:它只在详情里出现(详情比列表多三项)。
 *
 * <p>整页的形状是 {@code B0} §7.1 的 {@code Page<T>} —— 🔴 <b>不返回 {@code total} / {@code hasMore}</b>
 * ({@code U7.6} 逐字要求「不做『加载更多』按钮」)。
 * <b>档位屏区四</b>用同一个端点取 {@code limit=3},🔴 不建「档位屏专用的订单摘要端点」。
 */
public record OrderSummaryDto(String productName, int amountFen, String state, Instant createdAt) {

    public static OrderSummaryDto of(PaymentOrder order) {
        return new OrderSummaryDto(order.productName(), order.amountFen(),
                order.state().name(), order.createdAt());
    }
}
