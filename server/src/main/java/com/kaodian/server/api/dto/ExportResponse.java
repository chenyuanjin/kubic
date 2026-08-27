package com.kaodian.server.api.dto;

import com.kaodian.server.collect.Touch;
import com.kaodian.server.coverage.CoverageService.GroupCoverage;
import com.kaodian.server.coverage.CoverageService.Summary;
import com.kaodian.server.syllabus.Syllabus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 全量导出的<b>唯一那份数据</b> —— md / csv / json 三种写法都从它渲染(docs/10 §6.5)。
 *
 * <h2>为什么三种格式共用一个 record,而不是各写各的</h2>
 *
 * §6.5 的承诺是「无删减」,而最容易破掉它的方式不是有人故意少导,是<b>三条渲染路径各自演化</b>:
 * json 加了一个字段,md 忘了跟,半年后没人说得清哪一份才是全的。
 * 共用一个 record 之后,「三种格式内容不一致」这件事<b>需要有人主动去写代码才做得到</b> ——
 * 默认状态是一致。
 * <p>
 * 同理,md 与 csv 的列不是各自硬编码的,而是共用 {@code ExportRenderer} 里同一张列表 + 同一批行。
 *
 * <h2>🔴 字段表就是内容边界(R-06)</h2>
 *
 * 全部七个分量,一个都不装机构的课程内容:
 * <ul>
 *   <li>{@code subject} / {@code summary} —— 我们自己的元信息与统计</li>
 *   <li>{@code nodes} / {@code archivedNodes} —— 考点 code、名称、频次统计、我的触达情况</li>
 *   <li>{@code records} —— <b>来源名、时间、方式、考点</b>,以及用户自填的两个整数</li>
 * </ul>
 * 没有题干({@code R-01}),没有讲义、没有课程内容({@code R-06})—— 上游的 {@link Touch}
 * 与 {@link Syllabus} 结构上就没有这些位置,导出这一侧自然也拼不出来。
 * <b>「来源」在这里只是一个名字</b>,不是那节课的任何内容。
 *
 * <h2>为什么复用 {@link NodeDetailDto} / {@link TimelineItemDto} 而不新定义字段</h2>
 *
 * §6.5 要的是「字段名与 API 契约一致」。复用现成的 DTO 是让这句话<b>在编译期成立</b>的唯一办法:
 * {@code GET /api/records} 与导出里的一条记录长得一模一样,不是因为有人对齐过,
 * 是因为它们本来就是同一个 record。顺带地,红线扫描的白名单也不需要为导出新增一行 ——
 * 这份导出没有引入任何一个新的自由文本位置。
 *
 * @param exportedAt    这份导出的快照时刻。<b>不是署名</b> —— 导出里没有水印,
 *                      见 {@code ExportController} 的类注释
 * @param recordCount   行为层记录总数。它与 {@code records.size()} 恒等,
 *                      摆出来是为了让「有没有被截断」这件事<b>不用信任、可以核对</b>
 * @param nodes         参与差集的考点,按树的顺序摊平
 * @param archivedNodes 已归档的考点。<b>它们不在差集里,但必须在导出里</b> ——
 *                      docs/13 {@code R-49}:「归档可以无声刷高覆盖率」,
 *                      而三条对策之一就是「导出带完整归档清单」。少了这一段,
 *                      导出就成了那句无声的同谋
 * @param records       全部触达记录,<b>按发生时间升序</b>。{@code /api/records} 是倒序的(最近的在最上面),
 *                      那是屏幕的需要;一份存档按发生顺序读才连得起来
 */
public record ExportResponse(
        Instant exportedAt,
        SubjectDto subject,
        SummaryDto summary,
        int recordCount,
        List<NodeDetailDto> nodes,
        List<SyllabusNodeDto> archivedNodes,
        List<TimelineItemDto> records
) {

    /**
     * 从一次覆盖度快照拼出整份导出。
     *
     * <p>参数摊开写而不是直接收 {@code CoverageReader.Snapshot},是为了不让 {@code api.dto}
     * 反过来依赖 {@code api} ——「包之间只通过接口调用」(docs/10 §2.2),
     * 而 DTO 是被组装的一方,不该认识组装它的那个人。
     *
     * @param touches 行为层原始记录,<b>调用方给什么就导什么</b>:这里没有任何过滤、
     *                没有 limit、没有分页。「无删减」这条承诺落在代码上就是这一句
     */
    public static ExportResponse of(Instant exportedAt, Syllabus syllabus, Summary summary,
                                    List<GroupCoverage> groups, List<Touch> touches) {

        List<NodeDetailDto> nodes = groups.stream()
                .flatMap(g -> g.nodes().stream())
                .map(NodeDetailDto::from)
                .toList();

        // 归档的考点不在覆盖度结果里(compute 只走 activeNodes),所以这一段单独从树上取。
        // 记录数就地从 touches 里数 —— 不去问 SyllabusStore,免得同一个数有两个出处。
        List<SyllabusNodeDto> archived = new ArrayList<>();
        for (Syllabus.Group g : syllabus.groups()) {
            for (Syllabus.Node n : g.archivedNodes()) {
                int count = (int) touches.stream().filter(t -> n.code().equals(t.nodeCode())).count();
                archived.add(SyllabusNodeDto.of(n, g, count));
            }
        }

        List<TimelineItemDto> records = touches.stream()
                .map(t -> TimelineItemDto.from(t, syllabus))
                .toList();

        return new ExportResponse(
                exportedAt,
                SubjectDto.from(syllabus.subject()),
                SummaryDto.from(summary),
                touches.size(),
                nodes,
                List.copyOf(archived),
                records);
    }
}
