package com.kaodian.server.coverage;

import com.kaodian.server.collect.RecordTag;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 把数据契约的那几个整数钉死在代码上。
 *
 * <p>契约是 <b>18 个考点 / 8 个碰过 / 10 个没碰过 / 2 组整块空白</b>,
 * 「先补这几个」按 {@code recent5y_count} 取前五。
 *
 * <h2>🔴 这里没有百分比,也没有五态分布 —— 是拿掉了,不是漏了</h2>
 *
 * 上一版这个文件断言过 {@code percent() == 44} 和「稳 3 · 弱 2 · 生疏 2」。
 * 两组都已经不存在:{@code M3-骨架与覆盖度差集} §7.2 写死这一域的响应里<b>没有任何浮点</b>,
 * §7.4 把「练了几道 / 对了几道」整个拿出这一域 —— 稳 / 弱 / 生疏正是从那两个数推出来的,
 * 它们回答的是「答得怎么样」,正面撞红线一。
 * <p>
 * ⚠️ <b>不许在测试里自己除一下把百分比算回来。</b>那样做的话,这个数字会从
 * 「结构上不存在」退回「实现里恰好没返回」—— 而下一个人只需要在 record 上加一个字段,
 * 测试还是绿的。
 *
 * <h2>🔴 每个期望值都是手算出来的字面量,不是从另一个返回值推出来的</h2>
 *
 * {@code nodeTouched + nodeUntouched == nodeTotal} 在这里是<b>结论</b>不是定义
 * ({@link CoverageService#summarize} 的注释),所以三个数各自写死一个字面量;
 * 写成 {@code assertEquals(s.nodeTotal() - s.nodeTouched(), s.nodeUntouched())} 就永远为真,
 * 也就永远测不出任何东西。
 */
class CoverageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    /** 测试用户 —— 与行为层种子同一个 id(B0 §3.3:auth 侧从 10001 起号)。 */
    private static final long USER = 10001L;

    private final CoverageService service = new CoverageService();
    private final Syllabus syllabus = SyllabusLoader.loadDefault();

    /**
     * 按数据契约构造行为层:8 个考点有记录,其余 10 个一条都没有。
     *
     * <p>⚠️ {@code practiced / correct} 仍然填着,但<b>这一域已经不读它们了</b> ——
     * 它们留在 {@link Touch.Drill} 上是因为「用户自己填的两个整数」属于「几次」,
     * 而覆盖度这一域只问「有没有」。填着是为了让这份夹具与行为层种子逐字一致,
     * 不是因为哪条断言用得上它们。
     */
    private List<Touch> contractTouches() {
        List<Touch> ts = new ArrayList<>();
        drill(ts, "growth-rate", "粉笔 · 资料分析系统班 L12", 12, 10, 0);
        drill(ts, "share-calc", "华图 · 资料速算网课", 9, 8, 1);
        drill(ts, "feature-number", "自己刷题 · 2023 国考真题", 7, 6, 3);
        drill(ts, "growth-amount", "自己刷题 · 2023 国考真题", 8, 4, 2);
        drill(ts, "truncate-divide", "B站 · 资料分析技巧", 6, 2, 4);
        drill(ts, "base-value", "中公 · 资料分析专项", 5, 4, 32);
        drill(ts, "interval-growth", "中公 · 资料分析专项", 3, 2, 33);
        // 听过课、一道题没练。它是 TOUCHED,不是 UNTOUCHED —— 见 touchedIsNotUntouched
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

    /** 契约夹具算出来的差集。标签由主标签派生,断言表为空 —— 与 {@code CoverageReader.read} 同一条路。 */
    private List<GroupCoverage> contractGroups() {
        List<Touch> touches = contractTouches();
        return service.compute(syllabus, touches,
                RecordTag.effectiveTagsOf(touches, List.of()), List.of(), NOW);
    }

    @Test
    @DisplayName("骨架层:18 个考点、5 个题型,与种子文件一致")
    void syllabusShape() {
        assertEquals(18, syllabus.nodeCount(), "考点总数");
        assertEquals(5, syllabus.groups().size(), "题型数");
        assertEquals("山东省考 · 行测 · 资料分析", syllabus.subject().display());
    }

    @Test
    @DisplayName("差集:18 个考点 / 8 个碰过 / 10 个没碰过 / 0 归档 / 0 已声明")
    void matchesDesignContract() {
        Summary s = service.summarize(contractGroups());

        // 五个数五个字面量。少写一个都会让「另外四个还对着」变成一句没人验过的话。
        assertEquals(18, s.nodeTotal(), "分母 |D| —— 未归档的骨架叶子节点");
        assertEquals(8, s.nodeTouched(), "分子 |N| —— 有计覆盖度标签的");
        assertEquals(10, s.nodeUntouched(), "差集 |D∖N| —— 数出来的,不是 18−8 减出来的");
        assertEquals(0, s.archivedCount(), "🔴 恒在,为 0 也返回(R-49:归档三件事都不做成开关)");
        assertEquals(0, s.assertedCount(), "这份夹具里没人按过「我已经会了」");
    }

    @Test
    @DisplayName("整块空白落在「倍数与比较」与「效应类」上 —— 树相对扁平清单的唯一优势")
    void whollyEmptyGroupsAreTheRightOnes() {
        List<GroupCoverage> groups = contractGroups();

        List<String> empty = groups.stream().filter(GroupCoverage::whollyEmpty).map(GroupCoverage::name).toList();
        assertEquals(List.of("倍数与比较", "效应类"), empty, "整块空白是哪两组,不只是几组");

        // 🔴 每组两个数都由服务端数出来,端不做减法(U3.1 §2.1)。所以两个都得断言:
        //    只断言 touchedCount 的话,一个写成 nodes.size() - touchedCount 的实现照样全绿,
        //    而它在归档节点进了 nodes() 之后会把归档的那一个算进「没碰过」。
        assertGroup(groups, "growth", 4, 3);
        assertGroup(groups, "multiple", 0, 3);
        assertGroup(groups, "effect", 0, 2);
        assertGroup(groups, "average-share", 2, 1);
        assertGroup(groups, "fast-math", 2, 1);
    }

    @Test
    @DisplayName("先补这几个:按近五年出现次数取前五,同分时按骨架自然序")
    void blindSpotTop5() {
        // 默认档 = UNTOUCHED + RECENT5Y_COUNT:「先补这几个」问的是「哪几个没碰过的最值得先补」。
        List<NodeCoverage> top = service.blindSpots(
                contractGroups(), BlindspotOrder.RECENT5Y_COUNT, BlindspotFilter.UNTOUCHED, false, 5);

        assertEquals(List.of("平均数计算", "现期量计算", "倍数计算", "年均增长率", "同比与环比"),
                top.stream().map(NodeCoverage::name).toList());
        // 出现次数一并钉住:名次对而次数不对,说明榜是按别的东西排的,只是碰巧同序
        assertEquals(List.of(6, 5, 5, 4, 4), top.stream().map(NodeCoverage::recent5yCount).toList());

        for (NodeCoverage n : top) {
            assertEquals(NodeState.UNTOUCHED, n.state(), "默认档里只该有没碰过的:" + n.code());
        }
    }

    /**
     * 🔴 三级排序链:<b>当前口径 → 骨架自然序 → code 字典序</b>。
     *
     * <p>三级之后不可能再有并列({@code code} 在一棵树里唯一),所以同样的输入永远得到同样一份清单。
     * 「先补这几个」每次刷新换一批,和没有这份清单是一样的。
     *
     * <p>第二级与第三级必须分开验:契约夹具里 {@code syllabusOrder} 两两不同,
     * 第三级<b>永远走不到</b> —— 把 {@code .thenComparing(code)} 整行删掉,上面那些断言一条都不会红。
     * 所以下半段手工造一对<b>同序号</b>的节点,而且故意按 code 倒序塞进去:
     * 排序是稳定的,少了第三级它们会原样保持倒序。
     */
    @Test
    @DisplayName("🔴 排序三级链:同口径按骨架序,同骨架序按 code 字典序 —— 到此不可能再并列")
    void blindSpotOrderIsTotal() {
        List<NodeCoverage> all = service.blindSpots(
                contractGroups(), BlindspotOrder.RECENT5Y_COUNT, BlindspotFilter.UNTOUCHED, false, 20);

        assertEquals(10, all.size(), "没碰过的一共 10 个,top 大于总数时不补空也不截断");
        // 三个 3 次的:倍数变化(骨架序 8)· 贡献率(10)· 拉动增长(11)· 分数比较(17)。
        // 按 code 字典序的话 contribution-rate 会排到 multiple-change 前面 —— 二级不是字典序,是树序。
        assertEquals(List.of("average-calc", "current-value", "multiple-calc", "annual-avg-growth", "yoy-mom",
                        "multiple-change", "contribution-rate", "pull-growth", "fraction-compare", "mixed-growth"),
                all.stream().map(NodeCoverage::code).toList());

        NodeCoverage z = new NodeCoverage("z-node", "Z", "g", "G", 7, NodeState.UNTOUCHED,
                3, 0, null, List.of(), false);
        NodeCoverage a = new NodeCoverage("a-node", "A", "g", "G", 7, NodeState.UNTOUCHED,
                3, 0, null, List.of(), false);
        List<NodeCoverage> tied = service.blindSpots(
                List.of(new GroupCoverage("g", "G", List.of(z, a), 0, 2)),
                BlindspotOrder.RECENT5Y_COUNT, BlindspotFilter.UNTOUCHED, false, 5);

        assertEquals(List.of("a-node", "z-node"), tied.stream().map(NodeCoverage::code).toList(),
                "同口径同骨架序时按 code 字典序 —— 输入是倒着给的,少了第三级就会原样倒着出来");
    }

    /**
     * 「多久前」这一件事,在新模型里由 {@link NodeCoverage#lastTouchAt()} 一个绝对时刻承担,
     * 服务端<b>不返回天数</b>、也不再有「超过 30 天就叫生疏」这种状态。
     *
     * <p>上一版这里断言的是「同样的正确率,29 天是稳、31 天是生疏」——
     * 那条已经删掉:它拿正确率当输入,回答的是「答得怎么样」。
     * 剩下来的那一半「多久前」搬到了排序口径上,就是这条。
     */
    @Test
    @DisplayName("🔴 按「多久没碰」排:从没碰过的排最前,不是最后 —— 它正是差集的正主")
    void lastTouchOrderPutsNeverTouchedFirst() {
        List<NodeCoverage> board = service.blindSpots(
                contractGroups(), BlindspotOrder.LAST_TOUCH_AT, BlindspotFilter.ALL, false, 12);

        for (int i = 0; i < 10; i++) {
            assertNull(board.get(i).lastTouchAt(),
                    "前 10 位该是从没碰过的那 10 个,第 " + i + " 位却有触达时刻");
        }
        // 第 11、12 位才是碰过里最久的两个:间隔增长率 33 天前、基期量 32 天前。
        // missingKeyFirst 反过来的话,这两个会顶到榜首,而「最久没碰」的第一名反而是从没碰过的那批被挤到末尾。
        assertEquals("interval-growth", board.get(10).code());
        assertEquals(daysAgo(33), board.get(10).lastTouchAt());
        assertEquals("base-value", board.get(11).code());
        assertEquals(daysAgo(32), board.get(11).lastTouchAt());
    }

    /**
     * 「听过课没练」与「完全没碰过」必须分开 —— 差集的全部意义就在这条线上。
     *
     * <p>上一版这两档叫 {@code TOUCHED_ONLY} 与 {@code EMPTY},分档依据里混着「练了几道」;
     * 现在依据只剩一个「有没有计覆盖度的标签」,而那个 VOICE 记录照样是一次触达。
     */
    @Test
    @DisplayName("碰过与没碰过必须分开:听过课没练,也是碰过")
    void touchedIsNotUntouched() {
        NodeCoverage lecture = find(contractGroups(), "share-change");
        assertEquals(NodeState.TOUCHED, lecture.state(), "一条 VOICE 记录就是一次触达,不看练没练");
        assertEquals(1, lecture.touchCount());
        assertEquals(daysAgo(5), lecture.lastTouchAt());

        NodeCoverage never = find(contractGroups(), "average-calc");
        assertEquals(NodeState.UNTOUCHED, never.state());
        assertEquals(0, never.touchCount(), "🔴 没碰过就是 0,不是 null —— 这一档由这个 0 表达");
        assertNull(never.lastTouchAt(), "从没碰过才是 null");

        // 五态互斥且穷尽,三个谓词各自把它们切成一刀。切错任何一刀,那三个数就一起错。
        assertTrue(NodeState.TOUCHED.inNumerator());
        assertFalse(NodeState.UNTOUCHED.inNumerator());
        assertTrue(NodeState.TOUCHED.inDenominator() && NodeState.UNTOUCHED.inDenominator());
        assertFalse(NodeState.TOUCHED.inBlindSet(), "碰过的不在差集里");
        assertTrue(NodeState.UNTOUCHED.inBlindSet());
        // 归档与树外的一个数都不进 —— 归档只退分子会让覆盖度掉,只退分母会让它涨,两种都是在编数
        for (NodeState out : List.of(NodeState.ARCHIVED, NodeState.GONE)) {
            assertFalse(out.inDenominator(), out + " 不该进分母");
            assertFalse(out.inNumerator(), out + " 不该进分子");
            assertFalse(out.inBlindSet(), out + " 不该进差集");
        }
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

    @Test
    @DisplayName("手动记录不消耗 AI 额度 —— 额度用尽 ≠ 记不了")
    void manualKindsNeverConsumeQuota() {
        assertTrue(TouchKind.VOICE.consumesAiQuota());
        assertTrue(TouchKind.PHOTO.consumesAiQuota());
        assertFalse(TouchKind.PASTE.consumesAiQuota());
        assertFalse(TouchKind.DRILL.consumesAiQuota());
        assertFalse(TouchKind.MANUAL.consumesAiQuota());
    }

    private void assertGroup(List<GroupCoverage> groups, String code, int touched, int untouched) {
        GroupCoverage g = groups.stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
        assertEquals(touched, g.touchedCount(), code + " · 碰过");
        assertEquals(untouched, g.untouchedCount(), code + " · 没碰过");
    }

    private NodeCoverage find(List<GroupCoverage> groups, String code) {
        return groups.stream().flatMap(g -> g.nodes().stream())
                .filter(n -> n.code().equals(code)).findFirst().orElseThrow();
    }
}
