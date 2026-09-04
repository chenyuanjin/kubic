package com.kaodian.server.api.billing;

import com.kaodian.server.api.dto.common.ApiError;
import com.kaodian.server.api.dto.common.ErrorCode;
import com.kaodian.server.billing.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 商业化这一侧的两个出口 —— 🔴 <b>{@code B0} 的 {@code ApiExceptionHandler} 一个字不动</b>。
 *
 * <p>只认两个异常,其余全部照旧落到 {@code B0} 那个兜底 advice 上。
 * 优先级设成最高,是为了让 {@code QuotaExhaustedException} 命中这里而不是那边的
 * {@code @ExceptionHandler(Exception.class)} 兜底 —— 兜底会把它变成一个 500。
 *
 * <p>形状仍然是 {@link ApiError},{@code traceId} 的生成方式与 {@code B0} 逐字一致
 * (12 位无横线 UUID 前缀)。⚠ 这一处是有意的重复:抽出来共用要改 {@code B0} 的文件,
 * 而那正是不许做的事;<b>重复的是四行,不是一个语义</b>。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BillingExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BillingExceptionHandler.class);

    /**
     * {@code 403 QUOTA_EXHAUSTED} + {@code details}。
     *
     * <p>🔴 {@code details} 里<b>不出现任何指向购买的东西</b> —— 形状由
     * {@link com.kaodian.server.api.billing.dto.QuotaExhaustedDetails} 逐字段定死,理由写在那里。
     */
    @ExceptionHandler(QuotaExhaustedException.class)
    public ResponseEntity<ApiError> handle(QuotaExhaustedException ex) {
        String traceId = newTraceId();
        log.info("[{}] 请求被拒绝 code={}", traceId, ErrorCode.QUOTA_EXHAUSTED.name());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("quotaType", ex.details().quotaType());
        details.put("periodYm", ex.details().periodYm());
        details.put("manualEntry", ex.details().manualEntry());
        return ResponseEntity.status(ErrorCode.QUOTA_EXHAUSTED.httpStatus())
                .body(new ApiError(ErrorCode.QUOTA_EXHAUSTED.name(), ex.getMessage(), traceId, details));
    }

    /**
     * 上游够不着 → {@code 502 SERVER_ERROR}(§8.2 / §4.5 的「上游下单失败 / 超时」那一档)。
     *
     * <p>🔴 <b>它必须与 {@code 422 RECEIPT_INVALID} 分得开</b>,而这条要求<b>靠 HTTP 状态分类就满足了</b>,
     * 不需要额外约定({@code 接口契约} §8.5 原文)。端在这一档的主按钮是「再试一次」,
     * 在 {@code 422} 那一档是「没能确认」——两句话两个动作。
     *
     * <p>消息不带 {@code ex.getMessage()} 进日志之外的地方:上游返回的报文可能带着任何东西。
     */
    @ExceptionHandler(PaymentGateway.PaymentGatewayException.class)
    public ResponseEntity<ApiError> handle(PaymentGateway.PaymentGatewayException ex) {
        String traceId = newTraceId();
        log.error("[{}] 支付上游够不着", traceId, ex);
        return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_GATEWAY)
                .body(new ApiError(ErrorCode.SERVER_ERROR.name(),
                        "支付通道暂时不通,过一会儿再试一次。", traceId));
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
