package com.kaodian.server.api.dto;

import java.util.List;

/**
 * 时间线。
 *
 * <p>{@code total} 给的是行为层记录总数,{@code returned} 是本次截取的条数 ——
 * 前端凭这两个数就能显示「共 128 条,显示最近 50 条」,不需要再请求一次计数。
 *
 * <p>docs/10 §6.2 定的是 cursor 分页,那是阶段 2 的事。现在全量在一个本地 JSON 文件里,
 * 先用一个 {@code limit} 截断:<b>把游标编码、游标失效、游标兼容这一整套先欠着</b>,
 * 等真有需要分页的数据量时再还。
 */
public record TimelineResponse(
        int total,
        int returned,
        List<TimelineItemDto> items
) {
}
