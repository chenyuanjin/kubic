package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.SummaryDto;

/**
 * 一次题型编辑之后返回的东西。理由同 {@link NodeEditResponse}:
 * 编辑骨架会动覆盖概览,一次写入就该给出一个一致的结果。
 *
 * @param group   改完之后那个题型的最新样子
 * @param summary 改完之后整棵树的覆盖概览
 */
public record GroupEditResponse(
        SyllabusGroupDto group,
        SummaryDto summary
) {
}
