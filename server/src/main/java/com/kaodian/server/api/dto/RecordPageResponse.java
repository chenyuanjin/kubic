package com.kaodian.server.api.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code GET /api/records} 的一页 —— <b>原始记录的时间线,cursor 分页</b>(docs/10 §6.2)。
 *
 * <h2>🔴 它和 {@code GET /api/timeline} 不是一个东西,两个都要留着</h2>
 *
 * 契约里它们分在两节,职责不同:
 *
 * <table border="1">
 *   <caption>两个时间线端点的分工</caption>
 *   <tr><th>—</th><th>{@code GET /api/records}</th><th>{@code GET /api/timeline}</th></tr>
 *   <tr><td>契约</td><td>§6.2 采集</td><td>§6.4 查询</td></tr>
 *   <tr><td>给谁用</td><td><b>记录本身</b>:翻历史、找那条记错的删掉</td>
 *       <td><b>聚合视图</b>:按天/周看「这段时间碰过些什么」</td></tr>
 *   <tr><td>翻页</td><td>cursor,能一直往回翻</td><td>{@code limit} 截最近 N 条</td></tr>
 *   <tr><td>属于</td><td>采集这条线的读侧</td><td>查询那条线,与覆盖概览、盲区并列</td></tr>
 * </table>
 *
 * <b>合并成一个是错的</b>:聚合视图迟早要按天分桶、要带每天的覆盖增量,
 * 而删记录那个页面要的是一条一条、能翻到底。把两种需求塞进一个端点,
 * 结果是一堆互相排斥的查询参数,以及一个谁都不敢改的返回体。
 *
 * <p>⚠ 顺带记一笔:{@code /api/timeline} 今天返回的是<b>平铺的最近 N 条</b>,
 * 还没有做契约 §6.4 要的按天/周聚合。这一条<b>不在本轮范围内、也没有被顺手改掉</b> ——
 * 它是那个端点自己的欠账,不是这个端点的理由。
 *
 * <h2>为什么 cursor 不是 offset</h2>
 *
 * docs/10 §六 的统一约定:「分页用 cursor 不用 offset」。理由在这个产品上很实:
 * 时间线是<b>倒序</b>的,而用户翻页的同时还在记新的一笔。offset 分页下,
 * 新记一笔会把整个列表往后推一格 —— 第二页的第一条正好是第一页看过的最后一条,
 * 而中间那条<b>永远不会被看到</b>。cursor 锚在一条具体记录上,新记的落在它前面,不影响往后翻。
 *
 * @param total      行为层记录总数(不是本页的)。前端显示「共 128 条」用它
 * @param returned   本页几条
 * @param hasMore    还有没有更旧的。<b>不要靠 {@code nextCursor != null} 判断</b>——
 *                   两者含义相同是实现细节,而这个布尔是契约
 * @param nextCursor 下一页从哪儿接着翻;没有更多时为 {@code null}
 * @param items      本页的记录,<b>按发生时间倒序</b>,最近的在最前
 */
public record RecordPageResponse(
        int total,
        int returned,
        boolean hasMore,

        @Size(max = RecordPageResponse.MAX_CURSOR_LENGTH)
        String nextCursor,

        List<TimelineItemDto> items
) {

    /**
     * 游标字符串的长度上限。
     *
     * <h2>它同时是「我们发出去的最长游标」和「我们肯收的最长游标」</h2>
     *
     * 游标是服务端签发的:{@code Base64URL(发生时间毫秒 | 记录 id)},
     * 记录 id 是 {@code t-} + UUID(38 字符),算下来七十出头。120 给了余量。
     * <p>
     * 🔴 更要紧的是<b>收</b>的那一侧:游标是查询参数,而查询参数没有任何长度上限
     * (见 {@code ApiException} 的同一段)。一个「解不开的游标」的报错会带着它进服务端日志,
     * 于是「翻页」这条最无害的路径就成了往日志里写一整段题干的通道。
     * 超过这个长度的游标<b>连解都不解</b>,直接拒。
     */
    public static final int MAX_CURSOR_LENGTH = 120;

    /**
     * 每页条数的默认值与上限。
     *
     * <p>与 {@code TimelineController} 的 50/200 保持一致,不是巧合也不是复制:
     * 两个端点翻的是同一批记录,一屏该放多少条这件事没有理由在两处给出两个答案。
     */
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
}
