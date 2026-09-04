package com.kaodian.server.coverage;

import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.UserAssertion;
import com.kaodian.server.coverage.CoverageService.GroupCoverage;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.coverage.CoverageService.Summary;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「我已掌握」的三处口径 —— <b>这个文件的中心是一条「三个数一个都没变」的断言</b>。
 *
 * <h2>为什么单开一个文件</h2>
 *
 * 与 {@code CoverageTagCaliberTest} 单开的理由逐字相同:{@code CoverageServiceTest} 钉的是
 * <b>数据契约那组数</b>,{@code CoverageTagCaliberTest} 钉的是<b>标签口径</b>,
 * 这里钉的是<b>断言口径</b>。三件事混在一起,任何一处口径改动都会让另外两组数一起红,
 * 而修的人分不清是哪一条坏了,最省事的做法是把数字改成新跑出来的值 ——
 * 那一刻三条断言一起失效,而且没人知道。
 *
 * <h2>🔴 「什么都没变」这句话现在比上一版更硬,不是更软</h2>
 *
 * 上一版说的是「那个百分比一个字都没动」。百分比已经没有了(§7.2:这一域的响应里没有浮点),
 * 于是这句话落到了三个整数上:<b>{@code nodeTotal} / {@code nodeTouched} / {@code nodeUntouched}
 * 三个都不动,只有 {@code assertedCount} 加一</b>。三个数比一个比值好守 ——
 * 比值可以靠上下同时变而看起来没动,三个整数不行。
 * <p>
 * 这条口径的失败方式是<b>无声的</b>:在 {@link NodeState#inNumerator()} 里加一句
 * {@code || this == ASSERTED},接口全绿、界面更好看、用户更满意,
 * 而这个产品唯一的那个数字从此不再指向任何真实的东西。
 *
 * <h2>🔴 {@code ASSERTED} 是<b>五态里的一个</b>,不是第六态</h2>
 *
 * 优先级链 {@code GONE > ARCHIVED > TOUCHED > ASSERTED > UNTOUCHED} 把
 * {@code ASSERTED ⊆ 没碰过} 从一条要靠自觉的约定变成了结构事实:
 * 一个已经碰过的节点<b>不可能</b>因为按了按钮而变成 {@code ASSERTED},
 * 所以它也不可能从分子里掉出来。{@link #assertingAnAlreadyTouchedNodeStaysTouched} 钉的就是这一格。
 *
 * <h2>⚠️ 断言不是归档 —— 同一类问题的两个不同答案</h2>
 *
 * 归档({@code R-49})把考点从<b>分母</b>里拿掉;断言把考点<b>留在分母里</b>、不进分子、单列一格。
 * {@link #assertingEveryNodeChangesOnlyOneNumber} 与 {@link #archivedNodesCannotBeAsserted}
 * 两条合起来钉的就是这个区别。
 */
class CoverageAssertionCaliberTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    /** 测试用户 —— 与行为层种子同一个 id(B0 §3.3:auth 侧从 10001 起号)。 */
    private static final long USER = 10001L;
    private static final Instant ASSERTED_AT = Instant.parse("2026-08-20T09:00:00Z");

    private final CoverageService service = new CoverageService();
    private final Syllabus syllabus = SyllabusLoader.loadDefault();

    /** 与 {@code CoverageServiceTest.contractTouches} 同一批数据:8 个考点碰过、10 个没碰过。 */
    private List<Touch> contractTouches() {
        List<Touch> ts = new ArrayList<>();
        drill(ts, "growth-rate", "粉笔 · 资料分析系统班 L12", 12, 10, 0);
        drill(ts, "share-calc", "华图 · 资料速算网课", 9, 8, 1);
        drill(ts, "feature-number", "自己刷题 · 2023 国考真题", 7, 6, 3);
        drill(ts, "growth-amount", "自己刷题 · 2023 国考真题", 8, 4, 2);
        drill(ts, "truncate-divide", "B站 · 资料分析技巧", 6, 2, 4);
        drill(ts, "base-value", "中公 · 资料分析专项", 5, 4, 32);
        drill(ts, "interval-growth", "中公 · 资料分析专项", 3, 2, 33);
        ts.add(new Touch("t-share-change", USER, "share-change",
                "粉笔 · 资料分析系统班 L12", TouchKind.VOICE, daysAgo(5), null, null));
        return ts;
    }

    private void drill(List<Touch> ts, String node, String source, int practiced, int correct, int daysAgo) {
        ts.add(new Touch("t-" + node, USER, node, source, TouchKind.DRILL, daysAgo(daysAgo),
                new Touch.Drill(practiced, correct), null));
    }

    private Instant daysAgo(int d) {
        return NOW.minus(Duration.ofDays(d));
    }

    private List<GroupCoverage> groupsWith(String... assertedCodes) {
        return groupsWith(syllabus, assertedCodes);
    }

    private List<GroupCoverage> groupsWith(Syllabus tree, String... assertedCodes) {
        List<Touch> touches = contractTouches();
        List<UserAssertion> assertions = new ArrayList<>();
        for (String code : assertedCodes) {
            assertions.add(new UserAssertion(USER, code, ASSERTED_AT));
        }
        return service.compute(tree, touches, RecordTag.effectiveTagsOf(touches, List.of()),
                List.copyOf(assertions), NOW);
    }

    /**
     * 默认档的「先补这几个」—— {@code UNTOUCHED} + {@code recent5y_count}。
     *
     * <p>🔴 已断言的节点<b>不在这一档的取值集合里</b>({@link BlindspotFilter#accepts}),
     * 所以「盲区榜排除已断言」不是一条额外的过滤规则,是这一档定义本身。
     */
    private List<NodeCoverage> defaultBoard(List<GroupCoverage> groups, int top) {
        return service.blindSpots(groups, BlindspotOrder.RECENT5Y_COUNT, BlindspotFilter.UNTOUCHED, false, top);
    }

    private static NodeCoverage node(List<GroupCoverage> groups, String code) {
        return groups.stream().flatMap(g -> g.nodes().stream())
                .filter(n -> n.code().equals(code)).findFirst().orElseThrow();
    }

    private static List<String> codes(List<NodeCoverage> nodes) {
        return nodes.stream().map(NodeCoverage::code).toList();
    }

    // ———————————— 一、那三个数不动。这是整件事的重点 ————————————

    /**
     * 🔴 这一条如果被删掉或改松,「我已掌握」就退化成一个刷分按钮。
     *
     * <p>前后两组各写死五个字面量,而不是写 {@code before.nodeTouched() == after.nodeTouched()}:
     * 后者在有人把断言并进分子时会<b>两边一起变</b>,断言照样绿。
     */
    @Test
    @DisplayName("🔴 按下「我已掌握」之后,三个数一个都没动,只有「已声明」加一(决策记录 §5.2:补丁不是解法)")
    void assertingANodeMovesNothingButTheAssertedCount() {
        Summary before = service.summarize(groupsWith());
        assertEquals(18, before.nodeTotal());
        assertEquals(8, before.nodeTouched());
        assertEquals(10, before.nodeUntouched());
        assertEquals(0, before.assertedCount());

        // average-calc 是一个彻头彻尾没碰过的考点 —— 最容易被「按一下就算碰过」
        Summary after = service.summarize(groupsWith("average-calc"));

        assertEquals(18, after.nodeTotal(), "分母不该动 —— 那是归档干的事,不是断言");
        assertEquals(8, after.nodeTouched(),
                "🔴 分子动了 —— 断言被算进了覆盖度。声明不是触达(docs/technical/INDEX.md §6.4),"
                        + "决策记录 §5.2:「我已掌握」按钮是补丁不是解法");
        assertEquals(10, after.nodeUntouched(),
                "🔴 差集也不该动 —— 那个考点确实还是一条记录都没有,声明改不了这件事;"
                        + "assertedCount 是它的【子集】,不是从它里面减掉的一块");
        assertEquals(0, after.archivedCount());
        assertEquals(1, after.assertedCount(), "唯一该变的就是这一个数(§6.4:断言单列不并入)");

        assertEquals(NodeState.ASSERTED, node(groupsWith("average-calc"), "average-calc").state());
    }

    /**
     * 极端情形下的同一条:全树 18 个考点<b>一个不落</b>地声明掌握 —— 「刷分」最短的那条路。
     *
     * <p>做完之后碰过的还是 8、没碰过的还是 10,只有盲区榜空了。
     * 🔴 而 {@code assertedCount} 是 <b>10 不是 18</b>:碰过的那 8 个优先级更高,
     * 它们的状态仍然是 {@code TOUCHED},根本不进这一格。
     * 写成「数一遍断言表行数」的实现在这里会给出 18 —— 而用户在树上只找得到 10 个。
     */
    @Test
    @DisplayName("🔴 18 个考点全声明一遍:碰过仍是 8、没碰过仍是 10,已声明是 10 不是 18")
    void assertingEveryNodeChangesOnlyOneNumber() {
        String[] all = syllabus.allNodes().stream().map(Syllabus.Node::code).toArray(String[]::new);
        assertEquals(18, all.length, "夹具自己先得对");

        List<GroupCoverage> groups = groupsWith(all);
        Summary after = service.summarize(groups);

        assertEquals(18, after.nodeTotal());
        assertEquals(8, after.nodeTouched(), "🔴 全部声明一遍就能刷高覆盖度 —— 这个按钮成了作弊器");
        assertEquals(10, after.nodeUntouched());
        assertEquals(10, after.assertedCount(),
                "已声明 = 声明了【且确实没碰过】的那些;数断言表行数的实现会多算碰过的那 8 个");

        assertTrue(defaultBoard(groups, 20).isEmpty(),
                "全部声明之后盲区榜该空 —— 那才是这个按钮真正做的事");
        assertEquals(10, service.blindSpots(groups, BlindspotOrder.RECENT5Y_COUNT,
                        BlindspotFilter.ASSERTED, false, 20).size(),
                "而它们要在「我说会了的清单」那一档里全部找得回来(U3.6)");
    }

    /**
     * 🔴 优先级链 {@code TOUCHED > ASSERTED} 的落点。
     *
     * <p>反过来让 {@code ASSERTED} 优先的话,这个已经碰过的节点会从分子里掉出来 ——
     * <b>点一下按钮就能让覆盖度下降</b>,而且不会有任何一条断言变红,只有那个数字会掉。
     */
    @Test
    @DisplayName("🔴 声明一个本来就碰过的考点:它仍然是「碰过」,「已声明」那一格也不加一")
    void assertingAnAlreadyTouchedNodeStaysTouched() {
        Summary after = service.summarize(groupsWith("growth-rate"));   // 碰过,1 条记录

        assertEquals(8, after.nodeTouched(), "碰过 + 声明过,还是同一个碰过的考点,不能数两遍,更不能掉出去");
        assertEquals(10, after.nodeUntouched());
        assertEquals(0, after.assertedCount(),
                "🔴 它不在差集里,所以不进这一格 —— 否则「已声明 N 个」会大于树上没碰过的考点数");

        NodeCoverage n = node(groupsWith("growth-rate"), "growth-rate");
        assertEquals(NodeState.TOUCHED, n.state(), "🔴 TOUCHED 优先于 ASSERTED,写反了覆盖度会因为点按钮而下降");
        assertTrue(n.asserted(), "但那一行断言是真实存在的,字段照样为 true —— 见 assertedFlagIsTheRowNotTheState");
    }

    @Test
    @DisplayName("断言不是第六态:它是五态里的一个,而且落在「没碰过」的里面")
    void assertionIsNotASixthState() {
        NodeCoverage n = node(groupsWith("average-calc"), "average-calc");

        assertEquals(NodeState.ASSERTED, n.state());
        assertTrue(n.state().inDenominator(), "照样进分母 —— 那是归档干的事");
        assertFalse(n.state().inNumerator(), "🔴 不进分子:声明不是触达");
        assertTrue(n.state().inBlindSet(), "🔴 它是「没碰过」的子集,不是它的对立面(U3.3 §2.4)");

        assertEquals(0, n.touchCount(), "触达次数不该被声明凭空加一");
        assertNull(n.lastTouchAt(), "从没碰过,「多久前」仍是 null");
        assertTrue(n.asserted());
    }

    // ———————————— 二、盲区榜排除已断言节点 ————————————

    @Test
    @DisplayName("🔴 声明掌握之后,那个考点从盲区榜上消失(§6.4:排除已断言节点)")
    void assertedNodesDisappearFromTheDefaultBoard() {
        List<NodeCoverage> before = defaultBoard(groupsWith(), 5);
        assertEquals("average-calc", before.get(0).code(), "夹具自己先得对 —— 榜首是平均数计算(近五年 6 次)");

        List<NodeCoverage> after = defaultBoard(groupsWith("average-calc"), 5);

        assertFalse(codes(after).contains("average-calc"),
                "🔴 声明掌握的考点还在「先补这几个」里 —— 用户按这个按钮要的就是它别再出现");
        assertEquals("current-value", after.get(0).code(), "后面的整体往上顶一位");
    }

    /**
     * 过滤必须排在 {@code top} 截断<b>之前</b>。
     *
     * <p>写在后面的话,声明过的考点会先占掉名额、再被删掉 —— 于是「要 5 个」返回 4 个,
     * 而榜上明明还有第 6 名可以补上来。这条错法在界面上表现为「榜越按越短」,
     * 而不是任何一处报错。
     */
    @Test
    @DisplayName("🔴 排除发生在取前 N 之前 —— 声明一个不会让这份清单少一行,只会让下一名顶上来")
    void exclusionHappensBeforeTheLimitNotAfter() {
        List<NodeCoverage> before = defaultBoard(groupsWith(), 5);
        assertEquals(List.of("average-calc", "current-value", "multiple-calc", "annual-avg-growth", "yoy-mom"),
                codes(before), "夹具自己先得对");

        List<NodeCoverage> after = defaultBoard(groupsWith("average-calc"), 5);

        assertEquals(List.of("current-value", "multiple-calc", "annual-avg-growth", "yoy-mom", "multiple-change"),
                codes(after),
                "要 5 个就该给 5 个,而且第 6 名(倍数变化)要真的顶进来 —— "
                        + "过滤排在截断之后的话,声明一个就少一行,榜会越按越短");
    }

    /**
     * 断言只是把节点<b>从这一档里挑出去</b>,不改它身上任何一个排序键。
     *
     * <p>「把它的出现次数按成 0」是另一种实现方式,它在默认档上表现一样,
     * 却会顺带改掉树上那一格显示的数字,以及 {@code filter=asserted} 那一档的顺序 ——
     * 而契约说的是「盲区榜排除」,不是「这个考点不再值钱」。
     */
    @Test
    @DisplayName("断言只过滤,不改数:被声明的考点在「我说会了的清单」里带着原样的排序键回来")
    void assertionFiltersWithoutRewritingTheSortKeys() {
        NodeCoverage plain = node(groupsWith(), "average-calc");
        NodeCoverage asserted = node(groupsWith("average-calc"), "average-calc");

        assertEquals(6, plain.recent5yCount(), "夹具自己先得对 —— 平均数计算近五年 6 次");
        assertEquals(6, asserted.recent5yCount(), "声明改不了真题考过几次,那是骨架侧的事实");
        assertEquals(13, plain.syllabusOrder());
        assertEquals(13, asserted.syllabusOrder(), "树上的位置也不该被一次声明挪走");
        assertEquals(0, asserted.touchCount());
        assertNull(asserted.lastTouchAt());

        assertEquals(List.of("average-calc"),
                codes(service.blindSpots(groupsWith("average-calc"), BlindspotOrder.RECENT5Y_COUNT,
                        BlindspotFilter.ASSERTED, false, 5)),
                "同一条规则的另一种读法:默认档排除它,asserted 档反过来只列它");
    }

    /**
     * ⚠️ 一个节点的 {@code asserted} 字段是<b>那一行断言的原始存在性</b>,不是 {@code state == ASSERTED}。
     *
     * <p>断言过、后来又碰过的节点 {@code state} 是 {@code TOUCHED},而这个字段仍然是 {@code true} ——
     * 断言行保留不删(§1.1),否则用户要为一次他从没做过的取消再按一遍。
     * 把这个字段实现成 {@code state == ASSERTED} 的那一版,界面上那个标记会在他碰过之后自己消失。
     */
    @Test
    @DisplayName("⚠️ asserted 字段是那一行断言在不在,不是 state == ASSERTED")
    void assertedFlagIsTheRowNotTheState() {
        List<GroupCoverage> groups = groupsWith("average-calc", "growth-rate");

        NodeCoverage touchedAndAsserted = node(groups, "growth-rate");
        assertEquals(NodeState.TOUCHED, touchedAndAsserted.state());
        assertTrue(touchedAndAsserted.asserted(), "🔴 状态是 TOUCHED,字段仍然为 true —— 两者不是同一件事");

        assertTrue(node(groups, "average-calc").asserted());
        assertFalse(node(groups, "share-calc").asserted(), "没按过按钮的碰过节点");
        assertFalse(node(groups, "current-value").asserted(), "没按过按钮的没碰过节点");
    }

    // ———————————— 三、指不到考点的声明行 ————————————

    @Test
    @DisplayName("声明指向树外的 code(考点被删了)→ 安静忽略,不炸,也不多算一格")
    void assertionsPointingOutsideTheTreeAreIgnored() {
        Summary s = service.summarize(groupsWith("这个考点已经不在树里了"));

        assertEquals(18, s.nodeTotal(), "🔴 一条指向树外的声明不该让分母凭空多一个");
        assertEquals(10, s.nodeUntouched());
        assertEquals(0, s.assertedCount(),
                "概览里那一格数的是「树上有几个考点被声明了」,不是「声明表里有几行」");
    }

    /**
     * ⚠️ 归档与断言是<b>同一类问题的两个不同答案</b>,这条钉的是它们不会互相污染。
     *
     * <p>归档把考点从分母里拿掉,断言把考点留在分母里、不进分子、单列一格。
     * 一个已归档的考点整个退出了差集,所以它身上的声明行<b>不该出现在那一格里</b> ——
     * 否则「已声明 N 个」会大于树上看得见的考点数,而用户找不到那第 N 个在哪。
     */
    @Test
    @DisplayName("⚠️ 已归档的考点身上的声明不进那一格 —— 归档退出差集,断言留在差集里")
    void archivedNodesCannotBeAsserted() {
        Syllabus archived = archive(syllabus, "average-calc");
        List<GroupCoverage> groups = groupsWith(archived, "average-calc");
        Summary s = service.summarize(groups);

        assertEquals(17, s.nodeTotal(), "归档把考点从【分母】里拿掉 —— 这是它与断言最大的区别");
        assertEquals(8, s.nodeTouched(), "归档一个没碰过的考点,分子不动");
        assertEquals(9, s.nodeUntouched(), "差集少一个 —— 它整个退出了差集,不是换了一档");
        assertEquals(1, s.archivedCount(), "🔴 单列一格,恒在(R-49:归档三件事都不做成开关)");
        assertEquals(0, s.assertedCount(), "已经不在差集里的考点,不该在「已声明」那一格里露面");

        assertEquals(NodeState.ARCHIVED, node(groups, "average-calc").state(), "ARCHIVED 优先于 ASSERTED");
        assertFalse(codes(service.blindSpots(groups, BlindspotOrder.RECENT5Y_COUNT,
                        BlindspotFilter.ALL, false, 20)).contains("average-calc"),
                "归档节点一档都不进 —— 连 filter=all 都不进");
    }

    /** 把一个考点标成已归档。记录一条不动 —— 与 {@code ExportApiTest.SwappableSyllabus#archive} 同一份。 */
    private static Syllabus archive(Syllabus tree, String nodeCode) {
        return new Syllabus(tree.subject(), tree.groups().stream()
                .map(g -> new Syllabus.Group(g.code(), g.name(), g.nodes().stream()
                        .map(n -> n.code().equals(nodeCode)
                                ? new Syllabus.Node(n.code(), n.name(), n.recent5yCount(), true)
                                : n)
                        .toList()))
                .toList());
    }
}
