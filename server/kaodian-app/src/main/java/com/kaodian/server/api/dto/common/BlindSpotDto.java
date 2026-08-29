package com.kaodian.server.api.dto.common;

import com.kaodian.server.coverage.CoverageService.NodeCoverage;

/**
 * 盲区清单里的一行 —— 「先补这几个」。
 *
 * @param rank          名次,从 1 开始。服务端给,前端不靠数组下标 —— 分页或过滤后下标就不是名次了
 * @param blindScore    排序分 = 近五年频次 × 状态权重。两个因子都在能力边界内,
 *                      没有任何一项来自「判断你答得对不对」
 * @param recent5yCount 频次因子,单独给出来是为了让排序<b>可解释</b>:
 *                      用户能自己看出「它排前面是因为考得多且我没碰过」,而不是信一个黑盒分数
 */
public record BlindSpotDto(
        int rank,
        String code,
        String name,
        String groupCode,
        String groupName,
        int recent5yCount,
        String state,
        String stateLabel,
        double blindScore
) {
    public static BlindSpotDto of(int rank, NodeCoverage n) {
        return new BlindSpotDto(
                rank, n.code(), n.name(), n.groupCode(), n.groupName(), n.recent5yCount(),
                n.state().name(), n.state().label(), n.blindScore());
    }
}
