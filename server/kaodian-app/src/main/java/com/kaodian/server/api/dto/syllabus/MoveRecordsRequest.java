package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 把一个考点上的记录整体改挂到另一个考点。
 *
 * <h2>🔴 这个端点存在的唯一理由是「删除守则」</h2>
 *
 * 有记录的考点删不掉(删了记录就成孤儿,覆盖率的分母和分子同时失真)。
 * 于是必须给出一条<b>不丢数据的出路</b>:先把记录搬走,搬完那个考点是空的,才允许删。
 * 另一条出路是归档 —— 那条连搬都不用搬。
 *
 * <h2>只接受 nodeCode,和记一笔是同一条线</h2>
 *
 * 目标必须是骨架树里<b>已存在且未归档</b>的考点(R-07)。不接受自由文本,
 * 也不接受「顺便新建一个考点然后搬进去」—— 新建是另一个端点、另一次判断。
 *
 * @param toNodeCode 搬到哪个考点上
 */
public record MoveRecordsRequest(

        @NotBlank(message = "必须说明记录搬到哪个考点上")
        @Size(max = 64, message = "考点 code 最长 64 个字符")
        String toNodeCode
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
