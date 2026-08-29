package com.kaodian.server.api.dto;

/**
 * 记一笔之后返回的东西:落下的那条记录 + 那个考点的<b>新状态</b>。
 *
 * <h2>为什么要把考点也带回来</h2>
 *
 * 记一笔的意义不在于「多了一条记录」,而在于「树上那个格子变色了」。
 * 不带回来,前端就得紧接着再请求一次详情或整棵树才能刷新那一格 —— 两次请求之间
 * 差集会被重算一遍,而「生疏」是按时间推的,理论上能算出不一样的结果。
 * <b>一次写入,一个一致的结果。</b>
 *
 * @param record 已落地的记录。id 与 occurredAt 由服务端定,以这里返回的为准
 * @param node   这一笔挂上去之后,该考点的最新覆盖视图
 */
public record CreateRecordResponse(
        TimelineItemDto record,
        NodeDetailDto node
) {
}
