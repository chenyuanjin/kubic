package com.kaodian.server.api.billing;

import com.kaodian.server.api.billing.dto.QuotaExhaustedDetails;
import com.kaodian.server.billing.QuotaPeriod;
import com.kaodian.server.billing.QuotaType;

/**
 * {@code 403 QUOTA_EXHAUSTED} —— 🔴 <b>必须带 {@code details}</b>({@code M7-额度与订单} §9.1 / §8.3)。
 *
 * <h2>为什么它不是一个 {@code ApiException}</h2>
 *
 * {@code B0} 的 {@code ApiException} <b>没有装 {@code details} 的地方</b>,而
 * {@code ApiExceptionHandler} 出的一律是三参 {@code ApiError}({@code details} 恒 {@code null})。
 * {@code ApiError} 那一侧通道是留着的(它的类注释逐字点了 {@code QUOTA_EXHAUSTED} 这一处),
 * <b>缺的是从异常到那个通道的路</b>。
 * <p>
 * 🔴 <b>本模块不去改 {@code ApiException} / {@code ApiExceptionHandler}</b> ——
 * 那两个文件是 {@code B0} 的横切件,{@code M7}(KUBI-101)共同约束第 2 条明写「改包络形状要停手报回」。
 * 所以这里另起一个异常 + 一个只认它的 {@code @RestControllerAdvice}
 * ({@link BillingExceptionHandler}),落在本模块自己的包里,<b>{@code B0} 的文件一个字不动</b>。
 *
 * <p>⚠ {@code M2}(打标管线)与 {@code M4}({@code POST /ai/ask})在扣减耗尽时要抛的是同一个 403。
 * 它们可以直接抛本类,<b>不必各造一个</b>。已回本议题登记为 {@code B0} 的一处缺口。
 */
public class QuotaExhaustedException extends RuntimeException {

    private final QuotaExhaustedDetails details;

    public QuotaExhaustedException(QuotaType type, String periodYm) {
        super("这个月的额度用完了 —— 手动那条路一直开着。");
        this.details = QuotaExhaustedDetails.of(type, periodYm);
    }

    public QuotaExhaustedException(QuotaPeriod period) {
        this(period.quotaType(), period.periodYm());
    }

    public QuotaExhaustedDetails details() {
        return details;
    }
}
