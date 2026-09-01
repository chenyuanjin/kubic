package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 修改一个考点的近五年频次。
 *
 * <h2>这是统计事实,不是难度、不是权重表</h2>
 *
 * {@code recent5yCount} 的含义只有一个:<b>这个考点近五年在真题里出现过几次</b>(docs/data/INDEX.md)。
 * 它不是「这题多难」,也不是「你该花多少时间」—— 那两样都属于教研,决策记录 §2.2 划在边界外。
 * 盲区排序用它做权重({@code blindScore = 频次 × 状态权重}),用的也正是它作为事实的那一面。
 *
 * <p>所以这里没有 {@code difficulty}、没有 {@code weight}、没有 {@code priority} ——
 * 一旦出现一个可以随手调的「权重」,排序就从一个事实推论变成一个不可复核的主观数。
 *
 * @param recent5yCount 非负整数
 */
public record SetFrequencyRequest(

        @NotNull(message = "必须给出近五年频次;一次都没考过就填 0")
        @Min(value = 0, message = "近五年频次不能为负")
        @Max(value = 999, message = "近五年频次上限 999")
        Integer recent5yCount
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
