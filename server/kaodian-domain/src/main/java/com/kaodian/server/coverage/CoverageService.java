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
 * ({@link Touch} + {@link RecordTag})。两者做差集,得出「你还没碰过什么」。
 *
 * <h2>这个类里没有任何学科判断</h2>
 *
 * 全部运算只有两类:<b>计数</b>和<b>比时间</b>。没有判题、没有解析、没有难度模型、
 * 没有掌握度预测,<b>也没有任何一次除法</b> —— 决策记录 §2.2 的能力边界在这里是
 * <b>算法上的事实</b>,不是一句承诺。
 *
 * <h2>🔴 「有没有 / 几次 / 多久前」三件事,没有一件需要小数</h2>
 *
 * 三件事的答案分别是 {@code bool} / {@code int} / 带时区的绝对时间。
 * 一个浮点数出现在这一域,它<b>一定</b>是一个比值 —— 而比值只有两种:掌握度,或百分比,
 * 两种都不许上屏({@code M3-骨架与覆盖度差集} §7.2)。所以这个文件里
 * <b>一个 {@code double} / {@code float} / {@code BigDecimal} 都没有</b>。
 * 这条比字段名黑名单硬:<b>改个名字绕不过它。</b>
 */
public class CoverageService {

    /**
     * 一个考点的完整视图 —— 骨架侧的事实 + 我这边的事实 + 推出来的状态。
     *
     * <h2>字段分三组,分组本身是契约(§9.4)</h2>
     *
     * <table border="1">
     *   <caption>三组字段分别关于谁</caption>
     *   <tr><th>组</th><th>字段</th><th>关于谁</th></tr>
     *   <tr><td>骨架事实</td><td>{@link #recent5yCount}</td><td>关于<b>真题</b></td></tr>
     *   <tr><td>我的事实</td><td>{@link #touchCount} · {@link #lastTouchAt} · {@link #sourceNames}</td>
     *       <td>关于<b>用户</b></td></tr>
     *   <tr><td>节点自身</td><td>{@link #code} · {@link #name} · {@link #state} · {@link #asserted}</td>
     *       <td>——</td></tr>
     * </table>
     *
     * <h2>🔴 这里没有的东西,是结构上没有,不是「查的时候不查」</h2>
     *
     * 没有题干、没有讲解 / 解析 / 例题、没有难度、<b>没有掌握度 / 正确率 / 得分 / 星级</b>、
     * 没有置信度 / 匹配分、没有相似考点、<b>没有任何比值</b>、<b>没有任何天数</b>。
     * 上一版这里有 {@code practiced} / {@code correct} / {@code accuracy()} 三个 ——
     * 它们回答的是「答得怎么样」,正面撞红线一,§7.4 把它们从这一域整个拿掉了。
     *
     * @param syllabusOrder 骨架自然序里的位置。🔴 <b>排序的第二级</b>,让同口径同分的两行
     *                      永远得到同一个顺序,不随 map 遍历顺序抖动
     * @param state         五态。由 {@link NodeState#derive} 一处推出,这里只是存放
     * @param recent5yCount 近五年出现次数。{@code null} = <b>这个考点没有出现次数记录</b> ——
     *                      与「数过了,是 0」是两档,响应里前者 key 不出现、后者 key 在值为 0(§二)
     * @param touchCount    碰过几次。🔴 <b>恒有值</b>,没碰过就是 {@code 0} ——
     *                      「没碰过」这一档由这个 0 表达,不需要第二种形态
     * @param lastTouchAt   最近一次碰过的<b>绝对时刻</b>;没碰过是 {@code null}。
     *                      🔴 服务端<b>不返回「多久前」</b>——「今天 / 昨天 / n 天前」全部由端算
     * @param asserted      用户按过「我已经会了」这个开关吗。🔴 <b>它是那一行的原始存在性,
     *                      不是 {@code state == ASSERTED}</b>:一个断言过之后又碰过的节点
     *                      {@code state} 是 {@code TOUCHED} 而这里仍然是 {@code true} ——
     *                      断言行保留不删(§1.1),否则用户要为一次他从没做过的取消再按一遍
     */
    public record NodeCoverage(
            String code,
            String name,
            String groupCode,
            String groupName,
            int syllabusOrder,
            NodeState state,
            Integer recent5yCount,
            int touchCount,
            Instant lastTouchAt,
            List<String> sourceNames,
            boolean asserted
    ) {
    }

    /**
     * 一个题型的汇总。
     *
     * @param touchedCount   这个题型下碰过的考点数。🔴 <b>由服务端给,端不从子树求和</b> ——
     *                       「前端不做任何一次减法」({@code U3.1} §2.1)在树上同样成立
     * @param untouchedCount 没碰过的考点数。🔴 同样是<b>数出来的</b>,不是
     *                       {@code nodes.size() - touchedCount} 减出来的,理由见 {@link #summarize}
     */
    public record GroupCoverage(
            String code,
            String name,
            List<NodeCoverage> nodes,
            int touchedCount,
            int untouchedCount
    ) {

        /**
         * <b>整块空白</b> —— 这个题型下一个考点都没碰过。
         *
         * <p>决策记录 §2.5:能表达「整块题型都没碰过」是树相对扁平清单的<b>唯一优势</b>。
         * 界面上它由分组头承担,不用图表。
         */
        public boolean whollyEmpty() {
            return touchedCount == 0 && untouchedCount > 0;
        }
    }

    /**
     * 覆盖概览 —— <b>那三个数,加两个单列的计数</b>。
     *
     * <h2>🔴 五个字段,一个浮点都没有,也没有 {@code percent}</h2>
     *
     * 上一版这里有 {@code ratio()} 与 {@code percent()}。{@code 看盲区} §2.9 写死
     * <b>用户侧任何位置不出现百分比</b>,落在契约上就是一句更硬的话:
     * <b>这一域的响应体里没有任何一个浮点字段</b>。
     * <p>
     * {@code 覆盖率 = 行为层 ÷ 骨架层} 作为一个<b>内部量</b>可以存在于日志与运维视图里,
     * 但它不许跨过 HTTP 边界 —— 所以它也不在这个 record 上:<b>一个字段一旦存在,
     * 第二个消费方就会把它送出去</b>。
     *
     * @param nodeTotal     {@code |D|} —— 未归档的骨架叶子节点数
     * @param nodeTouched   {@code |N|} —— 其中有计覆盖度标签的
     * @param nodeUntouched {@code |D∖N|} —— 🔴 <b>数出来的,不是减出来的</b>,见 {@link #summarize}
     * @param archivedCount {@code |R|} —— 归档节点数。🔴 <b>恒在,为 0 也返回</b>({@code R-49}:
     *                      归档三件事都不做成开关)
     * @param assertedCount {@code |A|} —— 「我已经会了」且确实没碰过的节点数。
     *                      🔴 <b>单列,不并入那三个数</b>;它是 {@code nodeUntouched} 的<b>子集</b>
     */
    public record Summary(
            int nodeTotal,
            int nodeTouched,
            int nodeUntouched,
            int archivedCount,
            int assertedCount
    ) {
    }

    /**
     * 骨架 + 行为 + 标签 + 「我已经会了」 → 全树的覆盖视图。<b>这一域唯一的计算入口。</b>
     *
     * <h2>🔴 {@code N} 只能由 {@code D} 过滤而来</h2>
     *
     * 这个循环<b>遍历骨架</b>,对每个节点去问「它上面有没有计覆盖度的标签」;
     * 它<b>不遍历标签</b>再去数不同的 {@code nodeCode}。两种写法今天算出同一个数,
     * 而后者是覆盖度能算出负数的<b>唯一</b>成因:
     * <pre>
     * ❌ touched = COUNT(DISTINCT tag.node_id)   ← 不受 level / archived / subject / 骨架版本约束
     *    untouched = total − touched             ← 四个缺口任意一个被踩到就为负
     * </pre>
     * 四个缺口都是<b>现实可达</b>的:归档发生在打标之后、手动挂载挂到了非叶子上、
     * 多科目、骨架换版。在这个循环里它们<b>全部结构性地不可能</b> ——
     * 一条指向树外节点的标签根本不会被查到({@link NodeState#GONE}),
     * 一条挂在已归档节点上的标签查到了也不进 {@code D}({@link NodeState#ARCHIVED} 优先)。
     *
     * <p>🔴 <b>四条脏数据一律安静忽略,不抛异常、不报错</b> —— 它们是数据问题不是请求问题,
     * 报错会让一屏正常内容因为一条脏标签整个打不开。
     *
     * <h2>四个集合来自同一次遍历</h2>
     *
     * {@code D} / {@code N} / {@code A} / {@code R} 在这一个循环里同时产出,
     * {@link GroupCoverage} 与 {@link Summary} 从同一份中间结果投影。
     * 理由与这个类原本那句话一致:<b>两处算同一个数就一定会算出两个数</b>。
     *
     * @param tags       有效标签,来自 {@link RecordTag#effectiveTagsOf} —— <b>不是</b>
     *                   {@code RecordTagStore.findAll()} 的原样返回:那里面没有推出来的主标签
     * @param assertions 「我已经会了」的全部行。指向树外 / 已归档考点的行被<b>安静忽略</b>
     * @param now        判定基准时刻。🔴 <b>注入,不许 {@code Instant.now()}</b> ——
     *                   「多久前」是这一域的三件事之一,不可注入就不可回放
     */
    public List<GroupCoverage> compute(Syllabus syllabus, List<Touch> touches, List<RecordTag> tags,
                                       List<UserAssertion> assertions, Instant now) {
        Map<String, List<Touch>> byNode = project(touches, tags);

        Set<String> assertedCodes = new HashSet<>();
        for (UserAssertion a : assertions) {
            assertedCodes.add(a.nodeCode());
        }

        List<GroupCoverage> result = new ArrayList<>();
        int order = 0;
        for (Syllabus.Group g : syllabus.groups()) {
            List<NodeCoverage> nodes = new ArrayList<>();
            int touched = 0;
            int untouched = 0;

            // 🔴 g.nodes() 而不是 g.activeNodes():已归档节点也要走一遍,
            //    因为 archivedCount 是响应的必填字段,为 0 也返回(R-49「归档计数常驻可见」)。
            //    它退分子也退分母 —— 那由 NodeState.ARCHIVED 的 inDenominator()/inNumerator()
            //    负责,不由「先过滤掉再说」负责。过滤掉的那一版数不出 archivedCount。
            for (Syllabus.Node n : g.nodes()) {
                List<Touch> ts = byNode.getOrDefault(n.code(), List.of());

                // 五态推导的唯一一处。四个入参都是「有没有」,一个都不是「对不对」。
                // inSyllabus 恒 true —— 我们正在遍历骨架本身,GONE 只可能由外部查询得到。
                NodeState state = NodeState.derive(
                        true, n.archived(), !ts.isEmpty(), assertedCodes.contains(n.code()));

                // 🔴 三个数在同一个循环里各自 ++,谁都不是别人减出来的。
                //    写成 untouched = nodes.size() - touched 的那一版今天恰好也对,
                //    但它把「N ⊆ D」从一条结构事实降级成一条巧合 —— 一旦降级,
                //    上面四个缺口里任何一个被引入时不会有任何东西报错,
                //    屏幕上直接出现「没碰过 −3 个」,而那一屏是这个产品唯一的产出。
                if (state.inNumerator()) {
                    touched++;
                }
                if (state.inBlindSet()) {
                    untouched++;
                }

                Instant latest = null;
                List<String> sources = new ArrayList<>();
                for (Touch t : ts) {
                    if (latest == null || t.occurredAt().isAfter(latest)) {
                        latest = t.occurredAt();
                    }
                    if (t.sourceName() != null && !sources.contains(t.sourceName())) {
                        sources.add(t.sourceName());   // 只有来源【名字】,没有来源的内容
                    }
                }

                nodes.add(new NodeCoverage(
                        n.code(), n.name(), g.code(), g.name(), order++, state,
                        n.recent5yCount(), ts.size(), latest, List.copyOf(sources),
                        assertedCodes.contains(n.code())));
            }
            result.add(new GroupCoverage(g.code(), g.name(), List.copyOf(nodes), touched, untouched));
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
     *       <td>一条记录被手动挂到同一个考点两次时,它的 {@code touchCount} 会被加两遍</td></tr>
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
     * 覆盖概览 —— 五个数,<b>五个都是数出来的</b>。
     *
     * <h2>🔴 {@code nodeUntouched} 不许由减法得出</h2>
     *
     * <pre>
     * 🔴 nodeUntouched = |D ∖ N|                    合法 —— 就是下面这个 ++
     * 🔴 nodeUntouched = nodeTotal − nodeTouched    禁止。哪怕今天两边恰好相等
     * </pre>
     *
     * 为什么连「今天恰好相等」都不许写成减法:减法把 {@code N ⊆ D} 从一条<b>结构事实</b>
     * 降级成一条<b>巧合</b>。恒等式 {@code nodeTouched + nodeUntouched == nodeTotal}
     * 在这里是<b>结论</b>,不是定义 —— 它由 {@link NodeState} 五态互斥且穷尽保证,
     * 所以它可以被测试断言;把它写成定义,它就永远为真,也就永远测不出任何东西。
     *
     * <h2>{@code assertedCount} 为什么在这里数,不去数一遍断言表的行数</h2>
     *
     * {@code |A| = |{ n ∈ B | asserted }|},定义里含着 {@code B = D∖N}。
     * 数断言表行数的那一版在「断言过、后来又碰过」的节点上会比屏上该显示的<b>多一个</b> ——
     * 那个节点的 {@code state} 是 {@code TOUCHED},它<b>不在</b> {@code B} 里。
     */
    public Summary summarize(List<GroupCoverage> groups) {
        int nodeTotal = 0;
        int nodeTouched = 0;
        int nodeUntouched = 0;
        int archivedCount = 0;
        int assertedCount = 0;

        for (GroupCoverage g : groups) {
            for (NodeCoverage n : g.nodes()) {
                NodeState state = n.state();
                if (state.inDenominator()) {
                    nodeTotal++;
                }
                if (state.inNumerator()) {
                    nodeTouched++;
                }
                if (state.inBlindSet()) {
                    nodeUntouched++;
                }
                if (state == NodeState.ARCHIVED) {
                    archivedCount++;
                }
                if (state == NodeState.ASSERTED) {
                    assertedCount++;
                }
            }
        }
        return new Summary(nodeTotal, nodeTouched, nodeUntouched, archivedCount, assertedCount);
    }

    /**
     * 「先补这几个」—— 按给定口径排好序的那一份清单。
     *
     * <h2>🔴 三级排序链在服务端满足</h2>
     *
     * <pre>当前口径  →  骨架自然序  →  nodeId 字典序</pre>
     *
     * 禁止随机、禁止打散、禁止按更新时间兜底。三级之后<b>不可能再有并列</b>
     * ({@code code} 在一棵树里唯一),所以同样的输入永远得到同样的一份清单 ——
     * 「先补这几个」每次刷新换一批,和没有这份清单是一样的。
     *
     * <h2>🔴 排序键缺失的那些,排在该在的一端</h2>
     *
     * {@code recent5y_count} 下没有出现次数记录的沉到<b>末尾</b>(它们提供不了这个口径要的信息);
     * {@code last_touch_at} 下从没碰过的排在<b>最前</b>(从没碰过就是「最久没碰」那一档)。
     * 端只在 key 状态变化处画一条分隔线 —— <b>所以响应里不加 {@code group} / {@code section} 字段</b>,
     * 加一个就是给同一个事实造第二个来源(§9.3)。
     *
     * @param top 要几个。🔴 <b>它不是一个查询参数</b> —— N 的唯一来源是
     *            {@code GET /config/effective} 的 {@code blindspotTop},调用方从那里取
     */
    public List<NodeCoverage> blindSpots(List<GroupCoverage> groups, BlindspotOrder orderBy,
                                         BlindspotFilter filter, boolean hasStatsOnly, int top) {
        List<NodeCoverage> flat = new ArrayList<>();
        for (GroupCoverage g : groups) {
            for (NodeCoverage n : g.nodes()) {
                if (!filter.accepts(n.state())) {
                    continue;                    // 归档节点一档都不进 —— 见 BlindspotFilter
                }
                if (hasStatsOnly && n.recent5yCount() == null) {
                    continue;                    // 「只看有出现次数记录的」
                }
                flat.add(n);
            }
        }
        flat.sort(comparator(orderBy));
        return flat.size() <= top ? List.copyOf(flat) : List.copyOf(flat.subList(0, top));
    }

    /**
     * 三级排序链。<b>第二、三级恒定</b>,只有第一级随口径变。
     *
     * <p>缺键的处理不用 {@code Comparator.nullsFirst/nullsLast} 包一层,而是先比一个
     * {@code int} 档位:{@code nullsFirst} 只在<b>该级</b>决定不了时才往下走,
     * 而这里要的是「缺键的整块聚在一端,块内部继续按二三级排」—— 两者在
     * 「一个缺键、一个不缺」之外的行为一样,在那一格上前者更容易被读成「随便排」。
     */
    private static Comparator<NodeCoverage> comparator(BlindspotOrder orderBy) {
        Comparator<NodeCoverage> primary = switch (orderBy) {
            case RECENT5Y_COUNT -> Comparator
                    .comparingInt((NodeCoverage n) -> missingRank(n.recent5yCount() == null, orderBy))
                    .thenComparingInt(n -> -orElseZero(n.recent5yCount()));      // 多的在前
            case LAST_TOUCH_AT -> Comparator
                    .comparingInt((NodeCoverage n) -> missingRank(n.lastTouchAt() == null, orderBy))
                    .thenComparingLong(n -> n.lastTouchAt() == null ? 0L : n.lastTouchAt().toEpochMilli());
            case TOUCH_COUNT -> Comparator.comparingInt(NodeCoverage::touchCount);   // 少的在前
            case SYLLABUS_ORDER -> Comparator.comparingInt(n -> 0);                  // 一级不区分
        };
        return primary
                .thenComparingInt(NodeCoverage::syllabusOrder)          // 二级:骨架自然序
                .thenComparing(NodeCoverage::code);                     // 三级:字典序,到此必唯一
    }

    /** 缺键的那一块排在最前({@code 0})还是最后({@code 1})。 */
    private static int missingRank(boolean missing, BlindspotOrder orderBy) {
        if (!missing) {
            return orderBy.missingKeyFirst() ? 1 : 0;
        }
        return orderBy.missingKeyFirst() ? 0 : 1;
    }

    private static int orElseZero(Integer value) {
        return value == null ? 0 : value;
    }
}
