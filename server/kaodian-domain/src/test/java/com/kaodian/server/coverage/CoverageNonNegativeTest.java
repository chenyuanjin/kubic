package com.kaodian.server.coverage;

import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.TagOrigin;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.UserAssertion;
import com.kaodian.server.coverage.CoverageService.Summary;
import com.kaodian.server.syllabus.Syllabus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 覆盖度算不出负数 —— {@code M3-骨架与覆盖度差集} §1.3(判据总表第 2 条)。
 *
 * <h2>它会怎么变成负数,而那种写法恰好是最自然的一种</h2>
 *
 * <pre>
 * -- ❌ 会算出负数的写法:两个数来自两次口径不同的查询,再相减
 * total    = COUNT(*)                   WHERE level=3 AND archived=0 AND subject=?
 * touched  = COUNT(DISTINCT tag.node_id) WHERE user_id=? AND discarded=0
 * untouched = total − touched
 * </pre>
 *
 * 第二条查询里标签的 {@code nodeCode} <b>不受 {@code level}、不受 {@code archived}、
 * 不受 {@code subject}、不受骨架版本约束</b>。四个缺口任意一个被踩到,
 * {@code touched > total},{@code untouched} 立刻为负 —— 而屏幕上会直接出现
 * <b>「没碰过 −3 个」</b>,而那一屏是这个产品唯一的产出。
 *
 * <h2>下面四条脏数据,每一条对应一个现实可达的缺口</h2>
 *
 * <table border="1">
 *   <caption>四个缺口的现实触发路径</caption>
 *   <tr><th>缺口</th><th>什么时候踩到</th></tr>
 *   <tr><td>标签挂在已归档节点上</td><td>归档发生在打标之后 —— 归档端点今天就能做到</td></tr>
 *   <tr><td>标签挂在非叶子节点上</td><td>手动挂载挂到了题型上</td></tr>
 *   <tr><td>标签跨科目</td><td>多科目下同一用户</td></tr>
 *   <tr><td>标签指向旧版骨架的节点</td><td>骨架版本更替,旧 code 不在新树里({@code GONE})</td></tr>
 * </table>
 *
 * 🔴 四条都必须被<b>安静忽略</b> —— 不抛异常、不报错。它们是数据问题不是请求问题,
 * 报错会让一屏正常内容因为一条脏标签整个打不开。
 */
class CoverageNonNegativeTest {

    private static final long USER = 10001L;
    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    private final CoverageService service = new CoverageService();

    /**
     * 当前科目的树:两个未归档叶子 + 一个已归档叶子。
     *
     * <p>题型 {@code growth} 本身是一个<b>非叶子</b>的 code —— 手动挂载把标签挂到它身上,
     * 就是「挂到了题型上」那个缺口。
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

    private static RecordTag countingTag(String id, String recordId, String nodeCode) {
        return new RecordTag(id, USER, recordId, nodeCode, RecordTag.MANUAL_CONFIDENCE,
                TagOrigin.MANUAL, NOW.minusSeconds(3600), false);
    }

    /**
     * 每一条脏数据都跑同一组断言。
     *
     * <p>🔴 {@code nodeTouched + nodeUntouched == nodeTotal} 在这里是一条<b>结论</b>,
     * 不是定义:三个数各自在同一个循环里 {@code ++},谁都不是别人减出来的。
     * 写成定义的那一版(减法)让这条断言<b>永远为真</b>,也就永远测不出任何东西 ——
     * 这正是本类要挡的那件事。
     */
    private void assertSaneUnder(String what, List<Touch> touches, List<RecordTag> tags) {
        Summary s = service.summarize(
                service.compute(tree(), touches, tags, List.of(), NOW));

        assertTrue(s.nodeUntouched() >= 0, what + ":「没碰过」算成了负数 —— " + s);
        assertTrue(s.nodeTouched() >= 0, what + ":「碰过」算成了负数 —— " + s);
        assertEquals(s.nodeTotal(), s.nodeTouched() + s.nodeUntouched(),
                what + ":碰过 + 没碰过 ≠ 考点总数,恒等式破了 —— " + s);
        // 分母是这棵树的固有事实:未归档叶子两个。任何一条脏标签都不该改变它。
        assertEquals(2, s.nodeTotal(), what + ":一条脏标签改变了分母");
        assertEquals(1, s.archivedCount(), what + ":一条脏标签改变了归档计数");
    }

    @Test
    @DisplayName("🔴 ① 一条计覆盖度的标签挂在【已归档】节点上 —— 归档发生在打标之后")
    void tagOnArchivedNode() {
        assertSaneUnder("标签挂在已归档节点上",
                List.of(touchOn("t-z", "z")),
                List.of(countingTag("g-z", "t-z", "z")));
    }

    @Test
    @DisplayName("🔴 ② 一条标签挂在【非叶子】节点(题型)上 —— 手动挂载不校验层级")
    void tagOnNonLeafNode() {
        assertSaneUnder("标签挂在题型上",
                List.of(touchOn("t-g", "growth")),
                List.of(countingTag("g-g", "t-g", "growth")));
    }

    @Test
    @DisplayName("🔴 ③ 一条标签挂在【别的科目】的考点上 —— record_tag 上没有 subject")
    void tagFromAnotherSubject() {
        assertSaneUnder("标签跨科目",
                List.of(touchOn("t-x", "shuliang-jisuan")),
                List.of(countingTag("g-x", "t-x", "shuliang-jisuan")));
    }

    @Test
    @DisplayName("🔴 ④ 一条标签指向【当前骨架版本里不存在】的 nodeCode —— 换版之后的旧行")
    void tagPointingAtAGoneNode() {
        assertSaneUnder("标签指向旧版骨架",
                List.of(touchOn("t-old", "old-2024-node")),
                List.of(countingTag("g-old", "t-old", "old-2024-node")));
    }

    @Test
    @DisplayName("🔴 四条脏数据一起来,而且一条都不许抛异常")
    void allFourAtOnceAndNothingThrows() {
        List<Touch> touches = List.of(
                touchOn("t-z", "z"),
                touchOn("t-g", "growth"),
                touchOn("t-x", "shuliang-jisuan"),
                touchOn("t-old", "old-2024-node"),
                touchOn("t-a", "a"));                 // 一条干净的,证明脏数据没把正常的一起吞掉
        List<RecordTag> tags = List.of(
                countingTag("g-z", "t-z", "z"),
                countingTag("g-g", "t-g", "growth"),
                countingTag("g-x", "t-x", "shuliang-jisuan"),
                countingTag("g-old", "t-old", "old-2024-node"),
                countingTag("g-a", "t-a", "a"));

        Summary s = service.summarize(service.compute(tree(), touches, tags, List.of(), NOW));
        assertEquals(2, s.nodeTotal());
        assertEquals(1, s.nodeTouched(), "四条脏标签一条都不该进分子,而干净的那条必须进");
        assertEquals(1, s.nodeUntouched());
        assertEquals(1, s.archivedCount());
        assertTrue(s.nodeUntouched() >= 0);
    }

    @Test
    @DisplayName("🔴 一条指向树外考点的【断言行】同样被安静忽略")
    void assertionOnAGoneNodeIsIgnoredToo() {
        Summary s = service.summarize(service.compute(
                tree(), List.of(), List.of(),
                List.of(new UserAssertion(USER, "old-2024-node", NOW)), NOW));

        assertEquals(2, s.nodeTotal());
        assertEquals(0, s.nodeTouched());
        assertEquals(2, s.nodeUntouched());
        // 断言表里有一行,而 assertedCount 是 0 —— 因为 |A| 只数树上的节点。
        // 「数一遍断言表的行数」的那一版这里会返回 1,而屏上那一格该显示 0。
        assertEquals(0, s.assertedCount());
    }
}
