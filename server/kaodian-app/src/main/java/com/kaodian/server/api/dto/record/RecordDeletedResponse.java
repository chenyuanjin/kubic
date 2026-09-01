package com.kaodian.server.api.dto.record;

import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.common.SummaryDto;
import jakarta.validation.constraints.Size;

/**
 * 删掉一条记录之后的答复:删的是哪条 + <b>那个考点与整棵树的新状态</b>。
 *
 * <h2>为什么要把考点和概览一起带回来</h2>
 *
 * 与 {@link CreateRecordResponse} 完全对称:删一笔的意义不在于「少了一条记录」,
 * 而在于树上那个格子<b>可能退回去了</b> —— 稳→弱、弱→仅接触、甚至回到空白,
 * 覆盖度那个百分比跟着降一格。
 * <p>
 * 不带回来,前端就得紧接着再请求一次详情或整棵树;而两次请求之间差集会被重算一遍,
 * 「生疏」是按时间推的,理论上能算出不一样的结果。<b>一次写入,一个一致的结果。</b>
 *
 * <h2>契约里的「级联删标签」现在有对应物了</h2>
 *
 * docs/technical/INDEX.md §6.2 对这个端点的约束原文是「<b>级联删标签</b>,触发覆盖层重算」。
 * 后半句自动成立(见 {@code TouchStore#delete});前半句由
 * {@code RecordController#delete} 里那一句 {@code RecordTagStore.deleteByRecord} 兑现 ——
 * <b>这个响应体一个字都没改</b>,当初那段注释里「到时候要动的不是这个响应体」的判断是对的。
 * <p>
 * 需要提一句的是<b>它为什么不影响这里的两个数</b>:记录一删,它的主标签就跟着不存在了
 * (主标签由记录推出来,见 {@code RecordTag#effectiveTagsOf}),
 * 所以 {@code node} 与 {@code summary} 在标签行删不删得掉之前就已经是对的。
 * 级联删掉的是那些<b>不会再被任何人读到、但会一直躺在文件里</b>的孤儿行。
 *
 * @param id      被删掉的记录 id
 * @param node    这一笔拿掉之后,该考点的最新覆盖视图。<b>考点已不在树里时为 {@code null}</b> ——
 *                历史记录可能挂在一个后来被删掉的考点上,那不该让删除操作 500
 * @param summary 删完之后整棵树的覆盖概览
 */
public record RecordDeletedResponse(

        // 上限跟着 CreateRecordRequest.nodeCode 的 64 走。它是服务端签发的 t-<uuid>(38 字符),
        // 永远短于它 —— 这个注解不参与校验(响应体不过 Validator),它是形状声明:
        // 这个位置放的是一个 id,不是一段内容(R-01)。
        @Size(max = 64)
        String id,

        NodeDetailDto node,
        SummaryDto summary
) {
}
