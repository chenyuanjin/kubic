package com.kaodian.server.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

/**
 * 合并预览 / 确认。
 *
 * <p>预览与确认<b>共用同一个令牌</b>,但只有确认会消费掉它。
 * 预览是只读的、可以重复调用;确认执行一次之后令牌立刻作废 ——
 * 这是「不可逆操作被重复提交」唯一有效的防线。
 */
public record MergeRequest(@NotBlank(message = "缺少合并令牌") String mergeToken) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
