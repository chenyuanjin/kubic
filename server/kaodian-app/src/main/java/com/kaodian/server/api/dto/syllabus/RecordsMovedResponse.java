package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.SummaryDto;

/**
 * 记录搬迁的结果。
 *
 * <p>{@link #movedCount} 必须回给用户,因为这是<b>唯一一个会动到历史记录归属的操作</b> ——
 * 「搬了几条」是用户复核这次操作的全部依据。搬迁只改 {@code nodeCode},
 * 时间戳、来源名、做题数原样保留,记录总数不变。
 *
 * @param fromNodeCode 记录原来挂在哪
 * @param toNodeCode   现在挂到哪
 * @param movedCount   搬走了几条
 * @param summary      搬完之后整棵树的覆盖概览 —— 两个考点的状态都会跟着变
 */
public record RecordsMovedResponse(
        String fromNodeCode,
        String toNodeCode,
        int movedCount,
        SummaryDto summary
) {
}
