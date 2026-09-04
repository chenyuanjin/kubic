package com.kaodian.server.api.billing.dto;

import com.kaodian.server.billing.OrderService;

import java.time.Instant;
import java.util.Map;

/**
 * {@code POST /api/v1/billing/orders} 的响应({@code M7-额度与订单} §8.2)。
 *
 * @param payParams 调起参数,形状<b>随 {@code channel} 变</b>,端原样透传给平台 SDK。
 *                  🔴 <b>服务端不解释它,端也不解析它</b>
 * @param expireAt  🔴 <b>与传给支付平台的过期时点是同一个值</b>,不是第二个数字 ——
 *                  {@code U7.4} §五 缺口 2 问的「以谁为准」,答案是以我方这一个值为准并下发给平台
 */
public record CreateOrderResponse(
        String outTradeNo,
        int amountFen,
        String productName,
        String state,
        Map<String, Object> payParams,
        Instant expireAt) {

    public static CreateOrderResponse of(OrderService.Created created) {
        var order = created.order();
        return new CreateOrderResponse(order.outTradeNo(), order.amountFen(), order.productName(),
                order.state().name(), created.payParams(), order.expireAt());
    }
}
