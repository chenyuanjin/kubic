package com.kaodian.server.api.dto.record;

import com.kaodian.server.tagging.TagState;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/records/{recordId}/tags/{tagId}/restore} 的成功响应 —— {@code M2} §9.2。
 *
 * @param tagId     原样回显
 * @param tagState  🔴 <b>取值域 = {@code TS-02} 一个值</b>。恢复的终点只有一个:
 *                  {@code discarded} 置回去的同时 {@code confirmedAt} 被清空,
 *                  所以它<b>回不到「已确认」</b> —— 用户还得再点一次
 */
public record RestoreTagResponse(

        @Size(max = 64)
        String tagId,

        @Size(max = 8)
        String tagState
) {

    /**
     * 恢复的终点 —— 写成常量而不是每次拼一次字符串。
     *
     * <p>🔴 这里写死 {@code TS-02} 不是图省事:如果哪天有人让 {@code restore} 保留
     * {@code confirmedAt},这个常量与 {@link TagState} 推出来的实际状态就会分叉,
     * 而分叉的那一刻正是覆盖度被一次<b>系统触发</b>的转移抬上去的那一刻({@code U2.2} §2.4)。
     * 判据把两者比一遍:{@code TagStateTest#restoreNeverRaisesCoverage}。
     */
    public static final String RESTORED_STATE = "TS-02";

    public static RestoreTagResponse of(String tagId) {
        return new RestoreTagResponse(tagId, RESTORED_STATE);
    }
}
