package com.kaodian.server.auth;

import java.time.Instant;

/**
 * 一次合并的留痕 —— <b>合并不可逆,必须留痕</b>({@code R-33} / docs/technical/INDEX.md §5.2)。
 *
 * <p>留痕的用途不是「以后能撤回」——撤不回来。它的用途是:用户三个月后说
 * 「我的记录少了一半」时,能回答他那半边去了哪。
 *
 * @param fromUserId       被并走的账号。合并后它被标记为已注销,identity 全部改挂到 {@code to}
 * @param toUserId         留下的账号
 * @param movedRecordCount 迁移了多少条行为记录。
 *                         ⚪ <b>当前恒为 0</b> —— 行为层还没有 {@code user_id}(见 {@link AccountService})
 * @param mergedAt         合并时刻
 */
public record AccountMergeLog(
        long fromUserId,
        long toUserId,
        int movedRecordCount,
        Instant mergedAt
) {

    public AccountMergeLog {
        if (fromUserId == toUserId) {
            throw new IllegalArgumentException("合并必须发生在两个不同的账号之间");
        }
        if (mergedAt == null) {
            throw new IllegalArgumentException("合并必须有时刻");
        }
    }
}
