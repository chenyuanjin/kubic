package com.kaodian.server.billing;

import com.kaodian.server.api.support.ApiException;

/**
 * 两个额度池 —— <b>互不借用</b>({@code M7-额度与订单} §2.1,{@code 商业化与额度设计} §三)。
 *
 * <h2>🔴 为什么恰好两个,而且第三个不是「以后再加」</h2>
 *
 * 计费维度<b>永远是两个</b>:第三维就是加油包,而 {@code 商业化与额度设计} §三 只允许两个。
 * {@code GET /quota} 的 {@code quotas} 因此是一个<b>对象</b>而不是数组
 * (§8.3)——档位数会变(加档只加值),维度不会,<b>对象把「维度不可改」写进了结构</b>。
 *
 * <p>🔴 「互不借用」也是结构事实而不是纪律:{@code QuotaStore} 的每一个方法都带着
 * 一个 {@code QuotaType} 参数,没有任何一个签名能表达「从另一个池子里借」。
 */
public enum QuotaType {

    /** 语音转写 + 图片识别打标。 */
    AI_CAPTURE("ai_capture"),

    /** AI 代发提问({@code POST /ai/ask})。 */
    AI_ASK("ai_ask");

    private final String wireName;

    QuotaType(String wireName) {
        this.wireName = wireName;
    }

    /** JSON 上的取值。枚举名是 {@code AI_CAPTURE},线上是 {@code ai_capture}。 */
    public String wireName() {
        return wireName;
    }

    /**
     * 请求参数 / 请求体 → 枚举。
     *
     * <p>🔴 大小写与枚举名都<b>不接受</b>:线上只有 {@code ai_capture} / {@code ai_ask} 两个串。
     * 顺手认一个 {@code AI_CAPTURE},契约上就凭空多了一组取值,而端会开始各写各的。
     *
     * <p>报错走 {@link ApiException#unknownValue} —— 回声截断,理由见那里。
     */
    public static QuotaType ofWireName(String s) {
        if (s != null) {
            for (QuotaType t : values()) {
                if (t.wireName.equals(s.trim())) {
                    return t;
                }
            }
        }
        throw ApiException.unknownValue("VALIDATION_FAILED", "额度类型(只认 ai_capture / ai_ask)", s);
    }
}
