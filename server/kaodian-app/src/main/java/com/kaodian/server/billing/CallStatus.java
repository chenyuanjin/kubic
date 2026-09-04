package com.kaodian.server.billing;

/**
 * 一次外部模型调用的结局 —— 只有两档({@code M7-额度与订单} §2.2)。
 *
 * <p>🔴 <b>没有 {@code REFUNDED} / {@code REVERSED} 这一档</b>:失败根本不扣
 * ({@code U7.1} §2.5),所以账本里没有「退还」这个动作。多一个态就会有人去写那条路径。
 */
public enum CallStatus {

    /** 外部调用成功 —— 这一次<b>扣了 1</b>。 */
    SUCCESS,

    /**
     * 外部调用失败 —— <b>只留流水,不动 {@code used}</b>。
     *
     * <p>这一行允许被后来的一次成功就地覆盖({@code M7} §2.3 步 ①):
     * {@code 接口契约} §1.5「上次失败 → 允许重试」靠的就是这一条。
     */
    FAILED
}
