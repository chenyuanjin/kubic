package com.kaodian.server.api.dto;

import com.kaodian.server.coverage.CoverageService.Summary;

import java.util.List;

/**
 * 覆盖概览 —— 界面上那个大字。
 *
 * <p>{@code percent} 由 {@link Summary#percent()} 取整给出,<b>前端不要拿 covered/total 自己算</b>:
 * 两处算同一个数就一定会算出两个数(比如一边四舍五入一边截断,44% 和 43% 同屏)。
 *
 * @param empty              空白考点数 —— 盲区的大小,{@code total − covered}
 * @param whollyEmptyGroups  整块空白的题型数
 * @param distribution       五态分布,顺序固定,带中文 label
 */
public record SummaryDto(
        int total,
        int covered,
        int empty,
        int percent,
        int whollyEmptyGroups,
        List<StateCountDto> distribution
) {
    public static SummaryDto from(Summary s) {
        return new SummaryDto(
                s.total(), s.covered(), s.empty(), s.percent(), s.whollyEmptyGroups(),
                StateCountDto.from(s.distribution()));
    }
}
