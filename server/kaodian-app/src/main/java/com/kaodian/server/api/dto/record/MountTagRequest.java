package com.kaodian.server.api.dto.record;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /records/{id}/tags} 的请求体 —— <b>只接受一个考点 code。</b>
 *
 * <h2>🔴 没有 name、没有 label、没有 tag —— R-07 在接口层的实现</h2>
 *
 * docs/10 §6.3 原文:「body <b>只接受 {@code nodeId}</b>,不接受 {@code name}。
 * <b>从树里选,不能新建。</b>」并且:「只要 API 上没有传入自由文本标签的通道,
 * 自由生成的考点就进不了库 —— 无论模型输出什么。」
 * <p>
 * 这条在这里有两个抓手:
 * <ol>
 *   <li>字段表上只有 {@code nodeCode} 一个位置,<b>没有能装下一个自己起的标签名的分量</b></li>
 *   <li>{@link #rejectUnknownField} —— 未定义字段一律 400,
 *       且与 {@code FAIL_ON_UNKNOWN_PROPERTIES} 那行配置无关。
 *       少了它,{@code {"name":"我自己想的考点"}} 会被静默忽略然后返回 200,
 *       <b>调用方以为它生效了</b></li>
 * </ol>
 * 第三道在服务层:{@code TaggingService.mount} 会去(未归档的)树里查一遍,查不到就拒。
 *
 * <h2>为什么叫 {@code nodeCode} 而不是契约里的 {@code nodeId}</h2>
 *
 * 骨架层的主键是 {@code code} 不是数字 id({@code Syllabus} 类注释:「🔴 code 是主键,名字不是」),
 * 而写入侧已有的那个字段({@code CreateRecordRequest.nodeCode})就叫这个名字。
 * 同一个东西在两个端点上叫两个名字,前端就得记住哪个端点用哪个 —— 那是纯粹的出错来源。
 * <b>契约里的 {@code nodeId} 与这里的 {@code nodeCode} 指的是同一样东西</b>,已在交付说明里报出。
 *
 * @param nodeCode 挂到哪个考点。必须是骨架树里<b>未归档</b>的 code
 */
public record MountTagRequest(

        @NotBlank(message = "必须给出考点 code —— 只能从树里选,不能新建")
        @Size(max = 64, message = "考点 code 最长 64 个字符")
        String nodeCode
) {

    /**
     * 🔴 R-07 的第二道锁 —— 未定义字段一律拒绝。
     *
     * <p><b>{@code value} 收下就丢</b>:它是用户送来的原文,可能就是一整段题干。
     * 异常里只带字段名(01 §2.2 不碰内容)。
     */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
