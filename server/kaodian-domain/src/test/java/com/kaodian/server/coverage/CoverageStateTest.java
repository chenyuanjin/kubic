package com.kaodian.server.coverage;

import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.TagOrigin;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.UserAssertion;
import com.kaodian.server.coverage.CoverageService.GroupCoverage;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.coverage.CoverageService.Summary;
import com.kaodian.server.syllabus.Syllabus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 五态推导 —— {@code M3-骨架与覆盖度差集} §1.1 的判据(判据总表第 1 条)。
 *
 * <h2>🔴 这个类不启动 Spring,不发一个 HTTP 请求</h2>
 *
 * 依赖图上「{@code domain} 无依赖」那条边就是为这条公式留的:
 * <b>公式必须能脱离 HTTP 被测</b>(§1.4)。要一个 {@code MockMvc} 才能验证的差集,
 * 等于把这个产品唯一的那个数锁在了接口层里。
 *
 * <h2>这个类守的那一条:{@code TOUCHED} 优先于 {@code ASSERTED}</h2>
 *
 * {@code U3.3} §2.4 把它写成集合关系 {@code 没碰过 ∪ 已经会了 = 没碰过},
 * 即 {@code ASSERTED ⊆ 没碰过}。而界面<b>不禁止</b>用户断言一个已经碰过的考点
 * ({@code U3.6} §2.1 只在归档时禁用开关)—— 于是「断言了之后又碰过」这一格必须有人定。
 * <p>
 * 定成 {@code ASSERTED} 优先的话:一个已经碰过的节点会从分子里掉出来,
 * <b>点一下按钮就能让覆盖度下降</b>,而 {@code U3.6} §2.2 逐字写着断言之后三个数一个都不变。
 * 定成 {@code TOUCHED} 优先,{@code ASSERTED ⊆ 没碰过} 就从一条<b>要靠自觉的约定</b>
 * 变成一条<b>结构上不可能被破坏的事实</b>。
 */
class CoverageStateTest {

    private static final long USER = 10001L;
    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    private final CoverageService service = new CoverageService();

    /**
     * 一棵最小的树:一个题型、两个未归档叶子 + 一个已归档叶子。
     *
     * <p>三个节点各自守一件事:{@code a} 是被推来推去的那一个,{@code b} 是「别的节点不受影响」
     * 的对照,{@code z} 是归档那一档 —— <b>它必须一直在树里</b>,因为 {@code archivedCount}
     * 是响应的必填字段,而「先过滤掉已归档再算」的那一版数不出这个字段。
     */
    private static Syllabus tree() {
        return new Syllabus(
                new Syllabus.Subject("ziliao", "国考", "行测", "资料分析", "2021-2025"),
                List.of(new Syllabus.Group("growth", "增长率", List.of(
                        new Syllabus.Node("a", "增长率计算", 14, false),
                        new Syllabus.Node("b", "增长量计算", 9, false),
                        new Syllabus.Node("z", "已经不考了的那个", 0, true)))));
    }

    private static Touch touchOn(String id, String nodeCode) {
        return new Touch(id, USER, nodeCode, "某网课", TouchKind.MANUAL,
                NOW.minusSeconds(3600), null, null);
    }

    /** 一条计覆盖度的手动标签 —— {@code discarded=false}。 */
    private static RecordTag countingTag(String id, String recordId, String nodeCode) {
        return new RecordTag(id, USER, recordId, nodeCode, RecordTag.MANUAL_CONFIDENCE,
                TagOrigin.MANUAL, NOW.minusSeconds(3600), false);
    }

    /** 一条被丢弃的标签 —— 可见,但<b>不计覆盖度</b>({@code P1-7} 宁缺毋滥的落地)。 */
    private static RecordTag discardedTag(String id, String recordId, String nodeCode) {
        return new RecordTag(id, USER, recordId, nodeCode, RecordTag.MANUAL_CONFIDENCE,
                TagOrigin.MANUAL, NOW.minusSeconds(3600), true);
    }

    private Summary summarize(List<Touch> touches, List<RecordTag> tags,
                              List<UserAssertion> assertions) {
        return service.summarize(service.compute(tree(), touches, tags, assertions, NOW));
    }

    private NodeCoverage node(String code, List<Touch> touches, List<RecordTag> tags,
                              List<UserAssertion> assertions) {
        for (GroupCoverage g : service.compute(tree(), touches, tags, assertions, NOW)) {
            for (NodeCoverage n : g.nodes()) {
                if (n.code().equals(code)) {
                    return n;
                }
            }
        }
        throw new AssertionError("树里没有这个考点:" + code);
    }

    @Test
    @DisplayName("① 断言一个没碰过的考点 → ASSERTED,那三个数一个都不变")
    void assertingAnUntouchedNodeMovesNoNumber() {
        Summary before = summarize(List.of(), List.of(), List.of());
        // 手数一遍夹具:未归档叶子两个 → 分母 2;一条标签都没有 → 分子 0;
        // 归档叶子一个 → archivedCount 1。这些是【字面量】,不是从别的数算出来的。
        assertEquals(2, before.nodeTotal());
        assertEquals(0, before.nodeTouched());
        assertEquals(2, before.nodeUntouched());
        assertEquals(1, before.archivedCount());
        assertEquals(0, before.assertedCount());

        List<UserAssertion> asserted = List.of(new UserAssertion(USER, "a", NOW));
        Summary after = summarize(List.of(), List.of(), asserted);

        // 🔴 这四行就是 U3.6 §2.2「断言之后三个数一个都不变」。
        //    它们不是重复:少写一行,那一个数就可以在不被发现的情况下动。
        assertEquals(2, after.nodeTotal());
        assertEquals(0, after.nodeTouched());
        assertEquals(2, after.nodeUntouched());
        assertEquals(1, after.archivedCount());
        // 变的只有这一个,而且是单列的那一格。
        assertEquals(1, after.assertedCount());

        assertEquals(NodeState.ASSERTED, node("a", List.of(), List.of(), asserted).state());
        assertTrue(node("a", List.of(), List.of(), asserted).asserted());
    }

    @Test
    @DisplayName("② 断言之后又碰过 → TOUCHED(不是 ASSERTED),assertedCount 减一,而断言行仍在")
    void touchedBeatsAsserted() {
        List<UserAssertion> asserted = List.of(new UserAssertion(USER, "a", NOW));
        List<Touch> touches = List.of(touchOn("t-1", "a"));
        List<RecordTag> tags = List.of(countingTag("g-1", "t-1", "a"));

        Summary s = summarize(touches, tags, asserted);
        assertEquals(2, s.nodeTotal());
        assertEquals(1, s.nodeTouched(), "碰过了就该进分子 —— 断言不该把它挡在外面");
        assertEquals(1, s.nodeUntouched());
        // 🔴 这一行是本类的正主。assertedCount 从 1 掉回 0,因为 |A| 的定义里含着 B = D∖N,
        //    而这个节点已经不在 B 里了。实现成「数一遍断言表的行数」的那一版这里会返回 1 ——
        //    比屏上该显示的多一个,而且不会有任何别的地方报错。
        assertEquals(0, s.assertedCount());

        NodeCoverage a = node("a", touches, tags, asserted);
        assertEquals(NodeState.TOUCHED, a.state(), "TOUCHED > ASSERTED,优先级链的第三与第四位");
        // 🔴 断言行保留不删:用户从没取消过它。删了就要求他再断言一次,
        //    而那是产品从没许诺过的一次额外操作。
        assertTrue(a.asserted(), "state 变了,但那一行还在 —— 两者是两个事实");
    }

    @Test
    @DisplayName("③ 把那条标签丢弃 → 回到 ASSERTED,不是 UNTOUCHED")
    void discardingTheTagFallsBackToAssertedNotUntouched() {
        List<UserAssertion> asserted = List.of(new UserAssertion(USER, "a", NOW));
        List<Touch> touches = List.of(touchOn("t-1", "a"));
        List<RecordTag> discarded = List.of(discardedTag("g-1", "t-1", "a"));

        NodeCoverage a = node("a", touches, discarded, asserted);
        // 如果 ② 那一步把断言行删掉了,这里就会是 UNTOUCHED —— 用户会发现自己
        // 「我已经会了」的开关被系统悄悄关掉了,而他从没碰过那个开关。
        assertEquals(NodeState.ASSERTED, a.state());

        Summary s = summarize(touches, discarded, asserted);
        assertEquals(0, s.nodeTouched(), "被丢弃的标签可见,但不计覆盖度");
        assertEquals(1, s.assertedCount());
    }

    @Test
    @DisplayName("④ 归档 → ARCHIVED,同时退分子与分母,archivedCount 加一")
    void archivedLeavesBothSidesAtOnce() {
        // z 在夹具里就是归档的,并且它身上挂着一条计覆盖度的标签 ——
        // 「归档发生在打标之后」是现实路径,不是构造出来的边角。
        List<Touch> touches = List.of(touchOn("t-z", "z"));
        List<RecordTag> tags = List.of(countingTag("g-z", "t-z", "z"));

        Summary s = summarize(touches, tags, List.of());
        assertEquals(2, s.nodeTotal(), "归档节点不进分母");
        assertEquals(0, s.nodeTouched(), "🔴 也不进分子 —— 只退一边就是在编数");
        assertEquals(2, s.nodeUntouched());
        assertEquals(1, s.archivedCount());

        assertEquals(NodeState.ARCHIVED, node("z", touches, tags, List.of()).state());
    }

    @Test
    @DisplayName("🔴 五态互斥且穷尽:每个节点恰好落在一个取值上,三个谓词的答案自洽")
    void theFiveStatesArePartitioned() {
        List<Touch> touches = List.of(touchOn("t-1", "a"));
        List<RecordTag> tags = List.of(countingTag("g-1", "t-1", "a"));
        List<UserAssertion> asserted = List.of(new UserAssertion(USER, "b", NOW));

        for (GroupCoverage g : service.compute(tree(), touches, tags, asserted, NOW)) {
            for (NodeCoverage n : g.nodes()) {
                NodeState s = n.state();
                // 分子必然在分母里 —— 这就是 N ⊆ D,而整条公式的正确性靠它。
                if (s.inNumerator()) {
                    assertTrue(s.inDenominator(), s + ":进了分子却不在分母里");
                }
                // 「碰过」与「没碰过」不许同时成立,也不许同时不成立(在分母内)。
                if (s.inDenominator()) {
                    assertTrue(s.inNumerator() ^ s.inBlindSet(),
                            s + ":「碰过」与「没碰过」必须恰好成立一个");
                } else {
                    assertFalse(s.inNumerator());
                    assertFalse(s.inBlindSet());
                }
            }
        }
    }

    @Test
    @DisplayName("🔴 优先级链逐格钉死:GONE > ARCHIVED > TOUCHED > ASSERTED > UNTOUCHED")
    void thePriorityLadderIsPinnedCellByCell() {
        // 每一行都让【更高优先级的那个条件】和一个更低的同时成立,断言高的赢。
        assertEquals(NodeState.GONE, NodeState.derive(false, true, true, true),
                "不在骨架里就是 GONE —— 其余三个条件说什么都不算数");
        assertEquals(NodeState.ARCHIVED, NodeState.derive(true, true, true, true),
                "归档压过「碰过」与「说会了」");
        assertEquals(NodeState.TOUCHED, NodeState.derive(true, false, true, true),
                "🔴 碰过压过说会了 —— 这一格反过来,点一下按钮就能让覆盖度下降");
        assertEquals(NodeState.ASSERTED, NodeState.derive(true, false, false, true));
        assertEquals(NodeState.UNTOUCHED, NodeState.derive(true, false, false, false));
    }
}
