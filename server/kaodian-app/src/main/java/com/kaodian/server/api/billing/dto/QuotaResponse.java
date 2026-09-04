package com.kaodian.server.api.billing.dto;

import com.kaodian.server.billing.QuotaPeriod;
import com.kaodian.server.billing.QuotaType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/v1/quota} 的响应({@code M7-额度与订单} §8.3)。
 *
 * @param periodYm 🔴 <b>服务端返回的周期标识。</b>缺它前端就要自己算月份
 *                 ({@code U7.1} §2.4 明令禁止)
 * @param quotas   🔴 <b>键是闭集两值的对象,不是数组</b>。与 {@code plans[].quota} 同一个形状。
 *                 <b>这里和 {@code plans} 相反是有理由的</b>:档位数会变(加档只加值),
 *                 而计费维度<b>永远是两个</b> —— 第三维就是加油包,而
 *                 {@code 商业化与额度设计} §三 只允许两个。<b>对象把「维度不可改」写进了结构</b>
 */
public record QuotaResponse(String periodYm, Map<String, QuotaLineDto> quotas) {

    public static QuotaResponse of(String periodYm, Map<QuotaType, QuotaPeriod> periods) {
        Map<String, QuotaLineDto> quotas = new LinkedHashMap<>();
        for (QuotaType type : QuotaType.values()) {
            quotas.put(type.wireName(), QuotaLineDto.of(periods.get(type)));
        }
        return new QuotaResponse(periodYm, quotas);
    }

    /**
     * 一个池子的三个数。
     *
     * <p>🔴 <b>三个数一起返回</b>,缺 {@code granted} 前端就做不出换算行({@code U7.1} §三)。
     *
     * @param remaining 🔴 <b>派生值 {@code max(granted - used, 0)},不是存储列</b>(§6.3)
     */
    public record QuotaLineDto(int granted, int used, int remaining) {

        static QuotaLineDto of(QuotaPeriod period) {
            return period == null
                    ? new QuotaLineDto(0, 0, 0)
                    : new QuotaLineDto(period.granted(), period.used(), period.remaining());
        }
    }
}
