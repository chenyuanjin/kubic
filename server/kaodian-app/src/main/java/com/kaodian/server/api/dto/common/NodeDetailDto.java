package com.kaodian.server.api.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.coverage.NodeState;

import java.time.Instant;
import java.util.List;

/**
 * 考点详情 —— {@code GET /api/v1/syllabus/nodes/{id}}
 * ({@code M3-骨架与覆盖度差集} §9.4)。
 *
 * <h2>字段分三组,分组本身是契约</h2>
 *
 * <table border="1">
 *   <caption>三组字段分别关于谁</caption>
 *   <tr><th>组</th><th>字段</th><th>关于谁</th></tr>
 *   <tr><td>骨架事实</td><td>{@code recent5yCount}</td><td>关于<b>真题</b></td></tr>
 *   <tr><td>我的事实</td><td>{@code touchCount} · {@code lastTouchAt} · {@code sourceNames}</td>
 *       <td>关于<b>用户</b></td></tr>
 *   <tr><td>节点自身</td><td>{@code nodeId} · {@code name} · {@code path} · {@code level}
 *       · {@code archived} · {@code asserted}</td><td>——</td></tr>
 * </table>
 *
 * <h2>🔴 没有的字段,是<b>结构上</b>没有,不是「查的时候不查」</h2>
 *
 * 题干 · 讲解 / 解析 / 例题 / 相关课程 · 难度 · <b>掌握度 / 正确率 / 得分 / 星级</b> ·
 * 置信度 / 匹配分 · 相似考点 · <b>任何比值</b> · <b>任何天数</b>。
 * <p>
 * 上一版这里有 {@code practiced} / {@code correct} / {@code accuracy} 三个。
 * 理由不是「用户自填就没事」:<b>字段在响应里,第二个消费方就会把它当成产品记的分</b>,
 * 而 {@code U3.4} §2.6 逐字禁掉了「置信度 / 匹配分」的屏上形态 —— 掌握度禁得更早。
 * 同一段还去掉了 {@code state} / {@code stateLabel}:新五态是服务端的推导中间量,
 * 详情屏要显示的事实由字段各自承担。
 *
 * <h2>🔴 时间是绝对时刻,不是「多久前」</h2>
 *
 * {@code lastTouchAt} 带时区。「今天 / 昨天 / n 天前 / 超过一年前」<b>全部由端算</b> ——
 * 服务端返回一个天数,{@code U3.8} §2.4 那条「天数与盲区数不许出现在同一句话里」
 * 就失去了唯一的结构性保障。
 *
 * @param level      🔴 恒为 {@code 3}。⚠️ 骨架今天是<b>两层</b>({@code Group → Node}),
 *                   而这个端点只解析得到叶子 —— 叶子就是进分母的那一层。
 *                   骨架长出中间层时这里要跟着改,契约形状不变
 *                   (ponytail: 常量 3,骨架长出 level 列时改成读它)
 * @param archived   🔴 与「不存在」<b>必须两档</b>:已归档返 {@code 200} 且内容照常返回
 *                   (归档不是删除),不存在才是 {@code 404 NODE_NOT_FOUND}。
 *                   合成一档,用户会以为自己的记录被删了
 * @param asserted   用户按过「我已经会了」这个开关吗 —— <b>原始开关状态</b>。
 *                   一个断言过之后又碰过的节点这里仍然是 {@code true}(断言行保留不删)
 * @param touchCount 🔴 <b>恒在</b>,值 {@code 0} 时界面写「你没碰过」,<b>不写「碰过 0 次」</b>;
 *                   此时 {@code lastTouchAt} 与 {@code sourceNames} 的 key <b>不出现</b>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeDetailDto(
        String nodeId,
        String name,
        String path,
        int level,
        boolean archived,
        boolean asserted,
        Integer recent5yCount,
        int touchCount,
        Instant lastTouchAt,
        List<String> sourceNames
) {

    /** 骨架今天只有 {@code Group → Node} 两层,而这个端点只解析得到叶子。 */
    private static final int LEAF_LEVEL = 3;

    public static NodeDetailDto from(NodeCoverage n) {
        return new NodeDetailDto(
                n.code(), n.name(), BlindSpotDto.path(n), LEAF_LEVEL,
                n.state() == NodeState.ARCHIVED, n.asserted(),
                n.recent5yCount(), n.touchCount(),
                n.lastTouchAt(),
                // 没碰过时 sourceNames 的 key 不出现 —— 空数组是「数过了,是零」那一档,
                // 而来源名不存在「有 0 个来源」这回事:没碰过就是没这一项。
                n.sourceNames().isEmpty() ? null : n.sourceNames());
    }
}
