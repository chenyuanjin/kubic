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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖度口径从「记录」改成「标签」之后,那个数还对不对。
 *
 * <h2>为什么单开一个文件,而不是往 {@code CoverageServiceTest} 里加</h2>
 *
 * {@code CoverageServiceTest} 钉的是<b>设计稿那组数</b>(18 / 8 / 44% / 稳3弱2生疏2仅接触1空白10),
 * 它的作用是「让设计稿和实现无法各说各话」。这里钉的是<b>口径本身</b> ——
 * 同一批数据,一条标签被丢弃之后那个数该怎么变。
 * <p>
 * 两件事混在一个文件里的后果很具体:口径改动会让设计稿那组数一起红,
 * 而修的人分不清是口径错了还是数据契约变了,最省事的做法是把数字改成新跑出来的值。
 * <b>那一刻两条断言一起失效,而且没人知道。</b>
 *
 * <h2>🔴 这个文件里的每条断言都配了一个「不这么写会怎样」</h2>
 *
 * 覆盖度的错法全是无声的:多算一次、少算一次、同一条记录数两遍,界面上都只是一个不一样的百分比。
 * 所以下面凡是断言数字的,都同时断言<b>它相对于基线变了/没变</b>,而不是只断言它等于某个数 ——
 * 一个写死的期望值在口径漂移时会被人直接改掉。
 */
class CoverageTagCaliberTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

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

    private Summary summaryWith(List<Touch> touches, List<RecordTag> stored) {
        return service.summarize(service.compute(syllabus, touches,
                RecordTag.effectiveTagsOf(touches, stored), NOW));
    }

    private NodeCoverage nodeWith(List<Touch> touches, List<RecordTag> stored, String code) {
        List<GroupCoverage> groups = service.compute(syllabus, touches,
                RecordTag.effectiveTagsOf(touches, stored), NOW);
        return groups.stream().flatMap(g -> g.nodes().stream())
                .filter(n -> n.code().equals(code)).findFirst().orElseThrow();
    }

    // ———————————————— 一、丢弃一条标签,覆盖度必须掉下去 ————————————————

    @Test
    @DisplayName("🔴 同一批数据,一条标签被丢弃之后覆盖度下降(P1-7:可见,但不计覆盖度)")
    void discardingATagLowersCoverage() {
        List<Touch> touches = contractTouches();

        Summary before = summaryWith(touches, List.of());
        assertEquals(8, before.covered(), "基线:8 个考点有记录");
        assertEquals(44, before.percent());

        // 「粉笔那节课我记错考点了」—— 丢掉这条记录的主标签,但记录本身一个字都不动
        RecordTag discarded = RecordTag.primaryOf(touches.get(0)).discard();
        Summary after = summaryWith(touches, List.of(discarded));

        assertEquals(7, after.covered(), "丢弃的标签还算在覆盖度里,盲区就永远不肯回来");
        assertEquals(39, after.percent(), "7/18 = 38.9% → 39%");
        assertTrue(after.percent() < before.percent(), "这个数必须真的掉下去,不是「大致差不多」");
        assertEquals(before.empty() + 1, after.empty(), "掉出覆盖度的那个考点要回到盲区里");
    }

    @Test
    @DisplayName("🔴 被丢弃的那个考点回到「空白」,而它的记录一条都没少")
    void theDiscardedNodeGoesBackToEmptyWhileItsRecordSurvives() {
        List<Touch> touches = contractTouches();
        RecordTag discarded = RecordTag.primaryOf(touches.get(0)).discard();

        NodeCoverage node = nodeWith(touches, List.of(discarded), "growth-rate");

        assertEquals(NodeState.EMPTY, node.state());
        assertEquals(0, node.touchCount(), "这个考点上不该再数出触达");
        assertEquals(0, node.practiced(), "做题数也跟着退回去 —— 那 12 道题不是在这个考点上做的");
        assertNull(node.accuracy(), "没练过应为 null,界面显示「—」,不是 0%");
        assertEquals(List.of(), node.sources());

        // 而记录本身还在。这正是「丢弃标签」与「删记录」的全部区别:
        // 错的只是它挂在哪儿,不该把「我那天学过东西」一起抹掉。
        assertEquals(8, touches.size());
    }

    @Test
    @DisplayName("同一个考点上还挂着别的记录时,丢一条不会让它掉出覆盖度 —— 分子是按记录去重数的")
    void discardingOneOfTwoRecordsOnTheSameNodeKeepsItCovered() {
        // 这条防的是「前端自己减一」那种写法,也防实现里把「丢弃数」直接从 covered 里扣掉。
        List<Touch> touches = new ArrayList<>(contractTouches());
        touches.add(new Touch("t-extra", "growth-rate", "自己刷题", TouchKind.MANUAL, daysAgo(1), null));

        Summary before = summaryWith(touches, List.of());
        Summary after = summaryWith(touches, List.of(RecordTag.primaryOf(touches.get(0)).discard()));

        assertEquals(before.covered(), after.covered(), "还有另一条记录挂在上面,这个考点仍然碰过");
        assertEquals(before.percent(), after.percent());
    }

    // ———————————————— 二、确认不改变覆盖度 ————————————————

    @Test
    @DisplayName("🔴 判据只有 discarded:没确认过的自动标签照样计,确认之后也不多计")
    void confirmationIsNotAConditionForCoverage() {
        // docs/technical/INDEX.md §6.4:「分子 = discarded=0 的触达节点数」。
        // 把「没点确认」也算成不覆盖,等于要求用户对每条自动标签点一次才承认他学过 ——
        // 覆盖率会变成点击率,而北极星指标看的正是这一屏。
        List<Touch> touches = contractTouches();
        RecordTag unconfirmed = new RecordTag("tag-auto", "t-growth-rate", "average-calc",
                0.91, TagOrigin.AUTO, null, false);

        Summary withUnconfirmed = summaryWith(touches, List.of(unconfirmed));
        Summary withConfirmed = summaryWith(touches, List.of(unconfirmed.confirm(NOW)));

        assertEquals(9, withUnconfirmed.covered(), "没确认的自动标签也是一次分类");
        assertEquals(withUnconfirmed.covered(), withConfirmed.covered(), "确认不新增,也不减少");
        assertEquals(withUnconfirmed.percent(), withConfirmed.percent());
    }

    // ———————————————— 三、加挂一个考点,覆盖度上去 ————————————————

    @Test
    @DisplayName("一条记录挂到第二个考点上 → 两个考点都算碰过,做题数在两边都算一遍")
    void oneRecordCanCoverTwoNodes() {
        List<Touch> touches = contractTouches();
        RecordTag extra = new RecordTag("tag-extra", "t-growth-rate", "average-calc",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false);

        Summary after = summaryWith(touches, List.of(extra));

        assertEquals(9, after.covered(), "一条记录同时说明了两个考点被碰过");
        assertEquals(12, nodeWith(touches, List.of(extra), "average-calc").practiced(),
                "这 12 道题在两个考点下各算一次 —— 用户说这一笔同时练到了它们");
        assertEquals(12, nodeWith(touches, List.of(extra), "growth-rate").practiced());
    }

    @Test
    @DisplayName("🔴 同一条记录同一个考点挂两次,只算一次 —— 否则做题数会被加两遍")
    void thesameRecordOnTheSameNodeIsCountedOnce() {
        // 触发路径不是空想:自动补标挂上 X,用户又手动挂了一次 X。
        // 不去重的话「弱」这个状态会由一组翻倍的数字推出来,而两倍的正确率和一倍的一样 ——
        // 表现出来只是触达次数变多,没有任何一处报错。
        List<Touch> touches = contractTouches();
        List<RecordTag> twice = List.of(
                new RecordTag("tag-a", "t-growth-rate", "growth-rate",
                        RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false),
                new RecordTag("tag-b", "t-growth-rate", "growth-rate", 0.9, TagOrigin.AUTO, null, false));

        NodeCoverage node = nodeWith(touches, twice, "growth-rate");
        NodeCoverage baseline = nodeWith(touches, List.of(), "growth-rate");

        assertEquals(baseline.touchCount(), node.touchCount(), "同一条记录不能数两遍");
        assertEquals(baseline.practiced(), node.practiced());
        assertEquals(baseline.correct(), node.correct());
        assertEquals(baseline.sources(), node.sources(), "来源名也不该出现两次");
    }

    @Test
    @DisplayName("🔴 一个考点下的记录按行为层原本的顺序排 —— 来源名列表的次序是接口上看得见的东西")
    void recordsUnderANodeKeepTheBehaviourLayerOrder() {
        // 这条是被一次变异测试逼出来的:把 project 里那句重排换成 Collections.reverse,
        // 当时【整个套件一条都不红】—— 因为契约数据里每个考点下正好只有一条记录。
        // 换句话说,「顺序」这件事当时完全没有被守着,而它错了只表现为界面上来源名换了个次序。
        List<Touch> touches = new ArrayList<>();
        touches.add(new Touch("t-old", "growth-rate", "中公 · 资料分析专项",
                TouchKind.MANUAL, daysAgo(10), null));
        touches.add(new Touch("t-mid", "growth-rate", "华图 · 资料速算网课",
                TouchKind.MANUAL, daysAgo(5), null));
        touches.add(new Touch("t-new", "growth-rate", "B站 · 资料分析技巧",
                TouchKind.MANUAL, daysAgo(1), null));

        // 标签故意按【与行为层相反】的顺序给进去:排序必须由记录顺序决定,不由标签顺序决定。
        List<RecordTag> reversed = new ArrayList<>(
                RecordTag.effectiveTagsOf(touches, List.of()));
        java.util.Collections.reverse(reversed);

        NodeCoverage node = service.compute(syllabus, touches, reversed, NOW).stream()
                .flatMap(g -> g.nodes().stream())
                .filter(n -> n.code().equals("growth-rate")).findFirst().orElseThrow();

        assertEquals(List.of("中公 · 资料分析专项", "华图 · 资料速算网课", "B站 · 资料分析技巧"),
                node.sources(), "来源名按首次出现顺序 —— 而「首次」指的是行为层里的次序");
        assertEquals(daysAgo(1), node.latestAt(), "最近一次触达不该受标签顺序影响");
    }

    // ———————————————— 四、孤儿标签与纯委托 ————————————————

    @Test
    @DisplayName("标签指不到记录 → 不计覆盖度,而且不炸")
    void aTagPointingAtNoRecordCountsForNothing() {
        // 删记录时会级联删标签,所以这种行本不该出现;但「本不该出现」不是一个能靠自觉维持的性质。
        // 万一留下了,正确的行为是它安静地不算数 —— 而不是让整棵树算不出来,
        // 更不是让那个考点凭空保持「碰过」。
        List<Touch> touches = contractTouches();
        RecordTag orphan = new RecordTag("tag-orphan", "t-已经删了", "average-calc",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false);

        assertEquals(summaryWith(touches, List.of()).covered(),
                summaryWith(touches, List.of(orphan)).covered());
        assertEquals(NodeState.EMPTY, nodeWith(touches, List.of(orphan), "average-calc").state());
    }

    @Test
    @DisplayName("标签指向树外的 code(考点被删了)→ 同样不计,不影响别的格子")
    void aTagPointingOutsideTheTreeChangesNothing() {
        List<Touch> touches = contractTouches();
        RecordTag gone = new RecordTag("tag-gone", "t-growth-rate", "已经被删掉的考点",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false);

        assertEquals(summaryWith(touches, List.of()).covered(),
                summaryWith(touches, List.of(gone)).covered());
    }

    @Test
    @DisplayName("🔴 三参 compute 是四参的纯委托 —— 真正的计算只有一处")
    void theThreeArgOverloadIsPureDelegation() {
        // 留着三参重载是为了让「还没有标签表」的调用方(测试、离线脚本)照旧能用。
        // 危险在于它可能悄悄长成第二套口径:那时 CoverageServiceTest 依然全绿,
        // 而线上走的是另一条路。这条断言把两者钉成同一个结果。
        List<Touch> touches = contractTouches();

        assertEquals(
                service.compute(syllabus, touches, NOW),
                service.compute(syllabus, touches, RecordTag.effectiveTagsOf(touches, List.of()), NOW),
                "三参与四参必须逐字段相等,否则库里存不存标签会走出两个不同的覆盖率");
    }

    @Test
    @DisplayName("空行为层:分子为 0,分母还是 18 —— 没有记录不等于没有考点")
    void anEmptyBehaviourLayerStillHasADenominator() {
        Summary empty = summaryWith(List.of(), List.of());
        assertEquals(18, empty.total());
        assertEquals(0, empty.covered());
        assertEquals(0, empty.percent());
        assertFalse(empty.distribution().isEmpty());
    }
}
