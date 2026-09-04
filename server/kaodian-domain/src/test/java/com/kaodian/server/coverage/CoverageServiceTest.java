package com.kaodian.server.coverage;

import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.coverage.CoverageService.GroupCoverage;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.coverage.CoverageService.Summary;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 把设计稿的数字钉死在代码上。
 *
 * <p>设计稿(49 屏,三端)全部按同一份数据契约绘制:
 * <b>18 个考点 / 8 个有记录 / 覆盖 44% / 10 个空白 / 2 组整块空白</b>,
 * 状态分布 <b>稳 3 · 弱 2 · 生疏 2 · 仅接触 1 · 空白 10</b>,
 * 「先补这几个」Top 5 为 <b>6.4 / 6.0 / 5.6 / 5.0 / 5.0</b>。
 *
 * <p>这个测试的作用不是「覆盖率」,是<b>让设计稿和实现无法各说各话</b>:
 * 任何一方改了口径,这里立刻红。
 */
class CoverageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    /** 测试用户 —— 与行为层种子同一个 id(B0 §3.3:auth 侧从 10001 起号)。 */
    private static final long USER = 10001L;

    private final CoverageService service = new CoverageService();
    private final Syllabus syllabus = SyllabusLoader.loadDefault();

    /** 按数据契约构造行为层:8 个考点有记录,其余 10 个一条都没有。 */
    private List<Touch> contractTouches() {
        List<Touch> ts = new ArrayList<>();
        // 稳:近期练过,用户自填正确率 ≥ 60%
        drill(ts, "growth-rate", "粉笔 · 资料分析系统班 L12", 12, 10, 0);      // 83% 今天
        drill(ts, "share-calc", "华图 · 资料速算网课", 9, 8, 1);               // 89% 昨天
        drill(ts, "feature-number", "自己刷题 · 2023 国考真题", 7, 6, 3);      // 86% 3 天前
        // 弱:近期练过,但用户自填正确率 < 60%
        drill(ts, "growth-amount", "自己刷题 · 2023 国考真题", 8, 4, 2);       // 50% 2 天前
        drill(ts, "truncate-divide", "B站 · 资料分析技巧", 6, 2, 4);           // 33% 4 天前
        // 生疏:练过,但超过 30 天没碰
        drill(ts, "base-value", "中公 · 资料分析专项", 5, 4, 32);              // 80% 但 32 天前
        drill(ts, "interval-growth", "中公 · 资料分析专项", 3, 2, 33);         // 67% 但 33 天前
        // 仅接触:听过课,一道题没练
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

    @Test
    @DisplayName("骨架层:18 个考点、5 个题型,与种子文件一致")
    void syllabusShape() {
        assertEquals(18, syllabus.nodeCount(), "考点总数");
        assertEquals(5, syllabus.groups().size(), "题型数");
        assertEquals("山东省考 · 行测 · 资料分析", syllabus.subject().display());
    }

    @Test
    @DisplayName("差集:18 个考点 / 8 个有记录 / 44% / 10 个空白 / 2 组整块空白")
    void matchesDesignContract() {
        List<GroupCoverage> groups = service.compute(syllabus, contractTouches(), NOW);
        Summary s = service.summarize(groups);

        assertEquals(18, s.total(), "考点总数");
        assertEquals(8, s.covered(), "有记录");
        assertEquals(10, s.empty(), "空白");
        assertEquals(44, s.percent(), "覆盖率(设计稿上那个大字)");
        assertEquals(2, s.whollyEmptyGroups(), "整块空白的题型组数");
    }

    @Test
    @DisplayName("五态分布:稳 3 · 弱 2 · 生疏 2 · 仅接触 1 · 空白 10")
    void stateDistribution() {
        Summary s = service.summarize(service.compute(syllabus, contractTouches(), NOW));
        assertEquals(3, s.distribution().get(NodeState.STABLE), "稳");
        assertEquals(2, s.distribution().get(NodeState.WEAK), "弱");
        assertEquals(2, s.distribution().get(NodeState.RUSTY), "生疏");
        assertEquals(1, s.distribution().get(NodeState.TOUCHED_ONLY), "仅接触");
        assertEquals(10, s.distribution().get(NodeState.EMPTY), "空白");
        assertEquals(18, s.distribution().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("整块空白落在「倍数与比较」与「效应类」上 —— 树相对扁平清单的唯一优势")
    void whollyEmptyGroupsAreTheRightOnes() {
        List<GroupCoverage> groups = service.compute(syllabus, contractTouches(), NOW);
        List<String> empty = groups.stream().filter(GroupCoverage::whollyEmpty).map(GroupCoverage::name).toList();
        assertEquals(List.of("倍数与比较", "效应类"), empty);

        GroupCoverage multiple = groups.stream().filter(g -> g.code().equals("multiple")).findFirst().orElseThrow();
        assertEquals(12, multiple.recent5yCount(), "倍数与比较 · 频次合计");
        GroupCoverage effect = groups.stream().filter(g -> g.code().equals("effect")).findFirst().orElseThrow();
        assertEquals(6, effect.recent5yCount(), "效应类 · 频次合计");
    }

    @Test
    @DisplayName("先补这几个 Top 5:名次、分数、并列时的树序,逐条与设计稿一致")
    void blindSpotTop5() {
        List<NodeCoverage> top = service.blindSpots(service.compute(syllabus, contractTouches(), NOW), 5);
        assertEquals(5, top.size());

        assertEquals("增长量计算", top.get(0).name());
        assertEquals(6.4, top.get(0).blindScore(), 1e-9);
        assertEquals("平均数计算", top.get(1).name());
        assertEquals(6.0, top.get(1).blindScore(), 1e-9);
        assertEquals("截位直除", top.get(2).name());
        assertEquals(5.6, top.get(2).blindScore(), 1e-9);
        // 并列 5.0 —— 按树序,现期量计算(增长类)在倍数计算(倍数与比较)之前
        assertEquals("现期量计算", top.get(3).name());
        assertEquals(5.0, top.get(3).blindScore(), 1e-9);
        assertEquals("倍数计算", top.get(4).name());
        assertEquals(5.0, top.get(4).blindScore(), 1e-9);
    }

    @Test
    @DisplayName("能力边界:正确率是用户自填的两个整数相除,没练过时是 null 不是 0")
    void accuracyIsUserEnteredNotJudged() {
        List<GroupCoverage> groups = service.compute(syllabus, contractTouches(), NOW);
        NodeCoverage weak = find(groups, "growth-amount");
        assertEquals(8, weak.practiced());
        assertEquals(4, weak.correct());
        assertEquals(0.50, weak.accuracy(), 1e-9, "8 练 4 对 = 50%,就是用户填的那两个数");

        NodeCoverage untouched = find(groups, "average-calc");
        assertNull(untouched.accuracy(), "没练过应为 null,界面显示「—」,不是 0%");
        assertEquals(NodeState.EMPTY, untouched.state());
    }

    @Test
    @DisplayName("生疏只由时间推出:同样的正确率,隔 30 天内是稳,超过就是生疏")
    void rustyIsPurelyTemporal() {
        List<Touch> recent = List.of(new Touch("a", USER, "growth-rate", "自己刷题",
                TouchKind.DRILL, daysAgo(29), new Touch.Drill(10, 9), null));
        List<Touch> old = List.of(new Touch("b", USER, "growth-rate", "自己刷题",
                TouchKind.DRILL, daysAgo(31), new Touch.Drill(10, 9), null));

        assertEquals(NodeState.STABLE, NodeState.derive(recent, NOW));
        assertEquals(NodeState.RUSTY, NodeState.derive(old, NOW), "同样 90% 正确率,只因为隔久了");
    }

    @Test
    @DisplayName("仅接触与空白必须分开:听过课没练 ≠ 完全没碰过")
    void touchedOnlyIsNotEmpty() {
        List<Touch> lectureOnly = List.of(new Touch("c", USER, "share-change", "粉笔 · 资料分析系统班 L12",
                TouchKind.VOICE, daysAgo(5), null, null));
        assertEquals(NodeState.TOUCHED_ONLY, NodeState.derive(lectureOnly, NOW));
        assertEquals(NodeState.EMPTY, NodeState.derive(List.of(), NOW));
        assertTrue(NodeState.TOUCHED_ONLY.covered(), "仅接触算碰过,计入覆盖度");
        assertFalse(NodeState.EMPTY.covered());
    }

    /**
     * 第八个字段 {@code clientToken} 是去重键(docs/technical/INDEX.md §6.2「client_token 幂等」)。
     * 第二个 {@code userId} 是 B0-3 的租户列 —— 它是归属,不是内容,而且没有它这条记录读回来会被丢弃。
     *
     * <p>它是这条记录上<b>唯一一个来自客户端的字符串</b>,所以它能进来必须有个硬理由:
     * 上限 {@link Touch#MAX_CLIENT_TOKEN_LENGTH} = 64,而 64 装不下任何一道题的题干,
     * 且 {@code Touch} 的构造器会真的拒掉超长的值(不只是一个不参与校验的注解)。
     */
    @Test
    @DisplayName("🔴 记录里只有来源名与时间戳 —— 结构上没有放课程内容的地方")
    void recordCarriesNoCourseContent() {
        // Touch 是 record,它的组件就是它的全部字段。这里断言的是【形状】,不是某次赋值。
        List<String> fields = java.util.Arrays.stream(Touch.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(
                List.of("id", "userId", "nodeCode", "sourceName", "kind", "occurredAt", "drill", "clientToken"),
                fields);

        for (String forbidden : List.of("content", "text", "body", "question", "transcript",
                "imageUrl", "image", "answer", "explanation", "note")) {
            assertFalse(fields.contains(forbidden),
                    "Touch 不允许出现装内容的字段(决策记录 §2.2 不碰内容):" + forbidden);
        }
    }

    @Test
    @DisplayName("🔴 挂载只认考点树里的 code,空 code 直接拒绝(R-07 在构造器上的实现)")
    void mountingRejectsFreeText() {
        assertThrows(IllegalArgumentException.class,
                () -> new Touch("x", USER, "  ", "某来源", TouchKind.MANUAL, NOW, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Touch("x", USER, "growth-rate", "某来源", TouchKind.MANUAL, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Touch.Drill(3, 5), "对的题数不能多于练的题数");
    }

    /**
     * 🔴 这条断言的方向是反的,而反的正是它的价值(M1 §2.5)。
     *
     * <p>上一版断言的是 {@code VOICE.consumesAiQuota() == true} —— 它守的是那个字段的取值,
     * 而那个字段本身说的话就是错的:「哪一次调用是外部模型调用」由<b>调用点</b>决定,不由记录类型决定。
     * 一条用户自己挑了考点的 {@code PHOTO} 记录一次模型都没调,一条点了 {@code tags/suggest} 的
     * {@code MANUAL} 记录调了一次。
     * <p>
     * 留着它的代价不是「多一个没人用的字段」,是下一个实现额度的人在 {@code CaptureService} 里
     * 看到 {@code request.kind().consumesAiQuota()} 触手可及 —— <b>扣额度就会被写进记录写入路径</b>,
     * 而「额度用尽 ≠ 记不了」当场失守。所以这里守的是<b>那个字段不许回来</b>。
     */
    @Test
    @DisplayName("🔴 TouchKind 不许带额度语义 —— 额度归调用点,不归记录类型(M1 §2.5)")
    void touchKindCarriesNoQuotaSemantics() {
        List<String> quotaMembers = new ArrayList<>();
        for (Method m : TouchKind.class.getDeclaredMethods()) {
            if (m.getName().toLowerCase(Locale.ROOT).contains("quota")) {
                quotaMembers.add("方法 " + m.getName());
            }
        }
        for (Field f : TouchKind.class.getDeclaredFields()) {
            if (f.getName().toLowerCase(Locale.ROOT).contains("quota")) {
                quotaMembers.add("字段 " + f.getName());
            }
        }
        assertTrue(quotaMembers.isEmpty(),
                "TouchKind 上不许出现额度语义的成员 —— 它会把扣额度引进记录写入路径:" + quotaMembers);
    }

    private NodeCoverage find(List<GroupCoverage> groups, String code) {
        return groups.stream().flatMap(g -> g.nodes().stream())
                .filter(n -> n.code().equals(code)).findFirst().orElseThrow();
    }
}
