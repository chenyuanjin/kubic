package com.kaodian.server.api.dto.auth;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

/**
 * 发验证码。
 *
 * <p>🔴 <b>{@code captchaTicket} 与 {@code captchaRandstr} 两个都必须接</b>。
 * 只接票据是接入腾讯云验证码时最常见的一个错 —— 校验会 100% 失败,
 * 然后往往被人「修」成失败也放行,于是第①道闸就没了(docs/13 §1.8)。
 *
 * @param phone         手机号,允许带空格与 {@code +86}
 * @param purpose       {@code login} 或 {@code bind}。防跨场景重放
 * @param captchaTicket 滑块票据
 * @param captchaRandstr 滑块随机串
 */
public record SmsSendRequest(
        @NotBlank(message = "请填写手机号") String phone,
        String purpose,
        String captchaTicket,
        String captchaRandstr
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
