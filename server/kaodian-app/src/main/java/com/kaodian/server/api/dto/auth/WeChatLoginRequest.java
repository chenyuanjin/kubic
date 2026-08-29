package com.kaodian.server.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 微信 code 换会话 —— <b>关卡 2 后</b>。
 *
 * @param entry {@code mini_program} / {@code official_h5} / {@code website_qr}。
 *              <b>三条是三个不同的应用,appid 不通用</b>;传错的表现是 {@code errcode 40029}
 * @param state 回跳时原样带回的那个串。🔴 <b>服务端必须校验</b> ——
 *              不校验就能被塞进别人的 code(CSRF)。小程序入口不需要它
 * @param deviceLabel 与 {@link SmsVerifyRequest#deviceLabel()} 同一条纪律,上限见 {@link LoginFieldLimits}
 * @param referrer    同上
 */
public record WeChatLoginRequest(
        @NotBlank(message = "缺少入口类型") String entry,
        @NotBlank(message = "缺少 code") String code,
        String state,
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
