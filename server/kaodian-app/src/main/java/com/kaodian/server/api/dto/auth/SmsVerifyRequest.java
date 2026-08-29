package com.kaodian.server.api.dto.auth;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 验证码换令牌 —— <b>通过的那一刻,号码没见过就建号、见过就登进去</b>(docs/13 §1.7)。
 *
 * <h2>🔴 两个自由文本字段都有上限,这个上限是防内容夹带的</h2>
 *
 * {@code deviceLabel} 与 {@code referrer} 是<b>登录链路上仅有的两个用户可写自由文本</b>,
 * 而且它们会被原样写进 {@code tokens.json} / {@code signups.json}。
 * R-01(线上库不存在能装下题干的字段)在记录链路上由 {@link CreateRecordRequest} 三道锁守着,
 * 这两个字段是它在登录链路上的对应物 —— 上限的数与理由见 {@link LoginFieldLimits}。
 *
 * @param deviceLabel 这台设备叫什么。可空 —— <b>认不出设备不能成为登不进去的理由</b>,
 *                    所以拼不出短标签就别传,而不是传一整条 User-Agent
 * @param referrer    从哪个入口来的。⚪ 只在建号时记进注册流水,给「陌生 vs 熟人」的人工判定留线索
 */
public record SmsVerifyRequest(
        @NotBlank(message = "请填写手机号") String phone,
        @NotBlank(message = "请填写验证码") String code,
        String purpose,
        @Size(max = LoginFieldLimits.MAX_DEVICE_LABEL,
                message = "设备名最长 40 个字符 —— 它是个名字,不是放内容的地方")
        String deviceLabel,
        @Size(max = LoginFieldLimits.MAX_REFERRER,
                message = "渠道标识最长 64 个字符 —— 它是个标识,不是放内容的地方")
        String referrer
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
