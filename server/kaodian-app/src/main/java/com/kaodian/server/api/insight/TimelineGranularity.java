package com.kaodian.server.api.insight;

import com.kaodian.server.api.support.ApiException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 时间线聚合的两种粒度 —— <b>按天 / 按周</b>(docs/技术架构 §6.4「按天/周聚合触达」)。
 *
 * <h2>只有这两个值,没有「月」也没有「自定义天数」</h2>
 *
 * 契约那一行写死了两种。加一个「月」看上去只是多一个 case,实际上它会把
 * {@code buckets} 那个参数的含义变成三选一的猜谜(30 格是三十天、三十周,还是三十个月?);
 * 而「自定义 N 天一格」更糟 —— <b>它不是日历,是滑动窗口</b>,见 {@link #bucketOf} 那一段。
 *
 * <h2>为什么 label 是方法而不是构造器字段</h2>
 *
 * 与 {@link ExportFormat#mediaType} 同一条:一个 {@code String} 实例字段要向
 * {@code NoStemFieldTest} 交代自己的长度上限,而「按天」「按周」是<b>我们自己敲的两个字面量</b>,
 * 长度在编译期就定死了。给它挂 {@code @Size} 是给一个不存在的入口上锁。
 *
 * <h2>这个枚举住在 {@code api} 而不是 {@code api.dto}</h2>
 *
 * 它是<b>查询参数的类型</b>,不是响应体的一部分 —— 响应里出去的是
 * {@code granularity} / {@code granularityLabel} 两个字符串。
 * {@link ExportFormat} 是同样的位置、同样的理由。
 */
public enum TimelineGranularity {

    /** 一天一格。 */
    DAY,

    /** 一周一格,<b>周一起算</b>(见 {@link #bucketOf})。 */
    WEEK;

    /**
     * 请求参数 → 枚举。
     *
     * <p>大小写不敏感,理由与 {@link ExportFormat#ofWireName} 逐字相同:
     * 让一次大小写打错变成 400 没有任何好处。
     *
     * <p>🔴 报错走 {@link ApiException#unknownValue},<b>回声由它截断</b> ——
     * {@code granularity} 是查询参数,没有 {@code @Size} 管得着它。
     * 原样回显等于给「把一整段题干写进响应体和访问日志」开一条最不起眼的路(决策记录 §2.2 不碰内容)。
     */
    public static TimelineGranularity ofWireName(String s) {
        if (s != null) {
            for (TimelineGranularity g : values()) {
                if (g.name().equalsIgnoreCase(s.trim())) {
                    return g;
                }
            }
        }
        throw ApiException.unknownValue("UNKNOWN_GRANULARITY", "聚合粒度(只认 day / week)", s);
    }

    /** 界面上显示的中文名。服务端给,前端不硬编码中文 —— 与 {@code stateLabel} / {@code kindLabel} 同一条。 */
    public String label() {
        return switch (this) {
            case DAY -> "按天";
            case WEEK -> "按周";
        };
    }

    /**
     * 这一天属于哪一格 —— 返回那一格的<b>起始日</b>。
     *
     * <h2>🔴 周从周一起算,而且是日历周不是滑动窗口</h2>
     *
     * 两个决定各有理由:
     * <ul>
     *   <li><b>周一</b>:ISO-8601 的定义,而 docs/技术架构 §六 已经把「时间一律 ISO-8601」
     *       定成全局约定;中国大陆的日历也是周一起。挑周日起算会让服务端的「本周」
     *       和用户手机日历上的「本周」差一天,而这个差别只在跨周的那一天暴露出来。</li>
     *   <li><b>日历周,不是「最近 7 天」</b>:滑动窗口每天都在挪,于是<b>同一段历史
     *       今天看和明天看是两组不同的数</b> —— 「那一周我记了 5 次」这句话会天天变。
     *       这个产品能说的只有「有没有、几次、多久前」(决策记录 §2.2),而「几次」必须是
     *       一个问两遍答案一样的数。</li>
     * </ul>
     */
    public LocalDate bucketOf(LocalDate date) {
        return switch (this) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        };
    }

    /** 这一格的最后一天(含)。按天时就是它自己。 */
    public LocalDate endOf(LocalDate bucketStart) {
        return switch (this) {
            case DAY -> bucketStart;
            case WEEK -> bucketStart.plusDays(6);
        };
    }

    /**
     * 往前/往后挪几格。
     *
     * <p>按周时挪的是 {@code plusWeeks} 而不是 {@code plusDays(7 * n)} —— 结果相同,
     * 但写成前者,「一格有多宽」这件事就只由这个枚举回答,调用方不必知道 7。
     */
    public LocalDate shift(LocalDate bucketStart, long buckets) {
        return switch (this) {
            case DAY -> bucketStart.plusDays(buckets);
            case WEEK -> bucketStart.plusWeeks(buckets);
        };
    }
}
