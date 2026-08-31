package com.kaodian.server.coverage;

import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.UserAssertion;
import com.kaodian.server.syllabus.Syllabus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 整个产品的那一行公式:<b>{@code 盲区 = 骨架层 − 行为层}</b>。
 *
 * <p>骨架层是一棵维护好的考点树({@link Syllabus}),行为层是用户真实碰过的记录
 * ({@link Touch})。两者做差集,得出「你还没碰过什么」。
 *
 * <h2>这个类里没有任何学科判断</h2>
 *
 * 全部运算只有三类:计数、比时间、把用户自己填的两个整数相除。
 * 没有判题、没有解析、没有难度模型、没有掌握度预测 —— 决策记录 §2.2 的能力边界在这里是
 * <b>算法上的事实</b>,不是一句承诺。
 */
public class CoverageService {

    /**
     * 盲区排序权重 —— 「先补这几个」用的就是它。
     *
     * <p>排序分 = {@code 近五年频次 × 状态权重}。两个因子都在能力边界内:
     * 频次是真题统计事实(docs/数据线),状态由「有没有 / 几次 / 多久前」推出({@link NodeState})。
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

    /**
     * 一个考点的完整视图:骨架侧的事实 + 行为侧的统计 + 推出来的状态。
     *
     * @param assertedAt 用户按下「我已掌握」的时刻;<b>没按过是 {@code null}</b>。
     *                   🔴 它<b>不参与</b> {@link #state} 的推导,也不参与覆盖率 ——
     *                   见 {@link UserAssertion} 与 {@link #summarize}。它是<b>独立状态</b>
     *                   (docs/技术架构 §5.2),摆在五态旁边,不是第六态
     */
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
            List<String> sources,
            Instant assertedAt
    ) {
        /** 用户声明过掌握这个考点。<b>与「碰过」无关</b>,两者可以任意组合。 */
        public boolean asserted() {
            return assertedAt != null;
        }

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
         * <p>决策记录 §2.5:能表达「整块题型都没碰过」是树相对扁平清单的<b>唯一优势</b>。
         * 界面上它由红色分组头承担,不用图表。
         */
        public boolean whollyEmpty() {
            return coveredCount == 0 && !nodes.isEmpty();
        }
    }

    /**
     * 覆盖概览。北极星指标「主动查看盲区的人数」看的就是这一屏。
     *
     * @param asserted 声明「我已掌握」的考点数 —— docs/技术架构 §6.4:<b>断言单列不并入</b>。
     *                 它<b>不是</b> {@link #covered} 的一部分,也<b>不是</b> {@link #empty}
     *                 的对立面:一个被声明的考点如果确实一条记录都没有,它<b>同时</b>
     *                 记在 {@code empty} 和这里。两个数相加没有意义,界面上也不该并排求和 ——
     *                 它是另一个维度的计数,理由见 {@link UserAssertion}
     */
    public record Summary(
            int total,
            int covered,
            int empty,
            int whollyEmptyGroups,
            int asserted,
            Map<NodeState, Integer> distribution
    ) {
        /**
         * 覆盖率。分母是考点总数,分子是有记录的考点数。
         *
         * <p>🔴 {@link #asserted} 没有出现在这个式子里,这是它整件事的重点:
         * <b>按「我已掌握」不会让这个数动一下</b>(决策记录 §5.2:补丁不是解法)。
         */
        public double ratio() {
            return total == 0 ? 0 : (double) covered / total;
        }

        /** 取整后的百分比,界面上那个大字。 */
        public int percent() {
            return (int) Math.round(ratio() * 100);
        }
    }

    /**
     * 骨架 + 行为 → 全树的覆盖视图,<b>不看标签表</b>。
     *
     * <p>等价于「每条记录只有采集那一刻挂上的那条主标签,没有人确认过、也没有人丢弃过」——
     * 这正是标签表出现之前的口径,所以它不是一条并行的算法,是
     * {@link #compute(Syllabus, List, List, Instant)} 的一次<b>纯委托</b>:
     * 真正的计算只有一处,不会出现「两处算同一个数就一定会算出两个数」。
     */
    public List<GroupCoverage> compute(Syllabus syllabus, List<Touch> touches, Instant now) {
        return compute(syllabus, touches, RecordTag.effectiveTagsOf(touches, List.of()), now);
    }

    /**
     * 骨架 + 行为 + <b>标签</b> → 全树的覆盖视图。
     *
     * <h2>🔴 覆盖度的分子由标签数出来,不由记录数出来</h2>
     *
     * docs/技术架构 §6.4:「分子 = <b>{@code discarded=0}</b> 的触达节点数」;
     * docs/技术架构 §5.2:「{@code discarded=1} 即宁缺毋滥的落地:<b>可见,但不计覆盖度</b>」({@code P1-7})。
     * 落到这里就是 {@link #project} 里那一句 {@code countsInCoverage()} 的过滤 ——
     * 被丢弃的标签仍然查得到、看得见,只是不再把它那个考点算成「碰过」。
     * <p>
     * 这条口径必须在这一层实现,不能留给接口层「查的时候顺手过滤一下」:
     * 覆盖率、五态、盲区排序三个数都是从这里出去的,漏掉任何一个都会让同一屏上出现两套口径。
     *
     * @param tags 有效标签,来自 {@link RecordTag#effectiveTagsOf} ——
     *             <b>不是</b> {@code RecordTagStore.findAll()} 的原样返回:
     *             那里面没有推出来的主标签,直接拿来算会让绝大多数记录凭空消失
     */
    public List<GroupCoverage> compute(Syllabus syllabus, List<Touch> touches,
                                       List<RecordTag> tags, Instant now) {
        return compute(syllabus, touches, tags, List.of(), now);
    }

    /**
     * 骨架 + 行为 + 标签 + <b>「我已掌握」</b> → 全树的覆盖视图。
     *
     * <h2>🔴 断言是第五个输入,而不是第六种状态</h2>
     *
     * 它<b>只被记在 {@link NodeCoverage#assertedAt} 上</b>,不参与 {@link NodeState#derive}、
     * 不改 {@code covered}、不改 {@code blindScore}。三处口径全在下游:
     * <table border="1">
     *   <caption>断言在三处的落法(docs/技术架构 §6.4 / §5.2)</caption>
     *   <tr><th>口径</th><th>怎么落</th><th>在哪</th></tr>
     *   <tr><td>覆盖率<b>分子不变</b></td>
     *       <td>什么都不做 —— {@code covered} 只数 {@code state().covered()}</td>
     *       <td>就在下面这个循环里,<b>看不到 asserted 这个词</b></td></tr>
     *   <tr><td>概览<b>单列一格</b></td><td>数一遍 {@code asserted()}</td>
     *       <td>{@link #summarize}</td></tr>
     *   <tr><td>盲区榜<b>排除</b></td><td>过滤掉 {@code asserted()}</td>
     *       <td>{@link #blindSpots}</td></tr>
     * </table>
     *
     * <p>为什么放进 {@code compute} 而不是让三个查询端点各自去查一次声明表:
     * 与类注释里那句「两处算同一个数就一定会算出两个数」是同一条 ——
     * 覆盖率、盲区榜、树上的格子必须来自<b>同一次读取</b>,否则同一屏上会出现
     * 「盲区榜里没有它,但树上它没有已掌握标记」这种自相矛盾的画面。
     *
     * @param assertions 「我已掌握」的全部行。指向树外 / 已归档考点的行会被<b>安静地忽略</b>:
     *                   那不是错误,是考点被删了或被归档了,而归档本来就退出差集
     */
    public List<GroupCoverage> compute(Syllabus syllabus, List<Touch> touches, List<RecordTag> tags,
                                       List<UserAssertion> assertions, Instant now) {
        Map<String, List<Touch>> byNode = project(touches, tags);

        Map<String, Instant> assertedAt = new LinkedHashMap<>();
        for (UserAssertion a : assertions) {
            // 同一个考点最多一行(主键就是 nodeCode,见 FileAssertionStore),
            // 万一文件被手工改出两行,取先写下的那条 —— 与「重复断言不刷新时刻」同一句话。
            assertedAt.putIfAbsent(a.nodeCode(), a.assertedAt());
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
                    // 🔴 这一句里没有 assertedAt。「我已掌握」不让任何考点变成「碰过」——
                    //    决策记录 §5.2:它是补丁不是解法,而一个能靠点按钮刷高的覆盖率
                    //    与没有覆盖率是一样的。加一句 `|| asserted` 就是把补丁伪装成疗效。
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
                        state, ts.size(), practiced, correct, latest, List.copyOf(sources),
                        assertedAt.get(n.code())));
            }
            result.add(new GroupCoverage(g.code(), g.name(), List.copyOf(nodes), covered, g.recent5yCount()));
        }
        return List.copyOf(result);
    }

    /**
     * 标签 → 「哪个考点下挂着哪些记录」。<b>差集运算真正的输入。</b>
     *
     * <h2>三条规则,每条都对应一个会算错的写法</h2>
     *
     * <table border="1">
     *   <caption>project 的三条规则</caption>
     *   <tr><th>规则</th><th>不这么写会怎样</th></tr>
     *   <tr><td>丢弃的标签跳过</td>
     *       <td>用户说过「不是这个考点」,覆盖度却还算着它 —— 盲区永远不肯回来({@code P1-7})</td></tr>
     *   <tr><td>指不到记录的标签跳过</td>
     *       <td>记录删了、标签行还在时,那个考点会凭空保持「碰过」</td></tr>
     *   <tr><td>同一记录同一考点只算一次</td>
     *       <td>一条记录被手动挂到同一个考点两次(或补标与手动挂撞上),
     *           它的做题数会被<b>加两遍</b> —— 而做题数直接决定五态里的「弱」</td></tr>
     * </table>
     *
     * <p>每个考点下的记录按<b>行为层原本的顺序</b>重排,而不是按标签的顺序。
     * 来源名集合是「按首次出现顺序」出接口的,跟着标签顺序走会让界面上那一列悄悄换序,
     * 而不会有任何一条断言红。
     */
    private static Map<String, List<Touch>> project(List<Touch> touches, List<RecordTag> tags) {
        Map<String, Touch> byId = new LinkedHashMap<>();
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < touches.size(); i++) {
            Touch t = touches.get(i);
            byId.put(t.id(), t);
            order.put(t.id(), i);
        }

        Map<String, List<Touch>> byNode = new LinkedHashMap<>();
        Set<String> counted = new HashSet<>();
        for (RecordTag tag : tags) {
            if (!tag.countsInCoverage()) {
                continue;                                   // 可见,但不计覆盖度
            }
            Touch t = byId.get(tag.recordId());
            if (t == null) {
                continue;                                   // 标签指向的记录已经不在了
            }
            // 分隔符写成 \u0000 的转义,不写成一个真的 NUL 字节:后者会让整个源文件在 grep / file
            // 眼里变成二进制,而这个仓库有好几条红线断言是【把生效代码当文本扫一遍】的
            // (ImageRetentionTest)。选 \u0000 本身是因为它不可能出现在 code 或 id 里,
            // 换成 '-' 之类的话,("a-b", "c") 与 ("a", "b-c") 会拼成同一个键 —— 两条不同的挂载被当成一条。
            if (!counted.add(tag.nodeCode() + '\u0000' + tag.recordId())) {
                continue;
            }
            byNode.computeIfAbsent(tag.nodeCode(), k -> new ArrayList<>()).add(t);
        }
        for (List<Touch> list : byNode.values()) {
            list.sort(Comparator.comparingInt(t -> order.get(t.id())));
        }
        return byNode;
    }

    /**
     * 覆盖概览。
     *
     * <h2>🔴 「我已掌握」在这里<b>单列一格</b>,不并入任何一个已有的数</h2>
     *
     * docs/技术架构 §6.4:「分母 = level 3 节点数;分子 = {@code discarded=0} 的触达节点数;
     * <b>断言单列不并入</b>」。所以下面 {@code asserted} 是自己数自己的一遍循环变量,
     * 它<b>不加进 {@code covered}</b>(那会让覆盖率因为点按钮而上升)、
     * <b>也不从 {@code empty} 里减掉</b>(那个考点确实还是一条记录都没有)、
     * <b>更不占 {@code distribution} 里的一格</b>(五态是从记录推出来的,断言不是记录)。
     * <p>
     * 这三个「不」合起来的效果是:一个用户把 18 个考点全部声明掌握之后,这一屏上
     * <b>只有一个数变了</b> —— 「已声明 18 个」。覆盖率还是 44%,盲区还是 10 个。
     * 变的只是他不会再在「先补这几个」里看到它们({@link #blindSpots})。
     */
    public Summary summarize(List<GroupCoverage> groups) {
        Map<NodeState, Integer> dist = new LinkedHashMap<>();
        for (NodeState s : NodeState.values()) {
            dist.put(s, 0);
        }
        int total = 0;
        int covered = 0;
        int whollyEmptyGroups = 0;
        int asserted = 0;

        for (GroupCoverage g : groups) {
            if (g.whollyEmpty()) {
                whollyEmptyGroups++;
            }
            for (NodeCoverage n : g.nodes()) {
                total++;
                if (n.state().covered()) {
                    covered++;
                }
                if (n.asserted()) {
                    asserted++;
                }
                dist.merge(n.state(), 1, Integer::sum);
            }
        }
        return new Summary(total, covered, total - covered, whollyEmptyGroups,
                asserted, Map.copyOf(dist));
    }

    /**
     * 「先补这几个」—— 盲区 Top N。
     *
     * <p>按 {@link NodeCoverage#blindScore()} 降序;<b>同分时按树的顺序</b>,
     * 保证同样的输入永远得到同样的排序,不会因为 map 遍历顺序而抖动。
     *
     * <p>{@code STABLE} 权重为 0,自然落在最后,不需要额外过滤。
     *
     * <h2>🔴 已经声明「我已掌握」的考点<b>排除在外</b>(docs/技术架构 §6.4)</h2>
     *
     * 这是断言这个按钮<b>唯一真正做的事</b> —— 用户按它,要的就是这份清单别再提它。
     * 覆盖率不动、五态不动、分母不动,只有这一份清单短了一行。
     * <p>
     * 过滤必须排在 {@code limit(top)} <b>之前</b>:排在后面的话,声明过的考点会先占掉名额、
     * 再被删掉,于是「要 5 个」返回 3 个,而榜上明明还有别的盲区。
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
                .filter(n -> !n.asserted())      // 🔴 排除已断言节点(docs/技术架构 §6.4),必须在 limit 之前
                .limit(top)
                .toList();
    }
}
