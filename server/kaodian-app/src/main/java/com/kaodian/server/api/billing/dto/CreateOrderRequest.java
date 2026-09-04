package com.kaodian.server.api.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/billing/orders} 的请求体({@code M7-额度与订单} §8.2)。
 *
 * <h2>🔴 这里<b>只有两个字段</b>,而缺席的那两个各是一条契约</h2>
 *
 * <ul>
 *   <li><b>没有 {@code amountFen}</b> —— 🔴 body 不接受金额,金额由服务端按 {@code plans} 定。
 *       接受它就等于让端定价</li>
 *   <li><b>没有 {@code outTradeNo}</b> —— 🔴 订单号由服务端生成(§3.4)。它同时是路径参数与自然键,
 *       <b>让端生成等于把幂等的锚点交给端</b>。§3.5 判据 ③ 扫的就是这个字段在不在请求体里</li>
 * </ul>
 *
 * <p>⚠ {@code spring.jackson.deserialization.FAIL_ON_UNKNOWN_PROPERTIES=true} 已经打开,
 * 所以送来一个 {@code amountFen} 会<b>当场 400</b>,而不是被安静地忽略。
 *
 * @param planCode 档位 code。取值域是服务端 {@code plans} 列表,<b>不是写死的枚举</b>
 * @param channel  三个取值之一(§5.2),且必须是<b>这一端此刻可用的那个</b>
 */
public record CreateOrderRequest(
        @NotBlank @Size(max = 64) String planCode,
        @NotBlank @Size(max = 32) String channel) {
}
