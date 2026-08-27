package com.kaodian.server.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

/**
 * 验证码换令牌 —— <b>通过的那一刻,号码没见过就建号、见过就登进去</b>(docs/13 §1.7)。
 *
 * @param deviceLabel 这台设备叫什么。可空 —— <b>认不出设备不能成为登不进去的理由</b>
 * @param referrer    从哪个入口来的。⚪ 只在建号时记进注册流水,给「陌生 vs 熟人」的人工判定留线索
 */
public record SmsVerifyRequest(
        @NotBlank(message = "请填写手机号") String phone,
        @NotBlank(message = "请填写验证码") String code,
        String purpose,
        String deviceLabel,
        String referrer
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
