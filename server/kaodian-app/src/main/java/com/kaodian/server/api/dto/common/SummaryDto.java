package com.kaodian.server.api.dto.common;

import com.kaodian.server.api.dto.insight.StateCountDto;
import com.kaodian.server.coverage.CoverageService.Summary;

import java.util.List;

/**
 * 覆盖概览 —— 界面上那个大字。
 *
 * <p>{@code percent} 由 {@link Summary#percent()} 取整给出,<b>前端不要拿 covered/total 自己算</b>:
 * 两处算同一个数就一定会算出两个数(比如一边四舍五入一边截断,44% 和 43% 同屏)。
 *
 * <h2>🔴 {@code asserted} 是<b>单独一格</b>,不与其它任何一个数相加</h2>
 *
 * docs/10 §6.4:「分母 = level 3 节点数;分子 = {@code discarded=0} 的触达节点数;
 * <b>断言单列不并入</b>」。它不在 {@code covered} 里、不从 {@code empty} 里扣、
 * 不占 {@code distribution} 的一格。<b>界面上它是并排的另一个数,不是这个百分比的一部分</b> ——
 * 「我已掌握」按钮是补丁不是解法(01 §5.2),不能让那个大字因为点按钮而变大。
 * <p>
 * 也因此:{@code covered + empty == total} 这条恒等式<b>照旧成立</b>,
 * 而 {@code asserted} 与它们两个都可以重叠。
 *
 * @param empty              空白考点数 —— 盲区的大小,{@code total − covered}
 * @param whollyEmptyGroups  整块空白的题型数
 * @param asserted           声明「我已掌握」的考点数。<b>与覆盖率无关</b>,见上
 * @param distribution       五态分布,顺序固定,带中文 label。<b>断言不在这五格里</b>
 */
public record SummaryDto(
        int total,
        int covered,
        int empty,
        int percent,
        int whollyEmptyGroups,
        int asserted,
        List<StateCountDto> distribution
) {
    public static SummaryDto from(Summary s) {
        return new SummaryDto(
                s.total(), s.covered(), s.empty(), s.percent(), s.whollyEmptyGroups(),
                s.asserted(), StateCountDto.from(s.distribution()));
    }
}
