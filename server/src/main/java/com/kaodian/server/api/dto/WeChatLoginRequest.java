package com.kaodian.server.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

/**
 * 微信 code 换会话 —— <b>关卡 2 后</b>。
 *
 * @param entry {@code mini_program} / {@code official_h5} / {@code website_qr}。
 *              <b>三条是三个不同的应用,appid 不通用</b>;传错的表现是 {@code errcode 40029}
 * @param state 回跳时原样带回的那个串。🔴 <b>服务端必须校验</b> ——
 *              不校验就能被塞进别人的 code(CSRF)。小程序入口不需要它
 */
public record WeChatLoginRequest(
        @NotBlank(message = "缺少入口类型") String entry,
        @NotBlank(message = "缺少 code") String code,
        String state,
        String deviceLabel,
        String referrer
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
