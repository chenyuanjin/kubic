package com.kaodian.server.billing;

/**
 * 上游支付平台的状态,<b>归一之后</b>的样子({@code M7-额度与订单} §3.2)。
 *
 * <h2>🔴 归一表是一处,不是每条路各写一份</h2>
 *
 * 三条路(回调 / 主动查单 / 定时补偿)拿到的都是支付平台的原始状态,形状各不相同。
 * 三条路各自映射<b>会分叉</b>,而分叉的方向一定是「其中一条把 {@code UNKNOWN} 当成了
 * {@code NOT_PAID}」—— <b>那一刻订单会被关掉,而钱在我方</b>。
 * <p>
 * 所以 {@link #ofWeChatTradeState} 是全仓唯一一处认识 {@code trade_state} 那几个串的地方,
 * {@link PaymentSettleService#settle} 只认归一后的枚举。
 *
 * @param status        归一后的态
 * @param amountFen     上游报的金额,整数分 —— 🔴 {@code settle} 第 ② 步拿它跟订单比
 * @param transactionId 上游交易号;{@code null} 表示这一次上游没给(未支付的查单)
 */
public record UpstreamState(UpstreamStatus status, int amountFen, String transactionId) {

    /** 归一后的取值域。🔴 端与服务端<b>都不猜</b>:未识别就是 {@link UpstreamStatus#UNKNOWN}。 */
    public enum UpstreamStatus {

        /** 上游说付成功了 —— 校金额 → 发放(§3.3)。 */
        PAID_UPSTREAM,

        /** 用户支付中 —— {@code state = CONFIRMING},{@code grantState = NOT_STARTED}。 */
        PAYING,

        /**
         * 未支付 —— 🔴 <b>不动</b>,订单留在 {@code PENDING}。
         *
         * <p>微信的 {@code PAYERROR} 也归到这里:「支付失败」是<b>端本地态</b>
         * ({@code 接口契约} §8.6.2 那两行 🔵 之一),服务端从来不知道用户刚才试了一次没成。
         */
        NOT_PAID,

        /** 上游关单 / 已撤销 —— {@code state = CLOSED}。 */
        CLOSED_UPSTREAM,

        /** 上游退款 —— {@code state = REFUNDED}(§6.4:平台侧发起,我方无端点)。 */
        REFUNDED_UPSTREAM,

        /**
         * 🔴 未识别 —— <b>不动 + 告警</b>。
         *
         * <p>与端「未知 {@code state} 按确认中处置,不猜成功也不猜失败」({@code 接口契约} §8.6)同构。
         * <b>收据无效也归这里,而不是 {@code NOT_PAID}</b> —— 一张读不懂的收据不说明这一笔没付(§4.5)。
         */
        UNKNOWN
    }

    /**
     * 微信 {@code trade_state} → 归一态。<b>全仓唯一一处认识那几个串的地方。</b>
     *
     * <p>Apple 内购走同一张表:收据校验通过 → {@link UpstreamStatus#PAID_UPSTREAM};
     * 收据无效 → {@link UpstreamStatus#UNKNOWN};网络 / 上游错误 → <b>根本不调 {@code settle}</b>(§4.5)。
     */
    public static UpstreamState ofWeChatTradeState(String tradeState, int amountFen, String transactionId) {
        UpstreamStatus status = switch (tradeState == null ? "" : tradeState.trim()) {
            case "SUCCESS" -> UpstreamStatus.PAID_UPSTREAM;
            case "USERPAYING" -> UpstreamStatus.PAYING;
            case "NOTPAY", "PAYERROR" -> UpstreamStatus.NOT_PAID;
            case "CLOSED", "REVOKED" -> UpstreamStatus.CLOSED_UPSTREAM;
            case "REFUND" -> UpstreamStatus.REFUNDED_UPSTREAM;
            // 🔴 其它 / 未识别 → 不动 + 告警。不猜成功也不猜失败。
            default -> UpstreamStatus.UNKNOWN;
        };
        return new UpstreamState(status, amountFen, transactionId);
    }
}
