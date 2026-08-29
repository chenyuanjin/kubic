package com.kaodian.server.api.dto;

import java.util.List;

/**
 * 已归档的考点清单。
 *
 * <h2>为什么归档的考点需要一个自己的端点</h2>
 *
 * 因为它们<b>不在 {@code /api/syllabus/tree} 里</b> —— 归档的意思就是退出差集,
 * 树上不该再看见它们,否则「覆盖率」和「树上有几个格子」会对不上。
 * 可它们也没有消失:code 还在、记录还在、随时能接回来。
 * <p>
 * 一个看不见又删不掉的东西是最糟的状态,所以必须有一处能看见它们、能取消归档、
 * 能在把记录搬走之后真正删掉。就是这个端点。
 *
 * @param count 已归档的考点数
 * @param items 逐个列出,带各自还挂着几条记录
 */
public record ArchivedNodesResponse(
        int count,
        List<SyllabusNodeDto> items
) {
}
