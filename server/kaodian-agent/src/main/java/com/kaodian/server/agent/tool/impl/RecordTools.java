package com.kaodian.server.agent.tool.impl;

import com.kaodian.server.agent.tool.spi.AgentTool;
import com.kaodian.server.agent.tool.spi.AtomicTool;
import com.kaodian.server.agent.tool.spi.ToolLevel;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.coverage.CoverageReader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * 行为层的时间线查询,外加一个「现在几点」。
 *
 * <p>与 {@link CoverageTools} 是同一条纪律:返回的是<b>什么时候碰过什么</b>,
 * 不返回碰的时候记了什么内容。{@link Touch} 本身就没有装内容的字段
 * (那是 {@code NoStemFieldTest} 守着的),所以这一层想越界也没有原料 ——
 * 这正是把边界钉在数据模型上、而不是钉在提示词里的好处。
 */
@Component
public class RecordTools implements AgentTool {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final CoverageReader reader;
    private final Clock clock;

    /**
     * @param clock 与 {@code DomainBeans#clock} 是同一个 bean。agent 不自己 {@code Instant.now()} ——
     *              「多久以前」是这个产品的三个词之一,它的基准时刻必须和界面上那个一致
     */
    public RecordTools(CoverageReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    @AtomicTool(level = ToolLevel.READ, displayName = "最近记录", noun = "record", verb = "recent")
    @Tool(name = "recent_records", description = """
            列出最近的学习记录:什么时候、在哪个考点上、通过什么方式(语音记/拍照记/粘一段/记做题/手动挂)、
            来源是什么。用户问「我最近学了什么」「上周都干了什么」时用这个。
            只返回时间、考点名、方式与来源名,不含任何学习内容本身。""")
    public String recentRecords(
            @ToolParam(description = "要列几条,建议 10 到 20,最多 50") int limit) {
        int n = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 50));
        CoverageReader.Snapshot snapshot = reader.read();

        List<Touch> recent = snapshot.touches().stream()
                .sorted(Comparator.comparing(Touch::occurredAt).reversed())
                .limit(n)
                .toList();

        if (recent.isEmpty()) {
            return "还没有任何学习记录。";
        }

        ZoneId zone = clock.getZone();
        StringBuilder sb = new StringBuilder("最近 " + recent.size() + " 条记录:\n");
        for (Touch t : recent) {
            var node = snapshot.syllabus().nodeIncludingArchived(t.nodeCode());
            sb.append("· ").append(DAY.format(t.occurredAt().atZone(zone)))
                    .append("  ").append(node == null ? t.nodeCode() : node.name())
                    .append("  ").append(t.kind().label());
            if (t.sourceName() != null && !t.sourceName().isBlank()) {
                sb.append("  来源:").append(t.sourceName());
            }
            if (t.drill() != null) {
                sb.append("  (自己记的:练 ").append(t.drill().practiced())
                        .append(" 对 ").append(t.drill().correct()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @AtomicTool(level = ToolLevel.READ, displayName = "学习节奏", noun = "record", verb = "cadence")
    @Tool(name = "study_cadence", description = """
            统计最近一段时间的学习节奏:总共记了几条、有几天有记录、最近一次是多久以前。
            用户问「我最近勤快吗」「多久没学了」「这周记了几条」时用这个。
            返回的是计数与天数,不做任何评价。""")
    public String studyCadence(
            @ToolParam(description = "统计最近多少天,例如 7 或 30,最多 365") int days) {
        int window = Math.max(1, Math.min(days <= 0 ? 7 : days, 365));
        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofDays(window));
        ZoneId zone = clock.getZone();

        List<Touch> inWindow = reader.read().touches().stream()
                .filter(t -> t.occurredAt().isAfter(from))
                .toList();

        if (inWindow.isEmpty()) {
            return "最近 " + window + " 天没有任何记录。";
        }

        long activeDays = inWindow.stream()
                .map(t -> t.occurredAt().atZone(zone).toLocalDate())
                .distinct()
                .count();
        Instant latest = inWindow.stream().map(Touch::occurredAt).max(Instant::compareTo).orElse(now);
        long sinceLatest = Duration.between(latest, now).toDays();
        long distinctNodes = inWindow.stream().map(Touch::nodeCode).distinct().count();

        return """
                最近 %d 天:
                记录 %d 条,涉及 %d 个考点
                有记录的天数:%d 天
                最近一次:%s""".formatted(
                window, inWindow.size(), distinctNodes, activeDays,
                sinceLatest <= 0 ? "今天" : sinceLatest + " 天前");
    }

    @AtomicTool(level = ToolLevel.COMPUTE, displayName = "当前时间", noun = "time", verb = "now")
    @Tool(name = "time_now", description = """
            返回当前日期时间。当用户提到「今天」「这周」「最近」这类相对时间时先调它,
            否则模型会按训练数据里的时间来算,算出来的「多久以前」是错的。""")
    public String timeNow() {
        // COMPUTE 而不是 READ:它不碰任何存储。分层不是为了好看 ——
        // 「这一轮有没有读过用户数据」将来要能从 ToolCall 记录上一眼看出来。
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)")
                .withLocale(java.util.Locale.CHINA)
                .format(clock.instant().atZone(clock.getZone()));
    }
}
