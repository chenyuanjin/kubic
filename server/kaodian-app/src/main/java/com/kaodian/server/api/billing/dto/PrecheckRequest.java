package com.kaodian.server.api.billing.dto;

import com.kaodian.server.billing.QuotaPeriod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/quota/precheck} 的请求体({@code M7-额度与订单} §8.3)。
 *
 * <p>🔴 <b>只读不扣减</b>({@code 接口契约} §6.7)。有扣减端点客户端就能<b>只调不扣、或只扣不调</b>。
 *
 * @param quotaType {@code ai_capture} / {@code ai_ask}
 */
public record PrecheckRequest(@NotBlank @Size(max = 32) String quotaType) {

    /** 剩余不为 0 时的 {@code 200} 响应 —— 与请求同一个类里,免得两个文件各写一半。 */
    public record PrecheckResponse(String quotaType, int granted, int used, int remaining, String periodYm) {

        public static PrecheckResponse of(QuotaPeriod period) {
            return new PrecheckResponse(period.quotaType().wireName(), period.granted(),
                    period.used(), period.remaining(), period.periodYm());
        }
    }
}
