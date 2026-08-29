package com.kaodian.server.api.dto.auth;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

/**
 * 已登录账号绑手机号 —— <b>关卡 2 后</b>(docs/10 §6.1)。
 *
 * <p>它仍然要走一次验证码({@code purpose=bind}),而不是「登录了就能随便绑」:
 * 绑定的是<b>一个能用来登录的凭证</b>,不验证等于允许给自己绑上别人的号。
 */
public record BindPhoneRequest(
        @NotBlank(message = "请填写手机号") String phone,
        @NotBlank(message = "请填写验证码") String code
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
