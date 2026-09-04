package com.kaodian.server.api.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;

/**
 * 树上的一个叶子考点 —— {@code GET /api/v1/syllabus/tree} 里最深那一层
 * ({@code M3-骨架与覆盖度差集} §9.2)。
 *
 * <h2>🔴 这里没有 {@code state} / {@code stateLabel}</h2>
 *
 * 上一版每个节点带着五态的名字与中文标签。新五态
 * ({@code UNTOUCHED} / {@code TOUCHED} / {@code ASSERTED} / {@code ARCHIVED} / {@code GONE})
 * 是<b>服务端的推导中间量</b>,不是一个上屏的东西:树上要显示的事实只有
 * 「碰过几次」与「说过会了没」两件,它们各自有自己的字段。
 * <p>
 * 把状态名送出去会立刻长出第二条渲染路径 —— 端可以选择看 {@code state},
 * 也可以选择看 {@code touchCount},而两者在「断言过又碰过」那一格上给出不同的画面。
 * <b>一个事实一个来源。</b>
 *
 * <p>中文标签更不能来自服务端:{@code web/} 那道 {@code capability-boundary-scan.mjs}
 * 守的是<b>文案</b>,而服务端下发的文案绕过它。
 *
 * @param recent5yCount 近五年出现次数。{@code null} → key 不出现,界面写
 *                      「这个考点没有出现次数记录」;<b>不返回 0 冒充</b>(§二)
 * @param touchCount    碰过几次。🔴 <b>恒在</b>,没碰过就是 {@code 0} ——
 *                      界面据此写「你没碰过」,<b>不写「碰过 0 次」</b>
 * @param asserted      用户按过「我已经会了」这个开关吗。<b>原始开关状态</b>,
 *                      不是「它现在算不算没碰过」——后者是 {@code touchCount == 0}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeDto(
        String code,
        String name,
        Integer recent5yCount,
        int touchCount,
        boolean asserted
) {

    // 🔴 没有 archived 字段:归档节点【不在这一层】(见 GroupDto.from 的过滤)。
    //    留一个恒为 false 的字段,下一个人会拿它去写「树上把归档的灰掉」——
    //    而那正是 R-49 要挡的那个开关的另一种形态。
    public static NodeDto from(NodeCoverage n) {
        return new NodeDto(n.code(), n.name(), n.recent5yCount(), n.touchCount(), n.asserted());
    }
}
