package com.kaodian.server.coverage;

import com.kaodian.server.collect.Touch;
import com.kaodian.server.syllabus.Syllabus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 整个产品的那一行公式:<b>{@code 盲区 = 骨架层 − 行为层}</b>。
 *
 * <p>骨架层是一棵维护好的考点树({@link Syllabus}),行为层是用户真实碰过的记录
 * ({@link Touch})。两者做差集,得出「你还没碰过什么」。
 *
 * <h2>这个类里没有任何学科判断</h2>
 *
 * 全部运算只有三类:计数、比时间、把用户自己填的两个整数相除。
 * 没有判题、没有解析、没有难度模型、没有掌握度预测 —— 01 §2.2 的能力边界在这里是
 * <b>算法上的事实</b>,不是一句承诺。
 */
public class CoverageService {

    /**
     * 盲区排序权重 —— 「先补这几个」用的就是它。
     *
     * <p>排序分 = {@code 近五年频次 × 状态权重}。两个因子都在能力边界内:
     * 频次是真题统计事实(docs/07),状态由「有没有 / 几次 / 多久前」推出({@link NodeState})。
     *
     * <p><b>权重的排序逻辑:完全没碰过 > 听过没练 > 练了但用户说错得多 > 太久没碰 > 近期练过且用户说还行。</b>
     * 「仅接触」排在「弱」前面,因为听过课但一道题没练,比练过只是错得多更接近盲区 ——
     * 后者至少已经上过手,前者连手都没上过。
     *
     * <p><b>case 一律按权重降序书写。</b> 这不是排版洁癖:这段注释曾经把「弱」和「仅接触」写反过,
     * 错因就是 case 顺序与权重顺序不一致,读代码时对不上号。顺序一致就没有对错可言。
     */
    static double weightOf(NodeState state) {
        return switch (state) {
            case EMPTY -> 1.0;          // 空白 —— 差集的正主
            case TOUCHED_ONLY -> 0.9;   // 听过看过,一道没练
            case WEAK -> 0.8;           // 练过,但用户自填正确率低
            case RUSTY -> 0.7;          // 练过,但超过 30 天没碰
            case STABLE -> 0.0;         // 近期练过且用户说还行 —— 不需要补
        };
    }

    /** 一个考点的完整视图:骨架侧的事实 + 行为侧的统计 + 推出来的状态。 */
    public record NodeCoverage(
            String code,
            String name,
            String groupCode,
            String groupName,
            int recent5yCount,
            NodeState state,
            int touchCount,
            int practiced,
            int correct,
            Instant latestAt,
            List<String> sources
    ) {
        /**
         * 用户自填正确率。{@code null} 表示没练过 —— 界面上显示为「—」,不是 0%。
         *
         * <p>再说一次:这是用户敲进来的两个数相除,不是产品判出来的分。
         */
        public Double accuracy() {
            return practiced == 0 ? null : (double) correct / practiced;
        }

        /**
         * 排序分 = 频次 × 状态权重。
         *
         * <p><b>取一位小数不是四舍五入的洁癖,是因为这个数会直接显示在界面上。</b>
         * IEEE754 下 {@code 7 * 0.8 == 5.6000000000000005},不处理就会原样出现在
         * 「先补这几个」那一栏里。取整放在这里而不是前端,是为了让排序与显示用同一个数 ——
         * 两处各自取整,迟早会出现「显示相同但排序不同」的诡异现象。
         */
        public double blindScore() {
            return Math.round(recent5yCount * weightOf(state) * 10.0) / 10.0;
        }
    }

    /** 一个题型的汇总。{@link #whollyEmpty} 是这棵树相对扁平清单的唯一优势。 */
    public record GroupCoverage(
            String code,
            String name,
            List<NodeCoverage> nodes,
            int coveredCount,
            int recent5yCount
    ) {
        public int nodeCount() {
            return nodes.size();
        }

        /**
         * <b>整块空白</b> —— 这个题型下一个考点都没碰过。
         *
         * <p>01 §2.5:能表达「整块题型都没碰过」是树相对扁平清单的<b>唯一优势</b>。
         * 界面上它由红色分组头承担,不用图表。
         */
        public boolean whollyEmpty() {
            return coveredCount == 0 && !nodes.isEmpty();
        }
    }

    /** 覆盖概览。北极星指标「主动查看盲区的人数」看的就是这一屏。 */
    public record Summary(
            int total,
            int covered,
            int empty,
            int whollyEmptyGroups,
            Map<NodeState, Integer> distribution
    ) {
        /** 覆盖率。分母是考点总数,分子是有记录的考点数。 */
        public double ratio() {
            return total == 0 ? 0 : (double) covered / total;
        }

        /** 取整后的百分比,界面上那个大字。 */
        public int percent() {
            return (int) Math.round(ratio() * 100);
        }
    }

    /** 骨架 + 行为 → 全树的覆盖视图。 */
    public List<GroupCoverage> compute(Syllabus syllabus, List<Touch> touches, Instant now) {
        Map<String, List<Touch>> byNode = new LinkedHashMap<>();
        for (Touch t : touches) {
            byNode.computeIfAbsent(t.nodeCode(), k -> new ArrayList<>()).add(t);
        }

        List<GroupCoverage> result = new ArrayList<>();
        for (Syllabus.Group g : syllabus.groups()) {
            List<NodeCoverage> nodes = new ArrayList<>();
            int covered = 0;

            // activeNodes():已归档的考点退出差集 —— 分母和分子同时少一个,比值仍然诚实,
            // 而它的历史记录一条都没动(见 Syllabus.Group#activeNodes)。
            for (Syllabus.Node n : g.activeNodes()) {
                List<Touch> ts = byNode.getOrDefault(n.code(), List.of());
                NodeState state = NodeState.derive(ts, now);
                if (state.covered()) {
                    covered++;
                }

                int practiced = 0;
                int correct = 0;
                Instant latest = null;
                List<String> sources = new ArrayList<>();
                for (Touch t : ts) {
                    if (t.hasDrill()) {
                        practiced += t.drill().practiced();
                        correct += t.drill().correct();
                    }
                    if (latest == null || t.occurredAt().isAfter(latest)) {
                        latest = t.occurredAt();
                    }
                    if (t.sourceName() != null && !sources.contains(t.sourceName())) {
                        sources.add(t.sourceName());   // 只有来源【名字】,没有来源的内容
                    }
                }

                nodes.add(new NodeCoverage(
                        n.code(), n.name(), g.code(), g.name(), n.recent5yCount(),
                        state, ts.size(), practiced, correct, latest, List.copyOf(sources)));
            }
            result.add(new GroupCoverage(g.code(), g.name(), List.copyOf(nodes), covered, g.recent5yCount()));
        }
        return List.copyOf(result);
    }

    /** 覆盖概览。 */
    public Summary summarize(List<GroupCoverage> groups) {
        Map<NodeState, Integer> dist = new LinkedHashMap<>();
        for (NodeState s : NodeState.values()) {
            dist.put(s, 0);
        }
        int total = 0;
        int covered = 0;
        int whollyEmptyGroups = 0;

        for (GroupCoverage g : groups) {
            if (g.whollyEmpty()) {
                whollyEmptyGroups++;
            }
            for (NodeCoverage n : g.nodes()) {
                total++;
                if (n.state().covered()) {
                    covered++;
                }
                dist.merge(n.state(), 1, Integer::sum);
            }
        }
        return new Summary(total, covered, total - covered, whollyEmptyGroups, Map.copyOf(dist));
    }

    /**
     * 「先补这几个」—— 盲区 Top N。
     *
     * <p>按 {@link NodeCoverage#blindScore()} 降序;<b>同分时按树的顺序</b>,
     * 保证同样的输入永远得到同样的排序,不会因为 map 遍历顺序而抖动。
     *
     * <p>{@code STABLE} 权重为 0,自然落在最后,不需要额外过滤。
     */
    public List<NodeCoverage> blindSpots(List<GroupCoverage> groups, int top) {
        List<NodeCoverage> flat = new ArrayList<>();
        for (GroupCoverage g : groups) {
            flat.addAll(g.nodes());
        }
        List<NodeCoverage> ordered = new ArrayList<>(flat);
        ordered.sort(Comparator
                .comparingDouble(NodeCoverage::blindScore).reversed()
                .thenComparingInt(flat::indexOf));          // 同分 → 树序
        return ordered.stream()
                .filter(n -> n.blindScore() > 0)
                .limit(top)
                .toList();
    }
}
