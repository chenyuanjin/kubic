package com.kaodian.server.coverage;

import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.TagOrigin;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖度口径从「记录」改成「标签」之后,那三个数还对不对。
 *
 * <h2>为什么单开一个文件,而不是往 {@code CoverageServiceTest} 里加</h2>
 *
 * {@code CoverageServiceTest} 钉的是<b>数据契约那组数</b>(18 / 8 / 10 / 2 组整块空白),
 * 它的作用是「让设计稿和实现无法各说各话」。这里钉的是<b>口径本身</b> ——
 * 同一批数据,一条标签被丢弃之后那几个数该怎么变。
 * <p>
 * 两件事混在一个文件里的后果很具体:口径改动会让契约那组数一起红,
 * 而修的人分不清是口径错了还是数据契约变了,最省事的做法是把数字改成新跑出来的值。
 * <b>那一刻两条断言一起失效,而且没人知道。</b>
 *
 * <h2>🔴 这里断言的是「有没有」,不是「答得怎么样」</h2>
 *
 * 上一版这个文件顺带断言过 {@code practiced} / {@code accuracy()} / {@code percent()}。
 * 三个都已经不在这一域里(§7.2 / §7.4):前两个回答「答得怎么样」,第三个是个浮点。
 * 剩下的口径问题一个没少 —— <b>丢弃、确认、加挂、重复挂、孤儿标签</b>,
 * 它们的错法全是无声的:多算一次、少算一次、同一条记录数两遍,
 * 界面上都只表现为「没碰过」那一格里的数字不一样。
 */
class CoverageTagCaliberTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    /** 测试用户 —— 与行为层种子同一个 id(B0 §3.3:auth 侧从 10001 起号)。 */
    private static final long USER = 10001L;

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

    /**
     * 走 {@link RecordTag#effectiveTagsOf} 派生标签,断言表恒为空 —— 与 {@code CoverageReader.read} 同一条路。
     *
     * <p>🔴 <b>不能直接把 {@code stored} 递给 compute</b>:库里只有【后来发生的事】(补标、加挂、确认、丢弃),
     * 采集那一刻的主标签是推出来的。绕过派生的那一版会让每条记录都凭空少一条标签。
     */
    private List<GroupCoverage> groupsWith(List<Touch> touches, List<RecordTag> stored) {
        return service.compute(syllabus, touches,
                RecordTag.effectiveTagsOf(touches, stored), List.of(), NOW);
    }

    private Summary summaryWith(List<Touch> touches, List<RecordTag> stored) {
        return service.summarize(groupsWith(touches, stored));
    }

    private NodeCoverage nodeWith(List<Touch> touches, List<RecordTag> stored, String code) {
        return groupsWith(touches, stored).stream().flatMap(g -> g.nodes().stream())
                .filter(n -> n.code().equals(code)).findFirst().orElseThrow();
    }

    // ———————————————— 一、丢弃一条标签,那个考点必须回到差集里 ————————————————

    @Test
    @DisplayName("🔴 一条标签被丢弃之后,碰过的少一个、没碰过的多一个(P1-7:可见,但不计覆盖度)")
    void discardingATagMovesTheNodeBackIntoTheBlindSet() {
        List<Touch> touches = contractTouches();

        Summary before = summaryWith(touches, List.of());
        assertEquals(8, before.nodeTouched(), "基线:8 个考点碰过");
        assertEquals(10, before.nodeUntouched(), "基线:10 个没碰过");

        // 「粉笔那节课我记错考点了」—— 丢掉这条记录的主标签,但记录本身一个字都不动
        RecordTag discarded = RecordTag.primaryOf(touches.get(0)).discard();
        Summary after = summaryWith(touches, List.of(discarded));

        assertEquals(7, after.nodeTouched(), "丢弃的标签还算在覆盖度里,盲区就永远不肯回来");
        assertEquals(11, after.nodeUntouched(), "🔴 掉出分子的那个考点要真的回到差集里,不是凭空消失");
        assertEquals(18, after.nodeTotal(), "分母不该动 —— 丢一条标签不等于树上少一个考点");
    }

    @Test
    @DisplayName("🔴 被丢弃的那个考点回到「没碰过」,而它的记录一条都没少")
    void theDiscardedNodeGoesBackToUntouchedWhileItsRecordSurvives() {
        List<Touch> touches = contractTouches();
        RecordTag discarded = RecordTag.primaryOf(touches.get(0)).discard();

        NodeCoverage node = nodeWith(touches, List.of(discarded), "growth-rate");

        assertEquals(NodeState.UNTOUCHED, node.state());
        assertEquals(0, node.touchCount(), "这个考点上不该再数出触达");
        assertNull(node.lastTouchAt(), "「多久前」也跟着退回去 —— 那一次不是在这个考点上发生的");
        assertEquals(List.of(), node.sourceNames(), "来源名同理:整条标签都不算数了");

        // 而记录本身还在。这正是「丢弃标签」与「删记录」的全部区别:
        // 错的只是它挂在哪儿,不该把「我那天学过东西」一起抹掉。
        assertEquals(8, touches.size());
    }

    @Test
    @DisplayName("同一个考点上还挂着别的记录时,丢一条不会让它掉出分子 —— 分子是按考点数的,不是按标签数的")
    void discardingOneOfTwoRecordsOnTheSameNodeKeepsItTouched() {
        // 这条防的是「前端自己减一」那种写法,也防实现里把「丢弃数」直接从 nodeTouched 里扣掉。
        List<Touch> touches = new ArrayList<>(contractTouches());
        touches.add(new Touch("t-extra", USER, "growth-rate", "自己刷题", TouchKind.MANUAL, daysAgo(1), null, null));

        Summary before = summaryWith(touches, List.of());
        assertEquals(8, before.nodeTouched(), "多一条记录挂在已经碰过的考点上,碰过的考点数不变");
        assertEquals(2, nodeWith(touches, List.of(), "growth-rate").touchCount(), "但触达次数是 2");

        Summary after = summaryWith(touches, List.of(RecordTag.primaryOf(touches.get(0)).discard()));

        assertEquals(8, after.nodeTouched(), "还有另一条记录挂在上面,这个考点仍然碰过");
        assertEquals(10, after.nodeUntouched());

        NodeCoverage node = nodeWith(touches, List.of(RecordTag.primaryOf(touches.get(0)).discard()), "growth-rate");
        assertEquals(1, node.touchCount(), "少的是次数,不是那一格");
        assertEquals(daysAgo(1), node.lastTouchAt(), "最近一次触达退到剩下的那条记录上");
    }

    // ———————————————— 二、确认不改变任何一个数 ————————————————

    @Test
    @DisplayName("🔴 判据只有 discarded:没确认过的自动标签照样计,确认之后也不多计")
    void confirmationIsNotAConditionForCoverage() {
        // docs/technical/INDEX.md §6.4:「分子 = discarded=0 的触达节点数」。
        // 把「没点确认」也算成不覆盖,等于要求用户对每条自动标签点一次才承认他学过 ——
        // 覆盖度会变成点击率,而北极星指标看的正是这一屏。
        List<Touch> touches = contractTouches();
        RecordTag unconfirmed = new RecordTag("tag-auto", USER, "t-growth-rate", "average-calc",
                0.91, TagOrigin.AUTO, null, false);

        Summary withUnconfirmed = summaryWith(touches, List.of(unconfirmed));
        Summary withConfirmed = summaryWith(touches, List.of(unconfirmed.confirm(NOW)));

        assertEquals(9, withUnconfirmed.nodeTouched(), "没确认的自动标签也是一次分类");
        assertEquals(9, withConfirmed.nodeTouched(), "确认不新增,也不减少");
        assertEquals(9, withUnconfirmed.nodeUntouched());
        assertEquals(9, withConfirmed.nodeUntouched());
    }

    // ———————————————— 三、加挂一个考点,碰过的多一个 ————————————————

    @Test
    @DisplayName("一条记录挂到第二个考点上 → 两个考点都算碰过,各自记一次触达")
    void oneRecordCanCoverTwoNodes() {
        List<Touch> touches = contractTouches();
        RecordTag extra = new RecordTag("tag-extra", USER, "t-growth-rate", "average-calc",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false);

        Summary after = summaryWith(touches, List.of(extra));
        assertEquals(9, after.nodeTouched(), "一条记录同时说明了两个考点被碰过");
        assertEquals(9, after.nodeUntouched(), "差集跟着少一个 —— 两个数各自数出来,不是一个减出来的");

        // 「几次 / 多久前」在两个考点下各答一遍,答的是同一条记录 —— 它没有被复制,也没有被摊薄。
        NodeCoverage added = nodeWith(touches, List.of(extra), "average-calc");
        assertEquals(1, added.touchCount());
        assertEquals(daysAgo(0), added.lastTouchAt());
        assertEquals(List.of("粉笔 · 资料分析系统班 L12"), added.sourceNames());
        assertEquals(1, nodeWith(touches, List.of(extra), "growth-rate").touchCount());
    }

    @Test
    @DisplayName("🔴 同一条记录同一个考点挂两次,只算一次 —— 否则触达次数会被加两遍")
    void theSameRecordOnTheSameNodeIsCountedOnce() {
        // 触发路径不是空想:自动补标挂上 X,用户又手动挂了一次 X。
        // 不去重的话「碰过几次」会翻倍,而翻倍的次数和一倍的一样看不出问题 ——
        // 表现出来只是那个数字变大,没有任何一处报错。
        List<Touch> touches = contractTouches();
        List<RecordTag> twice = List.of(
                new RecordTag("tag-a", USER, "t-growth-rate", "growth-rate",
                        RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false),
                new RecordTag("tag-b", USER, "t-growth-rate", "growth-rate", 0.9, TagOrigin.AUTO, null, false));

        NodeCoverage node = nodeWith(touches, twice, "growth-rate");

        assertEquals(1, node.touchCount(), "同一条记录不能数两遍");
        assertEquals(List.of("粉笔 · 资料分析系统班 L12"), node.sourceNames(), "来源名也不该出现两次");
        assertEquals(8, summaryWith(touches, twice).nodeTouched(), "重复挂载更不该让碰过的考点数变多");
    }

    @Test
    @DisplayName("🔴 一个考点下的记录按行为层原本的顺序排 —— 来源名列表的次序是接口上看得见的东西")
    void recordsUnderANodeKeepTheBehaviourLayerOrder() {
        // 这条是被一次变异测试逼出来的:把 project 里那句重排换成 Collections.reverse,
        // 当时【整个套件一条都不红】—— 因为契约数据里每个考点下正好只有一条记录。
        // 换句话说,「顺序」这件事当时完全没有被守着,而它错了只表现为界面上来源名换了个次序。
        List<Touch> touches = new ArrayList<>();
        touches.add(new Touch("t-old", USER, "growth-rate", "中公 · 资料分析专项",
                TouchKind.MANUAL, daysAgo(10), null, null));
        touches.add(new Touch("t-mid", USER, "growth-rate", "华图 · 资料速算网课",
                TouchKind.MANUAL, daysAgo(5), null, null));
        touches.add(new Touch("t-new", USER, "growth-rate", "B站 · 资料分析技巧",
                TouchKind.MANUAL, daysAgo(1), null, null));

        // 标签故意按【与行为层相反】的顺序给进去:排序必须由记录顺序决定,不由标签顺序决定。
        List<RecordTag> reversed = new ArrayList<>(RecordTag.effectiveTagsOf(touches, List.of()));
        java.util.Collections.reverse(reversed);

        NodeCoverage node = service.compute(syllabus, touches, reversed, List.of(), NOW).stream()
                .flatMap(g -> g.nodes().stream())
                .filter(n -> n.code().equals("growth-rate")).findFirst().orElseThrow();

        assertEquals(List.of("中公 · 资料分析专项", "华图 · 资料速算网课", "B站 · 资料分析技巧"),
                node.sourceNames(), "来源名按首次出现顺序 —— 而「首次」指的是行为层里的次序");
        assertEquals(3, node.touchCount());
        assertEquals(daysAgo(1), node.lastTouchAt(), "最近一次触达是比出来的,不该受标签顺序影响");
    }

    // ———————————————— 四、脏标签一律安静忽略 ————————————————

    @Test
    @DisplayName("标签指不到记录 → 不计覆盖度,而且不炸")
    void aTagPointingAtNoRecordCountsForNothing() {
        // 删记录时会级联删标签,所以这种行本不该出现;但「本不该出现」不是一个能靠自觉维持的性质。
        // 万一留下了,正确的行为是它安静地不算数 —— 而不是让整棵树算不出来,
        // 更不是让那个考点凭空保持「碰过」。
        List<Touch> touches = contractTouches();
        RecordTag orphan = new RecordTag("tag-orphan", USER, "t-已经删了", "average-calc",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false);

        Summary s = summaryWith(touches, List.of(orphan));
        assertEquals(8, s.nodeTouched());
        assertEquals(10, s.nodeUntouched());
        assertEquals(NodeState.UNTOUCHED, nodeWith(touches, List.of(orphan), "average-calc").state());
    }

    @Test
    @DisplayName("标签指向树外的 code(考点被删了)→ 同样不计,不影响别的格子")
    void aTagPointingOutsideTheTreeChangesNothing() {
        List<Touch> touches = contractTouches();
        RecordTag gone = new RecordTag("tag-gone", USER, "t-growth-rate", "已经被删掉的考点",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false);

        Summary s = summaryWith(touches, List.of(gone));
        assertEquals(18, s.nodeTotal(), "🔴 一条指向树外的标签不该让分母凭空多一个 —— 那是 GONE 那一档");
        assertEquals(8, s.nodeTouched());
        assertEquals(10, s.nodeUntouched());
        assertTrue(groupsWith(touches, List.of(gone)).stream().flatMap(g -> g.nodes().stream())
                        .noneMatch(n -> n.code().equals("已经被删掉的考点")),
                "树外的 code 一个格子都不该出现在树上");
    }

    @Test
    @DisplayName("空行为层:分子为 0,分母还是 18 —— 没有记录不等于没有考点")
    void anEmptyBehaviourLayerStillHasADenominator() {
        Summary empty = summaryWith(List.of(), List.of());

        assertEquals(18, empty.nodeTotal());
        assertEquals(0, empty.nodeTouched());
        assertEquals(18, empty.nodeUntouched(), "一条记录都没有时,整棵树就是差集本身");
        assertEquals(0, empty.archivedCount());
        assertEquals(0, empty.assertedCount());
    }
}
