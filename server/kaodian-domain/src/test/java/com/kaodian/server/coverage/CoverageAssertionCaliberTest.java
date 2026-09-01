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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「我已掌握」的三处口径 —— <b>这个文件的中心是一条「什么都没变」的断言</b>。
 *
 * <h2>为什么单开一个文件</h2>
 *
 * 与 {@code CoverageTagCaliberTest} 单开的理由逐字相同:{@code CoverageServiceTest} 钉的是
 * <b>设计稿那组数</b>,{@code CoverageTagCaliberTest} 钉的是<b>标签口径</b>,
 * 这里钉的是<b>断言口径</b>。三件事混在一起,任何一处口径改动都会让另外两组数一起红,
 * 而修的人分不清是哪一条坏了,最省事的做法是把数字改成新跑出来的值 ——
 * 那一刻三条断言一起失效,而且没人知道。
 *
 * <h2>🔴 这里最重要的断言是「覆盖率一个字都没动」</h2>
 *
 * 决策记录 §5.2:<b>「『我已掌握』按钮是补丁不是解法。」</b> 它是给「用户学了但没记」这个问题的
 * 一块创可贴,治的是「被工具冤枉了」这个感受,不是录入不完整这个病。
 * 所以它一旦进了覆盖度的分子,补丁就被当成了疗效 —— 那个百分比会因为<b>点按钮</b>而上升,
 * 而一个能靠自我声明刷高的覆盖率,和一个没有覆盖率的产品,价值是一样的。
 * <p>
 * 这条口径的失败方式是<b>无声的</b>:在 {@code summarize} 里写一句
 * {@code if (n.state().covered() || n.asserted()) covered++;},接口全绿、界面更好看、
 * 用户更满意,而这个产品唯一的那个数字从此不再指向任何真实的东西。
 * 所以下面每一条断言都是「按之前 == 按之后」的形式,而不是「等于某个写死的数」。
 *
 * <h2>⚠️ 断言不是归档 —— 同一类问题的两个不同答案</h2>
 *
 * 归档({@code R-49})把考点从<b>分母</b>里拿掉,比值仍然诚实;断言把考点<b>留在分母里</b>、
 * 不进分子、单列一格。{@code assertingEveryNodeLeavesTheHeadlineNumberAlone} 与
 * {@code archivedNodesCannotBeAsserted} 两条合起来钉的就是这个区别。
 */
class CoverageAssertionCaliberTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final Instant ASSERTED_AT = Instant.parse("2026-08-20T09:00:00Z");

    private final CoverageService service = new CoverageService();
    private final Syllabus syllabus = SyllabusLoader.loadDefault();

    /** 与 {@code CoverageServiceTest.contractTouches} 同一批数据:8 个考点有记录,覆盖 44%。 */
    private List<Touch> contractTouches() {
        List<Touch> ts = new ArrayList<>();
        drill(ts, "growth-rate", "粉笔 · 资料分析系统班 L12", 12, 10, 0);
        drill(ts, "share-calc", "华图 · 资料速算网课", 9, 8, 1);
        drill(ts, "feature-number", "自己刷题 · 2023 国考真题", 7, 6, 3);
        drill(ts, "growth-amount", "自己刷题 · 2023 国考真题", 8, 4, 2);
        drill(ts, "truncate-divide", "B站 · 资料分析技巧", 6, 2, 4);
        drill(ts, "base-value", "中公 · 资料分析专项", 5, 4, 32);
        drill(ts, "interval-growth", "中公 · 资料分析专项", 3, 2, 33);
        ts.add(new Touch("t-share-change", "share-change",
                "粉笔 · 资料分析系统班 L12", TouchKind.VOICE, daysAgo(5), null));
        return ts;
    }

    private void drill(List<Touch> ts, String node, String source, int practiced, int correct, int daysAgo) {
        ts.add(new Touch("t-" + node, node, source, TouchKind.DRILL, daysAgo(daysAgo),
                new Touch.Drill(practiced, correct)));
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
            assertions.add(new UserAssertion(code, ASSERTED_AT));
        }
        return service.compute(tree, touches, RecordTag.effectiveTagsOf(touches, List.of()),
                List.copyOf(assertions), NOW);
    }

    private static NodeCoverage node(List<GroupCoverage> groups, String code) {
        return groups.stream().flatMap(g -> g.nodes().stream())
                .filter(n -> n.code().equals(code)).findFirst().orElseThrow();
    }

    // ———————————— 一、覆盖率的分子不动。这是整件事的重点 ————————————

    /**
     * 🔴 这一条如果被删掉或改松,「我已掌握」就退化成一个刷分按钮。
     *
     * <p>它故意不断言 {@code percent == 44} 这个写死的数,而是断言<b>按之前 == 按之后</b>:
     * 写死的期望值在有人「顺手」把断言并进分子时,会被当成一个过时的数字直接改掉。
     */
    @Test
    @DisplayName("🔴 按下「我已掌握」之后,覆盖率一个字都没动(决策记录 §5.2:补丁不是解法)")
    void assertingANodeDoesNotMoveTheCoverageRatio() {
        Summary before = service.summarize(groupsWith());
        // average-calc 是一个彻头彻尾的空白考点 —— 一条记录都没有,最容易被「按一下就算碰过」
        Summary after = service.summarize(groupsWith("average-calc"));

        assertEquals(before.total(), after.total(), "分母不该动 —— 那是归档干的事,不是断言");
        assertEquals(before.covered(), after.covered(),
                "🔴 分子动了 —— 断言被算进了覆盖度。docs/technical/INDEX.md §6.4:「分子 = discarded=0 的触达节点数」,"
                        + "而声明不是触达。决策记录 §5.2:「我已掌握」按钮是补丁不是解法");
        assertEquals(before.percent(), after.percent(), "那个大字必须一模一样");
        assertEquals(before.empty(), after.empty(),
                "空白数不该动 —— 那个考点确实还是一条记录都没有,声明改不了这件事");
        assertEquals(before.distribution(), after.distribution(),
                "五态是从记录推出来的,断言不是记录,不该占其中任何一格");
        assertEquals(before.whollyEmptyGroups(), after.whollyEmptyGroups(),
                "整块空白也是从记录推出来的");

        assertEquals(0, before.asserted());
        assertEquals(1, after.asserted(), "唯一该变的就是这一个数(docs/technical/INDEX.md §6.4:断言单列不并入)");
    }

    /**
     * 极端情形下的同一条:全树 18 个考点<b>一个不落</b>地声明掌握。
     *
     * <p>这正是「刷分」最短的那条路。做完之后覆盖率还是 44%、空白还是 10 个,
     * <b>只有盲区榜空了、已声明变成 18</b>。
     */
    @Test
    @DisplayName("🔴 把 18 个考点全声明掌握:覆盖率仍是 44%,空白仍是 10 —— 变的只有那一格与盲区榜")
    void assertingEveryNodeLeavesTheHeadlineNumberAlone() {
        Summary before = service.summarize(groupsWith());
        String[] all = syllabus.allNodes().stream().map(Syllabus.Node::code).toArray(String[]::new);
        assertEquals(18, all.length, "夹具自己先得对");

        List<GroupCoverage> groups = groupsWith(all);
        Summary after = service.summarize(groups);

        assertEquals(before.percent(), after.percent(), "🔴 全部声明一遍就能刷高覆盖率 —— 这个按钮成了作弊器");
        assertEquals(before.covered(), after.covered());
        assertEquals(before.empty(), after.empty());
        assertEquals(before.distribution(), after.distribution());
        assertEquals(18, after.asserted());

        assertTrue(service.blindSpots(groups, 20).isEmpty(),
                "全部声明之后盲区榜该空 —— 那才是这个按钮真正做的事");
    }

    @Test
    @DisplayName("声明一个本来就碰过的考点:同样什么都不变,不会被数两遍")
    void assertingAnAlreadyCoveredNodeDoesNotDoubleCount() {
        Summary before = service.summarize(groupsWith());
        Summary after = service.summarize(groupsWith("growth-rate"));   // 稳,记录 1 条

        assertEquals(before.covered(), after.covered(), "碰过 + 声明过,还是同一个考点,不能数两遍");
        assertEquals(before.percent(), after.percent());
        assertEquals(1, after.asserted());
    }

    @Test
    @DisplayName("断言与「碰过」是两个维度 —— 一个考点可以同时是「空白」和「已声明」")
    void assertionIsASeparateStateNotASixthOne() {
        NodeCoverage n = node(groupsWith("average-calc"), "average-calc");

        assertEquals(NodeState.EMPTY, n.state(), "🔴 状态还是空白 —— 断言不是第六态(docs/technical/INDEX.md §5.2:独立状态)");
        assertEquals(0, n.touchCount(), "触达次数不该被声明凭空加一");
        assertNull(n.latestAt(), "从没碰过,最近触达仍是 null");
        assertTrue(n.asserted());
        assertEquals(ASSERTED_AT, n.assertedAt());
    }

    // ———————————— 二、盲区榜排除已断言节点 ————————————

    @Test
    @DisplayName("🔴 声明掌握之后,那个考点从盲区榜上消失(docs/technical/INDEX.md §6.4:排除已断言节点)")
    void assertedNodesDisappearFromBlindSpots() {
        List<NodeCoverage> before = service.blindSpots(groupsWith(), 5);
        String top = before.get(0).code();
        assertEquals("growth-amount", top, "夹具自己先得对 —— 榜首是增长量计算");

        List<NodeCoverage> after = service.blindSpots(groupsWith(top), 5);

        assertFalse(after.stream().anyMatch(n -> n.code().equals(top)),
                "🔴 声明掌握的考点还在「先补这几个」里 —— 用户按这个按钮要的就是它别再出现");
        assertEquals(before.get(1).code(), after.get(0).code(), "后面的应该整体往上顶一位");
    }

    /**
     * 过滤必须排在 {@code limit(top)} <b>之前</b>。
     *
     * <p>写在后面的话,声明过的考点会先占掉名额、再被删掉 —— 于是「要 5 个」返回 4 个,
     * 而榜上明明还有第 6 名可以补上来。这条错法在界面上表现为「榜越按越短」,
     * 而不是任何一处报错。
     */
    @Test
    @DisplayName("🔴 排除发生在取前 N 之前 —— 声明一个不会让这份清单少一行,只会让下一名顶上来")
    void exclusionHappensBeforeTheLimitNotAfter() {
        List<NodeCoverage> before = service.blindSpots(groupsWith(), 5);
        assertEquals(5, before.size(), "夹具自己先得对");

        List<NodeCoverage> after = service.blindSpots(groupsWith(before.get(0).code()), 5);

        assertEquals(5, after.size(),
                "要 5 个就该给 5 个 —— 过滤排在 limit 之后的话,声明一个就少一行,榜会越按越短");
        assertEquals(before.get(4).code(), after.get(3).code(), "第 5 名应该顶到第 4 位");
    }

    @Test
    @DisplayName("排序分本身不受断言影响 —— 断言只是从这份清单里过滤掉,不是把它的分数按成 0")
    void assertionFiltersTheListWithoutRewritingTheScore() {
        NodeCoverage plain = node(groupsWith(), "average-calc");
        NodeCoverage asserted = node(groupsWith("average-calc"), "average-calc");

        assertEquals(plain.blindScore(), asserted.blindScore(),
                "把分数按成 0 是另一种写法,但它会顺带改掉树上那一格的排序依据 —— "
                        + "而契约说的是「盲区榜排除」,不是「这个考点不再是盲区」");
        assertEquals(plain.state(), asserted.state());
    }

    // ———————————— 三、指不到考点的声明行 ————————————

    @Test
    @DisplayName("声明指向树外的 code(考点被删了)→ 安静忽略,不炸,也不多算一格")
    void assertionsPointingOutsideTheTreeAreIgnored() {
        Summary s = service.summarize(groupsWith("这个考点已经不在树里了"));

        assertEquals(0, s.asserted(),
                "概览里那一格数的是「树上有几个考点被声明了」,不是「声明表里有几行」");
    }

    /**
     * ⚠️ 归档与断言是<b>同一类问题的两个不同答案</b>,这条钉的是它们不会互相污染。
     *
     * <p>归档把考点从分母里拿掉(比值仍然诚实),断言把考点留在分母里、不进分子、单列一格。
     * 一个已归档的考点整个退出了差集,所以它身上的声明行<b>不该出现在那一格里</b> ——
     * 否则「已声明 N 个」会大于树上看得见的考点数,而用户找不到那第 N 个在哪。
     */
    @Test
    @DisplayName("⚠️ 已归档的考点身上的声明不进那一格 —— 归档退出差集,断言留在差集里")
    void archivedNodesCannotBeAsserted() {
        Syllabus archived = archive(syllabus, "average-calc");

        Summary s = service.summarize(groupsWith(archived, "average-calc"));

        assertEquals(17, s.total(), "归档把考点从【分母】里拿掉 —— 这是它与断言最大的区别");
        assertEquals(0, s.asserted(), "已经不在差集里的考点,不该在「已声明」那一格里露面");
    }

    // ———————————— 四、委托关系 ————————————

    @Test
    @DisplayName("🔴 四参 compute 是五参的纯委托 —— 真正的计算只有一处")
    void theFourArgComputeIsAPureDelegation() {
        List<Touch> touches = contractTouches();
        List<RecordTag> tags = RecordTag.effectiveTagsOf(touches, List.of());

        assertEquals(
                service.compute(syllabus, touches, tags, NOW),
                service.compute(syllabus, touches, tags, List.of(), NOW),
                "「没有任何声明」和「不传声明」必须是同一件事,否则口径会有两份");
    }

    @Test
    @DisplayName("没按过按钮的考点 assertedAt 是 null —— 界面据此决定显不显示那个标记")
    void unassertedNodesCarryNull() {
        List<GroupCoverage> groups = groupsWith("average-calc");

        assertNull(node(groups, "growth-rate").assertedAt());
        assertFalse(node(groups, "growth-rate").asserted());
        assertNotNull(node(groups, "average-calc").assertedAt());
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
