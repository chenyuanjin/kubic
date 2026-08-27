package com.kaodian.server.api.dto;

import com.kaodian.server.coverage.CoverageService.NodeCoverage;

import java.time.Instant;

/**
 * 树上的一个考点 —— 差集运算的最小单位。
 *
 * <h2>🔴 这里没有讲解、没有例题、没有解析</h2>
 *
 * 名称、频次、状态、碰过几次、最近哪天,五样。R-05 / 01 §2.2「不做教研」在接口形状上
 * 就是这个字段表 —— <b>不是不填,是不建这个位置</b>。学科判断外包给外部模型,
 * 这个产品从不产出「这题该怎么做」。
 *
 * @param recent5yCount 近五年出现次数,统计事实(docs/07),也是「值不值得补」的权重
 * @param state         枚举名,前端按它分支与配色
 * @param stateLabel    中文名,前端直接显示,不硬编码
 * @param touchCount    我在这个考点上有几条记录
 * @param latestAt      最近一次触达;从没碰过是 {@code null},界面显示「—」而不是某个默认日期
 */
public record NodeDto(
        String code,
        String name,
        int recent5yCount,
        String state,
        String stateLabel,
        int touchCount,
        Instant latestAt
) {
    public static NodeDto from(NodeCoverage n) {
        return new NodeDto(
                n.code(), n.name(), n.recent5yCount(),
                n.state().name(), n.state().label(),
                n.touchCount(), n.latestAt());
    }
}
