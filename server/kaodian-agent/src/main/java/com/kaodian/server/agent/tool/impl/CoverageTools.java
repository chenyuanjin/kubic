package com.kaodian.server.agent.tool.impl;

import com.kaodian.server.agent.tool.spi.AgentTool;
import com.kaodian.server.agent.tool.spi.AtomicTool;
import com.kaodian.server.agent.tool.spi.ToolLevel;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.coverage.CoverageService.Summary;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;

/**
 * 覆盖率与盲区 —— agent 工具池里最主要的那几个。
 *
 * <h2>🔴 这三个工具的返回值,就是能力边界的实际形状</h2>
 *
 * 01 §2.2 的能力边界是「只答<b>有没有、几次、多久前</b>,永不判断<b>对不对</b>」。
 * 这条边界写在提示词里只是一句请求;写在这里才是一条约束 ——
 * <b>模型能说什么,上限是它能查到什么</b>。
 * <p>
 * 所以下面每个方法返回的都是<b>计数、状态、时间差</b>,没有一个字段装着题目内容或对错判定。
 * 想让 agent 说出「你这道题错在没看清题干」,它得先有一个能拿到题干的工具 —— 而那个工具不存在,
 * 也不该被加进来。
 *
 * <p><b>正确率是个例外,而它恰恰证明了这条边界。</b>{@link NodeCoverage#accuracy()} 是有值的,
 * 但那是<b>用户自己敲进来的两个数相除</b>(练了几道 / 对了几道),不是产品判出来的分。
 * 所以下面照样报它 —— 报的是「你自己记下来的数」,不是「我认为你掌握得怎么样」。
 * 措辞上的这点区别不是文字游戏:它决定了这个产品是记录工具还是判分工具。
 */
@Component
public class CoverageTools implements AgentTool {

    /**
     * 取数走 {@link CoverageReader} —— 与 HTTP 的四个查询端点<b>同一个入口</b>。
     *
     * <p>agent 自己再拼一次「骨架 + 行为 + 标签 + 断言」是行得通的,也正是不能做的:
     * 那样界面上显示 44%、agent 嘴里说 43%,而两个数都「对」。
     * 覆盖率是这个产品唯一的那个数,它不能有第二个算法。
     */
    private final CoverageReader reader;

    public CoverageTools(CoverageReader reader) {
        this.reader = reader;
    }

    @AtomicTool(level = ToolLevel.READ, displayName = "覆盖率概览", noun = "coverage", verb = "summary")
    @Tool(name = "coverage_summary", description = """
            查询当前学习覆盖率概览:一共多少个考点、碰过多少个、覆盖率百分之几、
            五种状态(空白/仅接触/生疏/弱/稳)各有多少个、用户声明「已掌握」的有多少个。
            用户问「我学得怎么样」「进度如何」「还剩多少没看」时用这个。
            返回的是计数,不含任何题目内容,也不含对错判定。""")
    public String coverageSummary() {
        CoverageReader.Snapshot snapshot = reader.read();
        Summary s = reader.summarize(snapshot);

        StringJoiner states = new StringJoiner("、");
        s.distribution().forEach((state, count) -> {
            if (count > 0) {
                states.add(state.label() + " " + count + " 个");
            }
        });

        return """
                考点总数:%d
                已碰过:%d(覆盖率 %d%%)
                完全空白:%d
                整组一次没碰过的题型:%d 组
                用户声明已掌握:%d 个(注:这个数【不计入】上面的覆盖率)
                状态分布:%s""".formatted(
                s.total(), s.covered(), s.percent(), s.empty(),
                s.whollyEmptyGroups(), s.asserted(),
                states.length() == 0 ? "(无)" : states.toString());
    }

    @AtomicTool(level = ToolLevel.READ, displayName = "盲区清单", noun = "blindspot", verb = "list")
    @Tool(name = "blindspot_list", description = """
            列出最该优先补的考点(盲区)。按「近五年考频 × 状态权重」排序,
            用户已经声明「我已掌握」的考点不会出现在这里。
            用户问「我该学什么」「哪里最薄弱」「先补哪个」时用这个。
            返回考点名称与计数,不含题目内容。""")
    public String blindSpotList(
            @ToolParam(description = "要列几个,建议 5 到 10,最多 20") int top) {
        int limit = Math.max(1, Math.min(top <= 0 ? 5 : top, 20));
        CoverageReader.Snapshot snapshot = reader.read();
        List<NodeCoverage> spots = reader.blindSpots(snapshot, limit);

        if (spots.isEmpty()) {
            return "当前没有盲区 —— 所有考点要么碰过,要么已被声明掌握。";
        }
        StringBuilder sb = new StringBuilder("最该优先补的 " + spots.size() + " 个考点(按考频×状态排序):\n");
        int rank = 1;
        for (NodeCoverage n : spots) {
            sb.append(rank++).append(". ").append(n.name())
                    .append("(").append(n.groupName()).append(")")
                    .append(" —— 状态:").append(n.state().label())
                    .append(",近五年考过 ").append(n.recent5yCount()).append(" 次")
                    .append(",你碰过 ").append(n.touchCount()).append(" 次")
                    .append(ago(n.latestAt(), snapshot.at()))
                    .append("\n");
        }
        return sb.toString();
    }

    @AtomicTool(level = ToolLevel.READ, displayName = "考点详情", noun = "node", verb = "detail")
    @Tool(name = "node_detail", description = """
            查一个具体考点上的记录情况:碰过几次、最近一次多久以前、来源有哪些、
            用户自己记的练习数与正确数、是否已声明掌握。
            用户问「XX 我碰过吗」「XX 最近一次是什么时候」时用这个。
            参数是考点名称的关键词,会做模糊匹配。""")
    public String nodeDetail(
            @ToolParam(description = "考点名称或其中的关键词,例如「资料分析」「增长率」") String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "没有给出考点关键词,无法查询。";
        }
        CoverageReader.Snapshot snapshot = reader.read();
        String needle = keyword.trim();

        List<NodeCoverage> matched = snapshot.groups().stream()
                .flatMap(g -> g.nodes().stream())
                .filter(n -> n.name().contains(needle) || needle.contains(n.name()))
                .limit(5)
                .toList();

        if (matched.isEmpty()) {
            // 🔴 查不到就说查不到。不猜、不给「你可能是想问……」——
            // 宁缺毋滥(01 §2.2):在这一层猜错,用户看到的是一条关于他没学过的考点的假记录。
            return "骨架里没有匹配「" + needle + "」的考点。可以先用 coverage_summary 看看有哪些题型。";
        }

        StringBuilder sb = new StringBuilder();
        for (NodeCoverage n : matched) {
            sb.append("【").append(n.name()).append("】(").append(n.groupName()).append(")\n")
                    .append("  状态:").append(n.state().label())
                    .append(",近五年考过 ").append(n.recent5yCount()).append(" 次\n")
                    .append("  你碰过:").append(n.touchCount()).append(" 次")
                    .append(ago(n.latestAt(), snapshot.at())).append("\n");
            if (!n.sources().isEmpty()) {
                sb.append("  来源:").append(String.join("、", n.sources())).append("\n");
            }
            if (n.practiced() > 0) {
                // 用户自填的两个数。报数,不评价。
                sb.append("  你自己记的:练了 ").append(n.practiced())
                        .append(" 道,对了 ").append(n.correct()).append(" 道\n");
            }
            if (n.asserted()) {
                sb.append("  你已声明掌握这个考点\n");
            }
        }
        return sb.toString();
    }

    /** 「多久以前」—— 能力边界里的第三个词。没碰过时明确说没碰过,不说「0 天前」。 */
    private static String ago(Instant latest, Instant now) {
        if (latest == null) {
            return ",一次都没碰过";
        }
        long days = Duration.between(latest, now).toDays();
        if (days <= 0) {
            return ",最近一次就在今天";
        }
        return ",最近一次在 " + days + " 天前";
    }
}
