package com.kaodian.server.api.dto.common;

// 🔴 注解留在 com.fasterxml.jackson.annotation —— 理由与 ApiError 同一条,见那个文件。
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kaodian.server.coverage.CoverageService.Summary;

/**
 * 覆盖概览 —— {@code GET /api/v1/coverage/summary} 的响应体
 * ({@code M3-骨架与覆盖度差集} §9.1)。
 *
 * <h2>🔴 这里没有 {@code percent},也没有任何一个浮点字段</h2>
 *
 * 上一版有 {@code percent}(那个大字)与 {@code distribution}(五态分布)。
 * {@code 看盲区} §2.9 写死<b>用户侧任何位置不出现百分比</b>;落在契约上是一句更硬的话:
 * <b>{@code M3} 的响应体里没有任何一个浮点字段</b>({@code CoverageNoRatioTest})。
 * <p>
 * 「有没有 / 几次 / 多久前」三件事的答案分别是 {@code bool} / {@code int} / 带时区的绝对时间,
 * 三种里没有一种需要小数。一个浮点字段出现在这一域,它<b>一定</b>是一个比值 ——
 * 而比值只有两种:掌握度,或百分比。<b>改个名字绕不过这一条。</b>
 *
 * <h2>🔴 三个数一律由服务端算并返回,前端不做任何一次减法</h2>
 *
 * {@code U3.1} §2.1。所以 {@code nodeUntouched} 是一个字段而不是让端去算 ——
 * 端算的那一版会在服务端口径改变时无声地对不上。
 *
 * <h2>「为 0」与「没数过」是两档,由 key 在不在区分(§二)</h2>
 *
 * <table border="1">
 *   <caption>逐字段的两档</caption>
 *   <tr><th>字段</th><th>有值为 0</th><th>没数过</th></tr>
 *   <tr><td>{@code nodeTotal} / {@code nodeTouched} / {@code nodeUntouched}</td>
 *       <td>key 在,值 0</td><td>🔴 <b>重算中三个 key 整个不出现</b></td></tr>
 *   <tr><td>{@code archivedCount}</td><td>✅ <b>恒在</b>,为 0 也返回</td>
 *       <td>🔴 <b>不存在这一档</b> —— 它只依赖骨架层,与用户行为无关,不参与重算</td></tr>
 *   <tr><td>{@code assertedCount}</td><td>key 在,值 0</td>
 *       <td>🔴 <b>重算中 key 不出现</b> —— 它的定义含着 {@code B = D∖N},依赖行为层</td></tr>
 *   <tr><td>{@code statsAsOfYear}</td><td>——</td><td>没有统计 → key 不出现</td></tr>
 * </table>
 *
 * 🔴 {@code assertedCount} 与 {@code archivedCount} 待遇不同不是随手定的:
 * 把 {@code assertedCount} 实现成「数一遍断言表的行数」确实能在重算中返回一个数,
 * 但那个数在「断言过、后来又碰过」的节点上会比屏上该显示的<b>多一个</b> ——
 * 那个节点的状态是 {@code TOUCHED},它不在 {@code B} 里。
 *
 * <h2>🔴 「骨架还没建好」不是这里的一档</h2>
 *
 * 返回 {@code {"nodeTotal": 0, ...}} 在语法上完全合法,界面也拿得到一个数 ——
 * 而那个「0 个考点」是一句假话({@code U3.1} §2.4)。所以它走<b>状态码</b>
 * {@code 422 SYLLABUS_EMPTY},不走字段,整屏进空态,连 {@code total = 0} 都不写。
 *
 * @param recalculating 🔴 「重算中」与「已是最新」两档就是这一个布尔,<b>恒在</b>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SummaryDto(
        Integer nodeTotal,
        Integer nodeTouched,
        Integer nodeUntouched,
        int archivedCount,
        Integer assertedCount,
        Integer statsAsOfYear,
        boolean recalculating
) {

    /**
     * 已是最新的那一档 —— 五个数都在。
     *
     * @param statsAsOfYear 统计截止年;{@code null} → key 不出现。
     *                      ⚠️ 骨架层今天<b>没有</b>这个事实的来源({@code Syllabus.Node}
     *                      只有 {@code recent5yCount}),所以它今天恒为 {@code null}。
     *                      这正是「没数过」那一档该有的样子,不是一个待补的 0
     */
    public static SummaryDto of(Summary s, Integer statsAsOfYear) {
        return new SummaryDto(s.nodeTotal(), s.nodeTouched(), s.nodeUntouched(),
                s.archivedCount(), s.assertedCount(), statsAsOfYear, false);
    }

    /**
     * 重算中的那一档 —— 🔴 <b>四个 key 消失,{@code archivedCount} 留下</b>。
     *
     * <p>为什么不返回一个「上一次算出来的」旧值:显示一份过期的覆盖度,
     * 和显示一个错的数区别不大,而端<b>分辨不出</b>它是旧的。
     * {@code recalculating} 这个布尔存在的全部理由就是让端能分辨。
     */
    public static SummaryDto recalculating(int archivedCount) {
        return new SummaryDto(null, null, null, archivedCount, null, null, true);
    }
}
