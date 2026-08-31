package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.GroupDto;
import com.kaodian.server.api.dto.common.SubjectDto;
import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.coverage.CoverageService.GroupCoverage;
import com.kaodian.server.coverage.CoverageService.Summary;
import com.kaodian.server.syllabus.Syllabus;

import java.util.List;

/**
 * 骨架树 + 覆盖,一次返回。
 *
 * <h2>不做懒加载</h2>
 *
 * docs/技术架构 §6.4:单模块整棵树一次返回。18 个考点、5 个题型,懒加载省不下任何东西,
 * 却会让「整块题型都没碰过」这种<b>需要看全貌才成立的判断</b>变成一串异步请求。
 *
 * <h2>为什么概览也塞在这里</h2>
 *
 * 树这一屏顶上就是那个百分比。分成两个请求意味着两个时刻的两次差集计算,
 * 而「生疏」是按时间推的 —— 两次计算之间跨过某个 30 天边界,顶上的数就和树上的颜色对不上了。
 * 同一个 {@code summary} 与 {@code groups} 出自同一次 {@code compute},这是它们必须同行的理由。
 */
public record TreeResponse(
        SubjectDto subject,
        SummaryDto summary,
        List<GroupDto> groups
) {
    public static TreeResponse of(Syllabus syllabus, Summary summary, List<GroupCoverage> groups) {
        return new TreeResponse(
                SubjectDto.from(syllabus.subject()),
                SummaryDto.from(summary),
                groups.stream().map(GroupDto::from).toList());
    }
}
