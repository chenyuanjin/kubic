package com.kaodian.server.api.dto.auth;

/**
 * 注销的响应。
 *
 * <h2>🔴 {@code exportHint} 是契约要求的,不是客套话</h2>
 *
 * docs/10 §6.1:<b>「{@code DELETE /account} 的响应里必须带导出入口提示」</b>({@code 1.3.1.3.3})。
 * 01 §2.6 的开放性承诺是完整导出;一个删完才想起来「我的记录呢」的用户,
 * 是这条承诺最直接的反例。
 *
 * <h2>⚪ 这里没有「N 天后彻底删除」那句话,是故意的</h2>
 *
 * 服务端数据的<b>硬删时点未定</b>。{@code 1.3.1.3.2} 的原文是「注销即删除」,
 * 而「软删 → T+7 硬删」是行业惯例 —— 后者把一条已写死的合规判据改松了。
 * 采用哪个由 {@code L-A5} 的律师稿定(docs/10 §6.1)。
 * <p>
 * <b>在它定下来之前,界面上不许出现任何具体天数。</b>
 * 写「7 天内清干净」等于替法务做决定,而且是用一句用户读起来最安心的话做的。
 *
 * @param revokedSessions 顺带吊销了几条会话
 */
public record DeactivateResponse(int revokedSessions, String exportHint) {

    public static final String EXPORT_HINT =
            "账号已注销,全部登录状态已失效。如果还没导出过你的记录,现在无法再导出了 —— "
                    + "导出入口在「我的 · 数据导出」,支持 Markdown / CSV / JSON。";
}
