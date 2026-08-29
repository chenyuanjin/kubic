package com.kaodian.server.api.dto.record;

import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.common.SummaryDto;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 挂载 / 确认 / 丢弃之后的答复:<b>这条记录现在挂着什么 + 覆盖度变成了多少。</b>
 *
 * <h2>为什么每次都把概览一起带回来</h2>
 *
 * 与 {@link CreateRecordResponse} / {@link RecordDeletedResponse} 完全对称,而在这里更要紧:
 * <b>丢弃一条标签唯一的可见后果就是覆盖度掉一格</b>({@code P1-7}「可见但不计覆盖度」)。
 * 不带回来,用户点完「丢弃」看到的是列表上多了个灰条,而那个真正发生的变化在另一屏上。
 * <p>
 * 顺带堵掉「前端自己减一」这种写法:分子是按<b>记录去重</b>数出来的,同一个考点上还挂着
 * 别的记录时丢弃一条根本不会让它掉出覆盖度。前端算不出这件事,也不该去算。
 *
 * @param node    受影响的那个考点的最新覆盖视图;考点已不在树里时为 {@code null}
 * @param summary 整棵树的覆盖概览
 */
public record RecordTagsResponse(

        @Size(max = 64)
        String recordId,

        List<TagDto> tags,
        NodeDetailDto node,
        SummaryDto summary
) {
}
