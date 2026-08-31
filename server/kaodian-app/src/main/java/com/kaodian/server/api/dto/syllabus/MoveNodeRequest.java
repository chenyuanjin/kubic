package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 把一个考点移到另一个题型下。
 *
 * <p>🔴 目标只能是<b>题型</b>,不能是另一个考点 —— 三层就是三层(决策记录 §2.5)。
 * 字段名写死成 {@code groupCode} 而不是笼统的 {@code parentCode},就是为了让这件事在接口上一眼可见。
 *
 * <p>移动<b>不改 code</b>,所以那个考点上的记录一条都不受影响。
 * 会变的只有两件事:它算进哪个题型的「整块空白」,以及盲区并列时的先后(树序)。
 *
 * @param groupCode 目标题型 code
 */
public record MoveNodeRequest(

        @NotBlank(message = "必须说明移到哪个题型下")
        @Size(max = 64, message = "题型 code 最长 64 个字符")
        String groupCode
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
