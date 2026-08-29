package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增一个考点。
 *
 * <h2>🔴 一:没有 {@code code} 字段</h2>
 *
 * code 由服务端生成,而且<b>不从名字派生</b>(见 {@code FileSyllabusStore#generateCode})。
 * 让客户端指定 code 等于把主键交出去;拿中文名当 code 更糟 ——
 * code 存在的全部理由就是「改名不断历史」,用名字做 code 等于取消这个理由。
 *
 * <h2>🔴 二:父级只能是题型,没有 {@code parentNodeCode}</h2>
 *
 * 模块 → 题型 → 考点,<b>三层,不做第四层</b>(01 §2.5)。
 * 这条限制在这里的形态就是:请求体里根本没有一个能指向另一个考点的字段,
 * 而 {@link #rejectUnknownField} 保证多写一个也进不来。
 *
 * <h2>🔴 三:名字有长度上限,这个上限是防内容夹带的</h2>
 *
 * 「增长量计算」五个字。40 是宽裕的,同时挡住把一整段题干贴进「考点名」这条绕路 ——
 * 与 {@link CreateRecordRequest#sourceName()} 上那个 60 是同一种上限。
 * 权威判定在 {@code FileSyllabusStore.validName}(它还会拒绝换行),这里只是提前给个清楚的报错。
 *
 * @param groupCode     挂到哪个题型下。必须是树里已有的题型 code
 * @param name          <b>自行归纳</b>的考点名,不沿用机构既有措辞(R-07 / docs/04 §1.2)
 * @param recent5yCount 近五年出现次数。统计事实(docs/07),也是盲区排序的权重之一
 */
public record CreateNodeRequest(

        @NotBlank(message = "必须说明挂到哪个题型下")
        @Size(max = 64, message = "题型 code 最长 64 个字符")
        String groupCode,

        @NotBlank(message = "考点必须有名字")
        @Size(max = 40, message = "考点名最长 40 个字符 —— 它是个名字,不是放内容的地方")
        String name,

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
