package com.kaodian.server.api.dto.common;

import com.kaodian.server.coverage.CoverageService.GroupCoverage;

import java.util.List;

/**
 * 树上的一个题型分组 —— {@code GET /api/v1/syllabus/tree} 的非叶子层
 * ({@code M3-骨架与覆盖度差集} §9.2)。
 *
 * <h2>🔴 两个计数由服务端给,端不从子树求和</h2>
 *
 * {@code U3.1} §2.1 那条「前端不做任何一次减法」在树上同样成立。
 * 端自己 {@code nodes.filter(...).length} 的那一版会在服务端口径改变时
 * <b>无声地</b>与顶上那三个数对不上 —— 而「对不上」正是这一屏唯一不能出的错。
 *
 * <p>🔴 {@link #untouchedCount} 同样是<b>数出来的</b>,不是
 * {@code nodes.size() − touchedCount} 减出来的:已归档节点在 {@code nodes} 里
 * (归档不是删除),它两边都不进,减法当场把它算进「没碰过」。
 *
 * @param whollyEmpty <b>整块空白</b> —— 这个题型下一个考点都没碰过。
 *                    决策记录 §2.5:能表达「整块题型都没碰过」是树相对扁平清单的<b>唯一优势</b>
 */
public record GroupDto(
        String code,
        String name,
        int touchedCount,
        int untouchedCount,
        boolean whollyEmpty,
        List<NodeDto> nodes
) {

    public static GroupDto from(GroupCoverage g) {
        return new GroupDto(
                g.code(), g.name(), g.touchedCount(), g.untouchedCount(), g.whollyEmpty(),
                g.nodes().stream()
                        // 🔴 已归档节点不进树的 nodes。它们【必须】走过 CoverageService 的那次遍历
                        //    (archivedCount 是数出来的,过滤在前就数不出来),但它们不上这一屏 ——
                        //    归档区是 §9.2 里一个独立的 archived 块,今天由 GET /syllabus/archived
                        //    单独供数。混进 nodes 里,端要么把归档的画成正常考点,要么自己再过滤一遍,
                        //    而那就是「同一个事实两个来源」。
                        .filter(n -> n.state() != com.kaodian.server.coverage.NodeState.ARCHIVED)
                        .map(NodeDto::from).toList());
    }
}
