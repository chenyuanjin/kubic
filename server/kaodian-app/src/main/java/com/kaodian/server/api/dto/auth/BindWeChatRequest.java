package com.kaodian.server.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

/**
 * 已登录账号绑微信 —— <b>关卡 2 后</b>。
 *
 * <p>docs/10 §7.1:这是<b>最顺的那条路径,产品应主动引导走这条</b>。
 * 用户先用手机号登录、再在已登录状态下授权微信,两条通道从一开始就落在同一个账号上,
 * 完全不需要事后合并 —— 而合并是不可逆的、要二次确认的、会出错的那一条。
 */
public record BindWeChatRequest(
        @NotBlank(message = "缺少入口类型") String entry,
        @NotBlank(message = "缺少 code") String code,
        String state
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
