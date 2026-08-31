package com.kaodian.server.api.dto.record;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST/DELETE /assertions} 的请求体 —— <b>只接受一个考点 code。</b>
 *
 * <h2>🔴 契约原文就是「body 只接受 {@code nodeId}」(docs/技术架构 §6.4)</h2>
 *
 * 与 {@link MountTagRequest} 同一副形状、同一套理由,只是那边是把记录挂到考点上,
 * 这边是给考点贴一句「我会了」。两条写入路径上都<b>没有一个能装下自由文本的位置</b>:
 * <ol>
 *   <li>字段表上只有 {@code nodeCode},<b>没有能写一句备注、一个理由、一段笔记的分量</b>。
 *       「我已掌握」是一个布尔事实,不是一条笔记 —— 给它配个 {@code note} 字段,
 *       那个字段一年后装的就是题干({@code R-01})</li>
 *   <li>{@link #rejectUnknownField} —— 未定义字段一律 400,且与
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES} 那行配置无关。少了它,
 *       {@code {"note":"这题我在抖音看过"}} 会被静默忽略然后返回 200,
 *       <b>调用方以为它生效了</b></li>
 * </ol>
 * 第三道在控制器:{@code AssertionController} 会去(未归档的)树里查一遍,查不到就拒。
 *
 * <h2>为什么叫 {@code nodeCode} 而不是契约里的 {@code nodeId}</h2>
 *
 * 与 {@link MountTagRequest} 逐字同理:骨架层的主键是 {@code code} 不是数字 id,
 * 而写入侧已有的两个字段({@code CreateRecordRequest.nodeCode}、{@code MountTagRequest.nodeCode})
 * 都叫这个名字。<b>同一个东西在三个端点上叫两个名字,前端就得记住哪个端点用哪个</b> ——
 * 那是纯粹的出错来源。契约里的 {@code nodeId} 与这里的 {@code nodeCode} 指同一样东西。
 *
 * @param nodeCode 声明掌握哪个考点。必须是骨架树里<b>未归档</b>的 code
 */
public record AssertionRequest(

        @NotBlank(message = "必须给出考点 code —— 只能从树里选,不能新建")
        @Size(max = 64, message = "考点 code 最长 64 个字符")
        String nodeCode
) {

    /**
     * 🔴 未定义字段一律拒绝。
     *
     * <p><b>{@code value} 收下就丢</b>:它是用户送来的原文,可能就是一整段题干。
     * 异常里只带字段名(决策记录 §2.2 不碰内容)。
     */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
