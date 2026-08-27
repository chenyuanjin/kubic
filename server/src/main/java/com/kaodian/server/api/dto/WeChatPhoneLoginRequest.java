package com.kaodian.server.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

/**
 * 小程序一步登录 —— <b>同一次交互里同时拿到微信身份与手机号</b>。
 *
 * <p>这是「联合登录」最顺的形态:两条通道从第一天起就落在同一个账号上,
 * {@code R-33}(行为层被拆两半)根本不会发生 —— 不需要事后引导补绑,更不需要合并。
 *
 * <h2>🔴 两个 code 是两回事,不能只传一个</h2>
 *
 * <table border="1">
 *   <caption>两个 code</caption>
 *   <tr><th>参数</th><th>从哪来</th><th>换什么</th><th>花钱吗</th></tr>
 *   <tr><td>{@code loginCode}</td><td>{@code wx.login}</td><td>openid / unionid</td><td><b>免费</b></td></tr>
 *   <tr><td>{@code phoneCode}</td><td>{@code bindgetphonenumber} 回调</td><td>手机号</td>
 *       <td><b>0.03 元/次</b></td></tr>
 * </table>
 *
 * 服务端<b>先用免费那个换出 openid、拿它做频控,再去调收费那个</b> ——
 * 与验证码四道闸同一条:<b>拦要拦在花钱那一步之前</b>(docs/13 §1.8)。
 *
 * <p>两个 code <b>各自 5 分钟有效、各自单次消费</b>。
 *
 * @param referrer ⚪ 只在建号时记进注册流水,给「陌生 vs 熟人」的人工判定留线索
 */
public record WeChatPhoneLoginRequest(
        @NotBlank(message = "缺少登录 code") String loginCode,
        @NotBlank(message = "缺少手机号 code") String phoneCode,
        String deviceLabel,
        String referrer
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
