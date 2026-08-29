package com.kaodian.server.api.dto;

/**
 * 绑定结果。
 *
 * <p>🔴 <b>{@code mergeToken} 非空时也绝不代表已经合并</b> —— 它只表示「可以走合并」。
 * docs/10 §7.1:合并默认不自动执行,必须用户显式发起 → 预览 → 二次确认 → 留痕 → 不可逆。
 * <p>
 * 响应里<b>没有</b>对方的 userId、手机号或任何可辨识信息:
 * 回一个「这个号已被 xxx 占用」等于让任何人拿别人的号试一下就能确认对方是不是用户。
 *
 * @param bound      绑上了吗
 * @param mergeToken 未绑上且可合并时的一次性令牌,5 分钟有效;否则为 {@code null}
 */
public record BindResponse(boolean bound, String mergeToken) {
}
