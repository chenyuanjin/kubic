package com.kaodian.server.api.dto.common;

import com.kaodian.server.coverage.CoverageService.GroupCoverage;

import java.util.List;

/**
 * 题型层。
 *
 * <p>{@link #whollyEmpty} 是这一层存在的全部理由:决策记录 §2.5 写明,能表达
 * <b>「整块题型都没碰过」</b>是树相对扁平清单的<b>唯一优势</b>。界面上它由一个红色分组头承担,
 * 不用图表。所以这个布尔值不是「顺手算的」,它是三层结构的产出物。
 *
 * @param coveredCount  这个题型下有记录的考点数
 * @param recent5yCount 组内频次合计
 */
public record GroupDto(
        String code,
        String name,
        int nodeCount,
        int coveredCount,
        int recent5yCount,
        boolean whollyEmpty,
        List<NodeDto> nodes
) {
    public static GroupDto from(GroupCoverage g) {
        return new GroupDto(
                g.code(), g.name(), g.nodeCount(), g.coveredCount(), g.recent5yCount(), g.whollyEmpty(),
                g.nodes().stream().map(NodeDto::from).toList());
    }
}
