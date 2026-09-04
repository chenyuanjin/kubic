package com.kaodian.server.billing;

import java.time.Instant;

/**
 * 一笔订单({@code M7-额度与订单} §4.1 / §8.2)。
 *
 * <h2>🔴 这里<b>没有</b>的两个字段,各自是一条契约</h2>
 *
 * <ul>
 *   <li>{@code closeReason} / {@code lastPayFailedAt} —— 「支付失败」与「已取消」是<b>端本地态</b>,
 *       服务端不给字段({@code 接口契约} §8.6.2)。加它们的条件是「用户隔一天回来看仍然需要看到这个区别」,
 *       今天不成立</li>
 *   <li>任何代扣字段({@code contractId} / {@code nextChargeAt} / {@code autoRenewEnabled})——
 *       <b>结构上不可能发生自动扣款</b>(§4.6 判据 ②)</li>
 * </ul>
 *
 * <p>🔴 {@link #outTradeNo} <b>由服务端生成,端不参与</b>(§3.4):它同时是路径参数与自然键,
 * 让端生成等于把幂等的锚点交给端。
 *
 * @param outTradeNo    商户订单号 —— 自然键,🔴 服务端生成
 * @param userId        谁的
 * @param planCode      买的哪一档。取值域由服务端 {@code plans} 配置定,<b>不是编译期枚举</b>
 * @param productName   下单那一刻的档位名。<b>快照,不跟着配置改</b> —— 订单历史要显示当时买的是什么
 * @param amountFen     整数分。🔴 <b>body 不接受金额</b>,这个数由服务端按 {@code plans} 定
 * @param channel       三值之一
 * @param state         🔴 字段名是 {@code state},见 {@link OrderState}
 * @param grantState    🔴 只在 {@code CONFIRMING} 时非空,其余四态为 {@code null}
 * @param transactionId 上游交易号 —— <b>唯一键</b>。微信与 Apple 落<b>同一列同一唯一键</b>(§4.5)
 * @param createdAt     下单时刻
 * @param expireAt      过期时点。🔴 <b>与传给支付平台的过期时点是同一个值</b>,不是第二个数字(§8.2)
 * @param paidAt        付款完成时刻;未完成为 {@code null}
 * @param refundedAt    退款时刻;非 {@code REFUNDED} 为 {@code null}
 */
public record PaymentOrder(
        String outTradeNo,
        long userId,
        String planCode,
        String productName,
        int amountFen,
        Channel channel,
        OrderState state,
        GrantState grantState,
        String transactionId,
        Instant createdAt,
        Instant expireAt,
        Instant paidAt,
        Instant refundedAt) {

    /**
     * 🔴 不变式:{@code grantState} 只在 {@code CONFIRMING} 出现。
     *
     * <p>写在构造器里而不是留给每一个赋值点各判一次 —— 那样迟早有一处漏掉,
     * 而漏掉的表现是响应里凭空多一个 key,端按它写分支。
     */
    public PaymentOrder {
        if (state != OrderState.CONFIRMING && grantState != null) {
            throw new IllegalArgumentException(
                    "grantState 只在 CONFIRMING 出现,当前 state=" + state + "(`接口契约` §8.6.1)");
        }
    }

    public PaymentOrder withState(OrderState newState, GrantState newGrantState) {
        return new PaymentOrder(outTradeNo, userId, planCode, productName, amountFen, channel,
                newState, newGrantState, transactionId, createdAt, expireAt, paidAt, refundedAt);
    }

    public PaymentOrder withTransactionId(String newTransactionId) {
        return new PaymentOrder(outTradeNo, userId, planCode, productName, amountFen, channel,
                state, grantState, newTransactionId, createdAt, expireAt, paidAt, refundedAt);
    }

    public PaymentOrder paid(Instant at) {
        // ⑥ state = PAID + paidAt,🔴 grantState 整个字段清除 —— PAID 本身就说明发放完成。
        return new PaymentOrder(outTradeNo, userId, planCode, productName, amountFen, channel,
                OrderState.PAID, null, transactionId, createdAt, expireAt, at, refundedAt);
    }

    public PaymentOrder refunded(Instant at) {
        return new PaymentOrder(outTradeNo, userId, planCode, productName, amountFen, channel,
                OrderState.REFUNDED, null, transactionId, createdAt, expireAt, paidAt, at);
    }
}
