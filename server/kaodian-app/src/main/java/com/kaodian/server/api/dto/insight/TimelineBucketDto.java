package com.kaodian.server.api.dto.insight;

import java.time.LocalDate;
import java.util.List;

/**
 * 时间线上的一格 —— 「这一天(或这一周)你碰了几次、碰到几个考点、从哪几个来源」。
 *
 * <h2>🔴 三个数,一个判断都没有</h2>
 *
 * 没有 {@code trend}、{@code changeVsPrevious}、{@code streak}(连续天数)、
 * {@code best} / {@code worst}、{@code improved}。这不是「暂时不给」,是 {@code R-05}
 * 在聚合视图上的形状:<b>产品只报「有没有、几次、多久前」,不判断「对不对」</b>(决策记录 §2.2)。
 * <p>
 * 聚合视图是这条红线最松的一处 —— 一旦相邻两格的数摆在同一个数组里,
 * 「这周比上周多了 40%」就只差一次减法,而那句话是<b>产品在给用户下评语</b>。
 * 减法本身没有罪,罪在<b>由服务端说出口</b>:字段名就是措辞,契约里出现
 * {@code improved} 的那天,前端不写「进步了」反而成了额外的克制。
 * <p>
 * 同理这里<b>没有 {@code practiced} / {@code correct} / 正确率</b>。
 * docs/technical/INDEX.md §5.2 撤掉 {@code practice_log} 那张表时给的理由逐字适用:
 * 「按正确率排薄弱考点只差一条 SQL,而那正是 {@code R-05}」——
 * 每格一个正确率,「这周退步了」就是同一条 SQL 的另一种写法。
 *
 * <h2>空格子也在数组里,{@code touchCount} 为 0</h2>
 *
 * 只吐有记录的格子会让「连着五天没记」和「连着五天每天都记」在图上长得一模一样 ——
 * 而<b>「有没有」正是这个产品仅有的三个维度里的第一个</b>。空格子给出的是
 * 「这天没有记录」这个<b>事实</b>,不是「这天偷懒了」这个评价;措辞归前端,
 * 但服务端连一个能挂评价的字段都不给(见上一段)。
 *
 * @param start      这一格的第一天。按天时它就是那天;按周时是<b>周一</b>
 * @param end        这一格的最后一天(<b>含</b>)。按天时 {@code end == start} ——
 *                   两个字段都给,是为了让前端<b>不必知道「周从哪天起」这条规则</b>:
 *                   规则只写在 {@code TimelineGranularity} 一处,客户端照着画就行
 * @param touchCount 这一格里的记录条数
 * @param nodeCount  这一格里碰到的<b>不同考点</b>个数。⚪ 它不是「新覆盖的考点数」——
 *                   那要回答「在这一格之前碰过没有」,是个跨窗口的累积量,
 *                   而窗口起点之前的记录在这个响应里根本没被读。契约那一行也没要它,
 *                   所以这里给的是当格去重计数,<b>不是拿它冒充覆盖增量</b>
 * @param sources    来源分布,<b>条数多的在前</b>;并列时按名字排,保证同一份数据两次请求同序。
 *                   空格子给空数组,不给 {@code null} —— 前端少一条分支
 */
public record TimelineBucketDto(
        LocalDate start,
        LocalDate end,
        int touchCount,
        int nodeCount,
        List<BucketSourceDto> sources
) {
}
