package com.kaodian.server.api.billing.dto;

import com.kaodian.server.billing.QuotaType;

/**
 * {@code 403 QUOTA_EXHAUSTED} 的 {@code details}({@code M7-额度与订单} §8.3,
 * {@code B0} §6.1 把这一格留给本模块)。
 *
 * <h2>🔴 {@code details} 里不出现任何指向购买的东西</h2>
 *
 * 没有 {@code planCode}、没有 {@code upgradeUrl}、没有「去看档位」。
 * <b>受限态里付费入口的视觉权重必须低于免费兜底</b>({@code U7.2} §2.7),
 * 而一个由服务端下发的购买字段<b>会让这条约束变成端的自觉</b>。
 *
 * @param quotaType   两个池子互不借用,界面要说清是哪一个用完了
 * @param periodYm    周期由服务端裁定,端不算
 * @param manualEntry 🔴 <b>免费兜底动作的标识,不是一句文案。</b>展示文案由端按 {@code code}
 *                    与这个标识<b>查自己的词表</b>(§1.3:{@code message} 端一律不直接展示)——
 *                    服务端下发文案等于把四个端的词表搬到后端,而那是词表的第二处定义
 */
public record QuotaExhaustedDetails(String quotaType, String periodYm, String manualEntry) {

    /** 手动补一句。⚠ 见 {@link #manualEntryOf}:这个取值今天没有一条能到达它的输入。 */
    public static final String MANUAL_TEXT = "manual_text";

    /** 手动挂考点(含从树里选)。 */
    public static final String MANUAL_TAG = "manual_tag";

    /** 复制上下文自己去问。 */
    public static final String COPY_CONTEXT = "copy_context";

    public static QuotaExhaustedDetails of(QuotaType type, String periodYm) {
        return new QuotaExhaustedDetails(type.wireName(), periodYm, manualEntryOf(type));
    }

    /**
     * 池子 → 免费兜底动作。
     *
     * <h2>⚠️ 三个取值,而这个端点只喂得进两个 —— 一个真实的契约缺口</h2>
     *
     * {@code U7.2} §2.5 那六行给的是<b>三种</b>兜底:手动补一句({@link #MANUAL_TEXT})/
     * 手动挂考点({@link #MANUAL_TAG})/ 复制上下文自己去问({@link #COPY_CONTEXT})。
     * <p>
     * 但 {@code POST /quota/precheck} 的请求体只有 {@code quotaType} 一个字段,
     * 而 {@code ai_capture} <b>同时盖着语音转写与图片识别打标</b>(§2.1)——
     * 前者的兜底是「手动补一句」,后者是「手动挂考点」。
     * <b>只凭 {@code quotaType} 分不出是哪一个。</b>
     * <p>
     * 本轮按 §8.3 那个响应示例逐字取 {@code ai_capture → manual_tag},
     * 于是 {@link #MANUAL_TEXT} 在这条路上<b>取不到</b>。
     * 🔴 <b>不自己往请求体上加一个字段去补这一格</b> —— 那是一次契约变更。
     * 已登记在 {@code M7} §契约增量 13,由 stage 3 与 {@code M2}(转写那一侧的扣点)一起看。
     */
    static String manualEntryOf(QuotaType type) {
        return switch (type) {
            case AI_CAPTURE -> MANUAL_TAG;
            case AI_ASK -> COPY_CONTEXT;
        };
    }
}
