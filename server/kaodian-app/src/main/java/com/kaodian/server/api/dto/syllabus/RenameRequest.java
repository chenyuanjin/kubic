package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重命名一个考点或题型。<b>只有名字,没有 code。</b>
 *
 * <h2>🔴 请求体里没有 code,不是省略,是不给这条路</h2>
 *
 * 要改哪个由路径决定,而这个请求<b>永远不会改动那个 code</b>。
 * 记录挂在 code 上,所以改名之后覆盖率、五态、盲区排序<b>一个数都不变</b> ——
 * 这正是阶段 1 敢反复「人工校正命名」的原因(docs/实施路径 §1.2)。
 * 只要请求体里没有第二个 code 字段,「改名顺便换个 code」这件事就无从发生。
 *
 * @param name 新名字。<b>自行归纳,不沿用机构既有措辞</b>(R-07)
 */
public record RenameRequest(

        @NotBlank(message = "新名字不能为空")
        @Size(max = 40, message = "名称最长 40 个字符 —— 它是个名字,不是放内容的地方")
        String name
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
