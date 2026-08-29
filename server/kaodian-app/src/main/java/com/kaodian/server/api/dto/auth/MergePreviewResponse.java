package com.kaodian.server.api.dto.auth;

import java.time.Instant;

/**
 * 合并预览 —— <b>只读,不产生副作用</b>(docs/10 §6.1)。
 *
 * @param movedRecordCount 会迁移多少条记录。⚪ <b>当前恒为 0,而且这不是 bug</b>:
 *                         行为层还是单用户的({@code Touch} 上没有 {@code user_id}),
 *                         今天的数据模型里没有「A 的记录」这个概念。
 *                         合并端点因此与微信登录一起关在关卡 2 后的开关之后
 * @param notice           必须原样展示给用户的那句话
 */
public record MergePreviewResponse(
        String fromLabel,
        String toLabel,
        int movedRecordCount,
        Instant expiresAt,
        String notice
) {

    /** 合并不可逆 —— 这句话在界面上不能被折叠、不能被省略。 */
    public static final String NOTICE =
            "合并不可逆。被并走的那个账号会被注销,它的手机号或微信将改绑到当前账号。";
}
