package com.kaodian.server.api.dto;

import com.kaodian.server.coverage.NodeState;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 五态分布里的一项。
 *
 * <h2>为什么是列表而不是 {@code {"EMPTY": 10, ...}} 这样的对象</h2>
 *
 * 一是<b>顺序</b>:五个状态在界面上的排列顺序(空白 → 仅接触 → 生疏 → 弱 → 稳)是产品语义,
 * JSON 对象的键顺序不是契约,列表的顺序是。
 * 二是<b>中文名的归属</b>:{@code label} 由服务端给,前端不硬编码「空白」「生疏」这些词 ——
 * 状态改名时改一处,不是改两端。
 *
 * @param state 枚举名,前端按它分支与配色
 * @param label 中文名,前端直接显示
 */
public record StateCountDto(String state, String label, int count) {

    /** 按 {@link NodeState} 的声明顺序摊平,缺的状态补 0 —— 前端能拿到稳定的五项。 */
    public static List<StateCountDto> from(Map<NodeState, Integer> distribution) {
        return Arrays.stream(NodeState.values())
                .map(s -> new StateCountDto(s.name(), s.label(), distribution.getOrDefault(s, 0)))
                .toList();
    }
}
