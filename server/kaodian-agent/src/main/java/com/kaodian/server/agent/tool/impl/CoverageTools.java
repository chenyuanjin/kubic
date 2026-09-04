package com.kaodian.server.agent.tool.impl;

import com.kaodian.server.agent.tool.spi.AgentTool;
import com.kaodian.server.agent.tool.spi.AtomicTool;
import com.kaodian.server.agent.tool.spi.ToolLevel;
import com.kaodian.server.coverage.BlindspotFilter;
import com.kaodian.server.coverage.BlindspotOrder;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.coverage.CoverageService.Summary;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 覆盖率与盲区 —— agent 工具池里最主要的那几个。
 *
 * <h2>🔴 这三个工具的返回值,就是能力边界的实际形状</h2>
 *
 * 决策记录 §2.2 的能力边界是「只答<b>有没有、几次、多久前</b>,永不判断<b>对不对</b>」。
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
            查询当前的覆盖概览:一共多少个考点、碰过多少个、还没碰过多少个、
            已归档多少个、用户声明「我已经会了」的有多少个。
            用户问「我学得怎么样」「进度如何」「还剩多少没看」时用这个。
            返回的是计数,不含任何题目内容,不含对错判定,也不含任何百分比。""")
    public String coverageSummary() {
        CoverageReader.Snapshot snapshot = reader.read();
        Summary s = reader.summarize(snapshot);

        // 🔴 这里不算百分比,也不把三个数相除。看盲区 §2.9:用户侧任何位置不出现百分比 ——
        //    而 agent 说出来的话就是用户侧,它比屏幕更难被扫描到。
        return """
                考点总数:%d
                碰过:%d
                还没碰过:%d
                已归档:%d(归档的考点不进上面那三个数)
                你说会了:%d 个(注:这个数【不改变】上面那三个数)""".formatted(
                s.nodeTotal(), s.nodeTouched(), s.nodeUntouched(),
                s.archivedCount(), s.assertedCount());
    }

    @AtomicTool(level = ToolLevel.READ, displayName = "盲区清单", noun = "blindspot", verb = "list")
    @Tool(name = "blindspot_list", description = """
            列出还没碰过的考点(盲区),按近五年考过的次数从多到少排。
            用户已经声明「我已经会了」的考点不会出现在这里。
            用户问「我该学什么」「先补哪个」「还有哪些没碰过」时用这个。
            返回考点名称与计数,不含题目内容,不判断哪个更薄弱。""")
    public String blindSpotList(
            @ToolParam(description = "要列几个,建议 5 到 10,最多 20") int top) {
        int limit = Math.max(1, Math.min(top <= 0 ? 5 : top, 20));
        CoverageReader.Snapshot snapshot = reader.read();
        // 🔴 与 HTTP 端点同一个口径同一个入口。UNTOUCHED 那一档天然排除了已断言的节点 ——
        //    ASSERTED 是一个独立取值,不是 UNTOUCHED 的一个附加标记。
        List<NodeCoverage> spots = reader.blindSpots(
                snapshot, BlindspotOrder.RECENT5Y_COUNT, BlindspotFilter.UNTOUCHED, false, limit);

        if (spots.isEmpty()) {
            return "当前没有盲区 —— 所有考点要么碰过,要么你已经说过会了。";
        }
        StringBuilder sb = new StringBuilder("还没碰过的 " + spots.size() + " 个考点(近五年考得多的排在前面):\n");
        int rank = 1;
        for (NodeCoverage n : spots) {
            sb.append(rank++).append(". ").append(n.name())
                    .append("(").append(n.groupName()).append(")")
                    .append(",近五年考过 ").append(n.recent5yCount() == null ? "?" : n.recent5yCount())
                    .append(" 次")
                    .append(",你碰过 ").append(n.touchCount()).append(" 次")
                    .append(ago(n.lastTouchAt(), snapshot.at()))
                    .append("\n");
        }
        return sb.toString();
    }

    @AtomicTool(level = ToolLevel.READ, displayName = "考点详情", noun = "node", verb = "detail")
    @Tool(name = "node_detail", description = """
            查一个具体考点上的记录情况:碰过几次、最近一次多久以前、来源有哪些、
            是否已经说过「我已经会了」。
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
            // 宁缺毋滥(决策记录 §2.2):在这一层猜错,用户看到的是一条关于他没学过的考点的假记录。
            return "骨架里没有匹配「" + needle + "」的考点。可以先用 coverage_summary 看看有哪些题型。";
        }

        StringBuilder sb = new StringBuilder();
        for (NodeCoverage n : matched) {
            sb.append("【").append(n.name()).append("】(").append(n.groupName()).append(")\n")
                    .append("  近五年考过 ").append(n.recent5yCount() == null ? "?" : n.recent5yCount())
                    .append(" 次\n")
                    .append("  你碰过:").append(n.touchCount()).append(" 次")
                    .append(ago(n.lastTouchAt(), snapshot.at())).append("\n");
            if (!n.sourceNames().isEmpty()) {
                sb.append("  来源:").append(String.join("、", n.sourceNames())).append("\n");
            }
            // 🔴 上一版这里还报「你自己记的:练了 N 道,对了 M 道」。它去掉了:
            //    agent 说出口的每一句都是用户侧,而「对了几道」在一段自然语言里
            //    与「你做得怎么样」只差一个逗号 —— 而这个产品从没判过任何一道题。
            //    「有没有 / 几次 / 多久前」三件事,上面三行已经答完。
            if (n.asserted()) {
                sb.append("  你说过这个考点你已经会了\n");
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
