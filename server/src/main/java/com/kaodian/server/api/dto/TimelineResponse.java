package com.kaodian.server.api.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/timeline} —— <b>按天/周聚合的触达视图</b>(docs/10 §6.4)。
 *
 * <h2>🔴 它和 {@code GET /api/records} 不是一个东西,两个都要留着</h2>
 *
 * 分工那张表写在 {@link RecordPageResponse} 的 javadoc 里,<b>这里不再抄一遍</b> ——
 * 抄一遍就是两份会各自漂移的说明。一句话版本:
 * <b>{@code /records} 是一条一条的原始记录(§6.2 采集线的读侧,cursor 分页),
 * 这一条是一格一格的统计(§6.4 查询线,与覆盖概览、盲区并列)。</b>
 * <p>
 * ⚠ 这个端点<b>曾经</b>返回平铺的最近 N 条,也就是在干 {@code /records} 的活;
 * {@code RecordPageResponse} 里那句「今天还没有做按天/周聚合」记的就是这笔欠账。
 * 现在还上了:平铺那一份归 {@code /records},这里只出桶。
 *
 * <h2>🔴 三个数,一个判断都没有</h2>
 *
 * 每一格里有什么、以及为什么<b>没有</b>趋势/连续天数/正确率,写在
 * {@link TimelineBucketDto} 上。整份响应同样一个评价字段都没有:
 * 没有「最活跃的一周」、没有「平均每天几条」—— 平均数看着中立,
 * 但它唯一的用途是拿今天去和它比。
 *
 * <h2>{@code total} 与 {@code counted}:窗口外的记录不会凭空消失</h2>
 *
 * {@code total} 是行为层记录<b>总数</b>,{@code counted} 是落进这个窗口的条数。
 * 两个都给,是因为只给后者的话,用户会看着一张只有 12 条的图问「我记的 128 条呢」;
 * 而只给前者,图上的柱子加起来对不上那个总数。<b>差额本身就是一句话:
 * 「还有 116 条在这段时间之前」</b>,而那句话由前端说,不由这里的字段说。
 *
 * <h2>{@code zone} 为什么必须出现在响应里</h2>
 *
 * 「今天」是哪一天,取决于按哪个时区切。服务端按北京时间切(理由见
 * {@code TimelineController} 的同名段落),而浏览器的 {@code new Date()} 用的是设备时区。
 * 不把切桶用的时区告诉前端,它就会拿设备时区去判断「数组最后一格是不是今天」,
 * 于是<b>一个把手机时区设成美西的用户,会看到最后一格标着「昨天」</b>。
 *
 * @param granularity      {@code DAY} / {@code WEEK},枚举原名,前端按它选渲染
 * @param granularityLabel 中文名,服务端给,前端不硬编码中文 —— 与 {@code stateLabel} 同一条
 * @param zone             切桶用的时区 id,如 {@code Asia/Shanghai}
 * @param from             第一格的第一天
 * @param to               最后一格的最后一天(含)。⚠ 按周时它<b>可能是未来的日期</b>,
 *                         因为本周还没过完。这不是 bug:格子的边界由日历定,不由「今天」定 ——
 *                         把最后一格截到今天,会让它比别的格子窄,而一根宽度不等的柱子
 *                         在图上直接就是误读
 * @param total            行为层记录总数(不是窗口内的)
 * @param counted          落进这个窗口的记录条数;等于所有 {@code touchCount} 之和
 * @param buckets          每一格,<b>按时间升序(旧 → 新)</b>,且<b>不跳过空格子</b>
 */
public record TimelineResponse(

        // 🔴 下面三个 @Size 不校验任何东西 —— 响应体从不过 Validator。
        // 它们是给 NoStemFieldTest 看的【长度声明】:api.dto 包里每个 String 字段都得说出自己的上限,
        // 否则那条断言就退化成「凡是响应体一律放行」。这三个值全是我们自己敲的字面量
        // (枚举原名、两个中文词、一个 IANA 时区 id),上限是多少不重要,
        // 重要的是它们【有】上限 —— 装不下一段题干。
        @Size(max = MAX_ENUM_NAME_LENGTH)
        String granularity,

        @Size(max = MAX_ENUM_NAME_LENGTH)
        String granularityLabel,

        @Size(max = MAX_ZONE_ID_LENGTH)
        String zone,

        LocalDate from,
        LocalDate to,
        int total,
        int counted,
        List<TimelineBucketDto> buckets
) {

    /** {@code DAY} / {@code WEEK} / 「按天」/「按周」—— 最长四个字符。16 给了余量。 */
    public static final int MAX_ENUM_NAME_LENGTH = 16;

    /** IANA 时区 id 最长的那个是 {@code America/Argentina/ComodRivadavia}(32)。64 给了余量。 */
    public static final int MAX_ZONE_ID_LENGTH = 64;

    /**
     * 默认粒度。
     *
     * <p>按天,不是按周:用户打开这个页面最常问的是「我最近有没有在记」,
     * 而按周的第一格要到周日才算完整 —— 周一打开看到「本周 1 条」,
     * 那是个还没写完的数。
     */
    public static final String DEFAULT_GRANULARITY = "day";

    /**
     * 默认给几格、最多几格。
     *
     * <h2>为什么两种粒度共用一个上限</h2>
     *
     * 366 是一年的天数;按周就是七年,<b>比行为层可能存在的历史还长</b>
     * (这个产品 2026 年才开始记第一笔)。所以一个数对两种粒度都够,
     * 不必写成「按天 366、按周 60」—— 两个上限意味着调用方得先知道自己在问哪一种,
     * 才知道传多少会被拒。
     *
     * <h2>为什么不是「全都给我」</h2>
     *
     * 全量那条路在 {@code /export}(docs/10 §6.5「无删减、无水印、不限次数」)。
     * 这个端点是「先看这段」,与 {@code /coverage/blindspots} 的 {@code top ≤ 100} 同一条纪律。
     */
    public static final int DEFAULT_BUCKETS = 30;
    public static final int MAX_BUCKETS = 366;
}
