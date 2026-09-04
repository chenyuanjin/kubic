package com.kaodian.server.billing;

/**
 * 一次 {@link PaymentSettleService#settle} 的结局。
 *
 * <p>三条路(回调 / 主动查单 / 定时补偿)拿它决定自己那一侧怎么回应:
 * 回调无论如何都回 {@code SUCCESS}(除了验签失败),查单把订单原样返回,补偿只记日志。
 */
public enum SettleResult {

    /** 订单已经是终态 —— 什么都没做(步 ①)。三条路重复到达时最先撞上的那一道。 */
    ALREADY_TERMINAL,

    /** 发放完成,订单推进到 {@code PAID}。 */
    GRANTED,

    /** 上游说「支付中」—— 订单推进到 {@code CONFIRMING} / {@code NOT_STARTED}。 */
    CONFIRMING,

    /** 上游关单 —— 订单推进到 {@code CLOSED}。 */
    CLOSED,

    /** 上游退款 —— 订单推进到 {@code REFUNDED}。🔴 额度与会员期<b>一格不动</b>(§6.4)。 */
    REFUNDED,

    /** 上游说未支付 —— 🔴 <b>不动</b>,订单留在 {@code PENDING}。 */
    NOT_PAID,

    /**
     * 🔴 <b>金额不符</b> —— 拒绝 + 告警 + 不发放,订单不推进。
     *
     * <p>回调是外部输入,金额校验是最后一道({@code U7.4} §2.2)。
     */
    AMOUNT_MISMATCH,

    /**
     * 发放那一步失败 —— {@code grantState = FAILED},{@code state} 停在 {@code CONFIRMING},
     * 🔴 <b>告警 + 进补偿重试</b>({@code 接口契约} §8.6.1)。
     */
    GRANT_FAILED,

    /** 🔴 未识别的上游态 —— <b>不动 + 告警</b>。不猜成功也不猜失败(§3.2)。 */
    UNKNOWN_UPSTREAM,

    /** 订单号根本不存在。 */
    ORDER_NOT_FOUND
}
