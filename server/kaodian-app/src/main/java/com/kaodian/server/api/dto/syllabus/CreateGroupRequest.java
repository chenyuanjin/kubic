package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增一个题型。
 *
 * <p>🔴 与 {@link CreateNodeRequest} 一样:<b>没有 code 字段</b>(服务端生成),
 * 也<b>没有 nodes 字段</b> —— 新建的题型一定是空的。
 *
 * <p>「一次把一个题型连同它下面十几个考点整个建出来」听起来省事,而那正是
 * <b>批量导入的入口</b>:一旦能一次提交一棵子树,下一步就是从某个机构的目录页整块拷进来,
 * 违反 R-07 / docs/04 §1.2「标签自行命名、不沿用机构既有体系与措辞」。
 * 逐个新增很慢,慢正是要的效果 —— 它逼着人对每一个考点名做一次自己的判断。
 *
 * @param name <b>自行归纳</b>的题型名
 */
public record CreateGroupRequest(

        @NotBlank(message = "题型必须有名字")
        @Size(max = 40, message = "题型名最长 40 个字符 —— 它是个名字,不是放内容的地方")
        String name
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
