package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.SubjectDto;
import com.kaodian.server.syllabus.Syllabus;

import java.util.List;

/**
 * 导出<b>自己的</b>骨架树。
 *
 * <h2>🔴 有导出,没有导入 —— 这条边界是有意的</h2>
 *
 * 01 §2.6 要的是完整数据导出(Markdown / CSV / JSON),那是对用户的承诺:
 * <b>你的东西你随时能拿走。</b> 所以这个端点存在。
 * <p>
 * 但它<b>没有对称的导入端点</b>,而且不打算有。一个接受任意树形 JSON 的
 * 「批量导入考点体系」端点,现实中的第一个用途一定是把某个机构的目录页整块拷进来 ——
 * 而 R-07 / docs/04 §1.2 要求<b>考点标签自行归纳、不沿用机构既有体系与措辞</b>。
 * 那不是一条能靠「用的时候注意点」守住的线:只要通道在,它就会被用。
 * <p>
 * 恢复自己的备份走的是另一条路:把导出的文件放回 {@code ~/.kaodian/syllabus.json}
 * (格式与这个响应体一一对应,{@code FileSyllabusStore} 启动时会读它)。
 * <b>那是「把自己的文件放回自己的目录」,不是一个能接收别人体系的 API。</b>
 * 将来如果真要做导入端点,它也只能接受这个格式,并且必须先回答这条边界怎么守。
 *
 * <h2>导出的内容里同样没有内容</h2>
 *
 * 名称、层级、近五年频次、归档标记,四样。没有题干、没有解析、没有任何机构的课程内容 ——
 * 这棵树从来就没装过它们(01 §2.2 / docs/07)。
 *
 * @param subject 这棵树是哪个省、哪门考试、哪个模块
 * @param groups  题型 → 考点,两层。<b>没有第三层嵌套</b>,因为不做第四层(01 §2.5)
 */
public record SyllabusExportResponse(
        SubjectDto subject,
        List<ExportGroupDto> groups
) {

    /** 导出里的一个题型。 */
    public record ExportGroupDto(String code, String name, List<ExportNodeDto> nodes) {}

    /**
     * 导出里的一个考点。
     *
     * <p>🔴 <b>没有 {@code children}</b> —— 第四层在数据结构上就不存在(01 §2.5),
     * 导出格式作为「放回去还能读」的那一份,自然也不给它留位置。
     */
    public record ExportNodeDto(String code, String name, int recent5yCount, boolean archived) {}

    public static SyllabusExportResponse from(Syllabus s) {
        return new SyllabusExportResponse(
                SubjectDto.from(s.subject()),
                s.groups().stream()
                        .map(g -> new ExportGroupDto(g.code(), g.name(),
                                // 归档的也导出:它们是用户自己的历史,导出必须是完整的
                                g.nodes().stream()
                                        .map(n -> new ExportNodeDto(
                                                n.code(), n.name(), n.recent5yCount(), n.archived()))
                                        .toList()))
                        .toList());
    }
}
