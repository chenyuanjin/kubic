package com.kaodian.server.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 调整某个题型下考点的顺序。
 *
 * <p>理由与约束同 {@link GroupOrderRequest}:树序决定盲区并列时的先后,
 * 所以顺序要显式持久化;列表必须是该题型下<b>未归档</b>考点的完整排列,
 * 少一个就整体拒绝。
 *
 * <p>已归档的考点不参与排序 —— 它们不进差集,先后没有意义 —— 重排后统一沉到末尾。
 *
 * @param nodeCodes 该题型下未归档考点 code 的完整排列
 */
public record NodeOrderRequest(

        @NotEmpty(message = "顺序不能为空")
        @Size(max = 500, message = "一次最多 500 个考点")
        List<@NotBlank @Size(max = 64) String> nodeCodes
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
