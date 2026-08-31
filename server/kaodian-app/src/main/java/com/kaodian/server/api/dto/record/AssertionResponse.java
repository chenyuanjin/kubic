package com.kaodian.server.api.dto.record;

import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.common.SummaryDto;
import java.time.Instant;

/**
 * {@code POST/DELETE /assertions} 的答复。
 *
 * <h2>为什么要把 {@code summary} 一起带回来</h2>
 *
 * 与 {@code RecordTagsResponse} 同一条:写完之后界面上那个大字要跟着刷新,
 * 让前端再发一次 {@code GET /coverage/summary} 会出现两次读取之间的空窗。
 * <p>
 * 🔴 而在这个端点上它还多担一件事:<b>让「覆盖率没有变」这件事当场可见</b>。
 * 用户按下「我已掌握」,最自然的预期是那个百分比往上跳一格 —— 它不会跳。
 * 把概览原样带回来,前端就能在同一次交互里说清楚发生了什么:
 * <b>盲区榜少了一行,已声明多了一个,覆盖率一个字没动</b>(决策记录 §5.2:补丁不是解法)。
 * 少了这一份数据,界面只能选择沉默,而沉默会被读成「没生效」。
 *
 * <h2>这个 record 里没有一个自由文本字段</h2>
 *
 * 考点的 code 与名字都在 {@code node} 里(它是 {@link NodeDetailDto},与
 * {@code GET /syllabus/nodes/{code}} 返回的是同一个形状)。这里不复述一遍 ——
 * 复述出来的那份迟早和它对不上,而且平白多两个要过红线扫描的位置。
 *
 * @param asserted      现在这个考点上有没有「我已掌握」。POST 之后恒为 {@code true},
 *                      DELETE 之后恒为 {@code false} —— 两个动作都是幂等的,
 *                      <b>返回的是最终状态,不是「这次改了没有」</b>
 * @param assertedAt    声明的时刻;取消之后是 {@code null}。
 *                      🔴 重复 POST <b>不刷新</b>它(见 {@code AssertionStore#put})
 * @param assertedTotal 全树声明了几个 —— 概览里单列的那一格({@code summary.asserted()} 同一个数)。
 *                      单独摆出来是因为按钮旁边那句「你已声明 N 个」用的就是它
 * @param node          这个考点的完整视图。<b>触达次数、状态、正确率都不会因为这次声明而变</b>
 * @param summary       写完之后的覆盖概览。<b>它的 percent 与写之前相同</b> —— 见上
 */
public record AssertionResponse(
        boolean asserted,
        Instant assertedAt,
        int assertedTotal,
        NodeDetailDto node,
        SummaryDto summary
) {
}
