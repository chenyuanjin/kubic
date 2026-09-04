package com.kaodian.server.billing;

/**
 * 发放进度 —— 🔴 <b>只在 {@link OrderState#CONFIRMING} 时出现,其余四态整个 key 不存在</b>
 * ({@code 接口契约} §8.6.1 + §1.1 空值规则,{@code M7-额度与订单} §4.1)。
 *
 * <p>落法是 DTO 上 {@code @JsonInclude(NON_NULL)} + {@code state != CONFIRMING} 时置 {@code null}。
 * <b>不是返回 {@code "grantState": null}</b>。
 *
 * <h2>🔴 {@link #IN_PROGRESS} 与 {@link #FAILED} 必须是两种情况</h2>
 *
 * 合并的后果是<b>一笔已经失败过的发放一直穿着「正在到账」这件衣服</b>({@code U7.6} §2.2)。
 */
public enum GrantState {

    /** 收款还在确认中,发放尚未开始(上游归一为 {@code PAYING} 时)。 */
    NOT_STARTED,

    /** 收款已确认,发放进行中。 */
    IN_PROGRESS,

    /**
     * 发放那一步失败了 —— {@code state} 停在 {@code CONFIRMING},🔴 <b>告警 + 进补偿重试</b>。
     *
     * <p>🔴 <b>不能退回 {@code PENDING},也不能推进到 {@code PAID}</b>:钱已经在我方,
     * 而权益还没到手,这个组合正是补偿任务要盯的那一档。
     */
    FAILED
}
