package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.api.dto.common.SyllabusNodeDto;

/**
 * 一次考点编辑之后返回的东西:改完的那个考点 + <b>整棵树的新覆盖概览</b>。
 *
 * <h2>为什么每次编辑都要把 summary 带回来</h2>
 *
 * 因为编辑骨架<b>会动那个百分比</b>,而且方式不止一种:
 * 新增一个考点 → 分母 +1;归档一个有记录的考点 → 分母分子同时 −1;
 * 删除一个空考点 → 分母 −1。这三件事在界面上看起来都只是「改了一下树」,
 * 数字却当场变了。
 * <p>
 * 不带回来,前端就得紧接着再请求一次概览 —— 两次请求之间差集会被重算一遍,
 * 而「生疏」是按时间推的,理论上能算出不一样的结果。<b>一次写入,一个一致的结果。</b>
 * 这与 {@link CreateRecordResponse} 是同一条理由。
 *
 * <h2>🔴 重命名是这里唯一不改数字的操作</h2>
 *
 * 改完名字之后 summary 应当<b>逐字不变</b> —— 因为记录挂 code 不挂名字。
 * 这不是一句承诺,它被一条测试钉着。
 *
 * @param node    改完之后那个考点的最新样子
 * @param summary 改完之后整棵树的覆盖概览
 */
public record NodeEditResponse(
        SyllabusNodeDto node,
        SummaryDto summary
) {
}
