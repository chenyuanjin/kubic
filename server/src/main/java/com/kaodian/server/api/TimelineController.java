package com.kaodian.server.api;

import com.kaodian.server.api.dto.BucketSourceDto;
import com.kaodian.server.api.dto.TimelineBucketDto;
import com.kaodian.server.api.dto.TimelineResponse;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchStore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时间线聚合 —— 「我这段时间碰过些什么」(docs/10 §6.4「按天/周聚合触达」)。
 *
 * <h2>🔴 这个端点只做统计,不做判断</h2>
 *
 * 它吐出去的每一格里只有三个数:几条记录、几个不同考点、来自哪几个来源。
 * <b>没有趋势、没有连续天数、没有正确率、没有「最活跃的一周」</b> ——
 * 逐条理由写在 {@code TimelineBucketDto} 上,那里是这条红线({@code R-05})真正的落点。
 * <p>
 * 聚合是 {@code R-05} 最容易失守的地方,因为失守的样子非常无害:
 * 相邻两格的数一旦并排摆着,「这周比上周多了 40%」只差一次减法。
 * 减法交给前端不是甩锅 —— <b>字段名就是措辞</b>,契约里出现 {@code improved} 的那天,
 * 前端不写「进步了」反倒成了额外的克制,而那种克制活不过两个版本。
 *
 * <h2>🔴 按北京时间切「一天」,不是 UTC</h2>
 *
 * 用户说的「今天」是北京时间的今天。用 UTC 切,<b>北京时间当天 0 点到 8 点记的那几笔
 * 会被算进「昨天」</b> —— 用户刚记完就翻开时间线,看到的是「昨天 3 条、今天 0 条」。
 * 而「多久前」是这个产品仅有的三个维度之一(01 §2.2),这一格是哪一天不能是个近似值。
 * <p>
 * 这与短信频控里「日按哪个时区算」是同一条决定,理由也逐字相同
 * ({@code kaodian.auth.sms.zone},见 {@code application.properties}:
 * 「用 UTC 算,『明天 0 点恢复』这句话在晚上 8 点之后就是错的」)。
 * <p>
 * 但它<b>不复用那个键</b>:那一条属于短信频控。将来真要给境外号码另切一个短信「日」时,
 * 改那个键会连带把整条时间线重新分桶 —— <b>两件毫不相干的事共用一个开关,
 * 出事的方式一定是「改了 A 坏了 B,而且没人往那边看」。</b>
 *
 * <h2>🔴 时区从配置来,不从 {@code Clock} 来</h2>
 *
 * {@code ApiBeans#clock} 给的是 {@code Clock.systemUTC()},{@code clock.getZone()} 恒为 UTC。
 * 顺手用它会让上面那一整段变成一句空话,<b>而且测试不会红</b> ——
 * 因为跑测试的机器多半就在东八区,{@code Instant.now()} 附近的记录怎么切都落在同一天。
 * 时钟回答的是「现在几点」,时区回答的是「一天从哪儿开始」,这是两个问题。
 *
 * <h2>直接依赖 TouchStore,不走覆盖层</h2>
 *
 * 聚合要的是原始记录本身,不是差集结果。硬拉一遍 {@code CoverageReader#read}
 * 只为拿到同一批记录,是白算一次全树覆盖。
 */
@RestController
public class TimelineController {

    private final TouchStore store;
    private final Clock clock;
    private final ZoneId zone;

    public TimelineController(
            TouchStore store, Clock clock,
            @Value("${kaodian.api.timeline.zone:Asia/Shanghai}") String zone) {
        this.store = store;
        this.clock = clock;
        // 时区名不合法时【启动就炸】,不静默退回 UTC。
        // 退回 UTC 的后果是分桶差 8 小时而一切照常返回 200 —— 没有任何人会发现。
        this.zone = ZoneId.of(zone);
    }

    /**
     * 按天或按周聚合。
     *
     * <h2>窗口锚在「今天」,不锚在最后一条记录上</h2>
     *
     * 锚在最后一条记录上的话,<b>「已经三周没记了」这个事实会从图上整个消失</b> ——
     * 最右边那一格永远是有数的那一格。而这个产品能说的第一件事就是「有没有」。
     *
     * @param granularity {@code day} / {@code week},大小写不敏感。默认按天,
     *                    理由见 {@code TimelineResponse.DEFAULT_GRANULARITY}
     * @param buckets     往回给几格(含当前这一格)。默认 30,上限 366 ——
     *                    两种粒度共用一个上限,理由见 {@code TimelineResponse.MAX_BUCKETS}
     */
    @GetMapping("/api/timeline")
    public TimelineResponse timeline(
            // 🔴 这里刻意【没有】把参数直接声明成枚举。Spring 的枚举转换大小写敏感,
            // 而且失败时走的是兜底那支 —— 用户得到的是一句「请求无法处理:400」,
            // 既不说认哪几个值,也不说是哪个参数错了。ofWireName 自己认、自己报,
            // 与 /api/export 的 format 是同一条路(见 ExportFormat)。
            @RequestParam(defaultValue = TimelineResponse.DEFAULT_GRANULARITY)
            String granularity,

            @RequestParam(defaultValue = "" + TimelineResponse.DEFAULT_BUCKETS)
            @Min(value = 1, message = "至少要 1 格")
            @Max(value = TimelineResponse.MAX_BUCKETS, message = "一次最多 366 格,全量请走导出接口")
            int buckets) {

        TimelineGranularity unit = TimelineGranularity.ofWireName(granularity);

        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate lastStart = unit.bucketOf(today);
        LocalDate firstStart = unit.shift(lastStart, -(buckets - 1L));

        List<Touch> all = store.findAll();

        // 先把窗口内的记录按格分好。窗口外的一条都不进这个 map ——
        // 它们仍然计进 total,只是不属于任何一格(见 TimelineResponse 的 total/counted 那段)。
        Map<LocalDate, List<Touch>> byBucket = new HashMap<>();
        for (Touch t : all) {
            LocalDate start = unit.bucketOf(LocalDate.ofInstant(t.occurredAt(), zone));
            if (start.isBefore(firstStart) || start.isAfter(lastStart)) {
                continue;
            }
            byBucket.computeIfAbsent(start, k -> new ArrayList<>()).add(t);
        }

        // 🔴 顺序来自【走一遍日历】,不是来自遍历上面那个 map。
        // map 里只有【有记录的】格子,顺着它吐等于把空格子悄悄删掉 ——
        // 而「连着五天没记」正是这张图最该说清楚的事(见 TimelineBucketDto)。
        List<TimelineBucketDto> out = new ArrayList<>(buckets);
        int counted = 0;
        for (LocalDate start = firstStart; !start.isAfter(lastStart); start = unit.shift(start, 1)) {
            List<Touch> inBucket = byBucket.getOrDefault(start, List.of());
            counted += inBucket.size();
            out.add(fold(unit, start, inBucket));
        }

        return new TimelineResponse(
                unit.name(), unit.label(), zone.getId(),
                firstStart, unit.endOf(lastStart),
                all.size(), counted, out);
    }

    /** 一格里的三个数。<b>这个方法只会数数</b> —— 它不比较、不排名、不和别的格子说话。 */
    private static TimelineBucketDto fold(TimelineGranularity unit, LocalDate start, List<Touch> touches) {
        Set<String> nodes = new HashSet<>();
        Map<String, Integer> perSource = new HashMap<>();
        for (Touch t : touches) {
            nodes.add(t.nodeCode());
            perSource.merge(t.sourceName(), 1, Integer::sum);
        }

        List<BucketSourceDto> sources = perSource.entrySet().stream()
                .map(e -> new BucketSourceDto(e.getKey(), e.getValue()))
                // 条数多的在前;并列时按名字排。并列不打破的话,同一份数据两次请求会给出两种顺序
                // (HashMap 的迭代顺序不是承诺),前端的图会自己抖起来。
                // 来源名理论上可空(Touch 的构造器不强制它),排序不能因此把整条时间线炸成 500 ——
                // 与 TimelineItemDto 翻不出考点名时不 500 是同一条。
                .sorted(Comparator.comparingInt(BucketSourceDto::touchCount).reversed()
                        .thenComparing(BucketSourceDto::sourceName,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return new TimelineBucketDto(start, unit.endOf(start), touches.size(), nodes.size(), sources);
    }
}
