package com.kaodian.server.api.billing;

import com.kaodian.server.api.billing.dto.PrecheckRequest;
import com.kaodian.server.api.billing.dto.QuotaResponse;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.billing.QuotaPeriod;
import com.kaodian.server.billing.QuotaService;
import com.kaodian.server.billing.QuotaType;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 额度这一侧的两个端点({@code M7-额度与订单} §8.3)。
 *
 * <h2>🔴 这里<b>没有</b>一个扣减端点,而那是一条契约不是一次遗漏</h2>
 *
 * {@code 接口契约} §6.7.1:有扣减端点<b>客户端就能只调不扣、或只扣不调</b>。
 * 扣减只发生在 AI 端点内部、外部调用成功之后 —— 由 {@code M2} / {@code M4} 在它们自己的
 * 端点里调 {@link QuotaService#consume}。
 *
 * <p>🔴 只读令牌打 {@code /quota/**} 一律 {@code 403 READONLY_TOKEN},<b>不论方法</b>(锁 4)——
 * 那一道在 {@code ApiAuthFilter} 的前缀黑名单里,进不到这里。
 */
@RestController
@RequestMapping("/api/v1/quota")
public class QuotaController {

    private final QuotaService quotas;

    public QuotaController(QuotaService quotas) {
        this.quotas = quotas;
    }

    /** 两个池子的三个数一起返回 —— 缺 {@code granted} 前端就做不出换算行({@code U7.1} §三)。 */
    @GetMapping
    public QuotaResponse quota(CurrentSession session) {
        // 🔴 周期在最外层算一次(§2.5),往下都用这一个串。
        String periodYm = quotas.currentPeriod();
        return QuotaResponse.of(periodYm, quotas.provision(session.userId(), periodYm));
    }

    /**
     * 预检 —— 🔴 <b>只读不扣减</b>。
     *
     * <p>剩余为 0 → {@code 403 QUOTA_EXHAUSTED} 且<b>必须带 {@code details}</b>(§9.1),
     * 由 {@link QuotaExhaustedException} 与 {@link BillingExceptionHandler} 落地。
     */
    @PostMapping("/precheck")
    public PrecheckRequest.PrecheckResponse precheck(CurrentSession session,
                                                     @Valid @RequestBody PrecheckRequest request) {
        String periodYm = quotas.currentPeriod();
        QuotaType type = QuotaType.ofWireName(request.quotaType());
        QuotaPeriod period = quotas.peek(session.userId(), periodYm, type);
        if (!period.hasRemaining()) {
            throw new QuotaExhaustedException(period);
        }
        return PrecheckRequest.PrecheckResponse.of(period);
    }
}
