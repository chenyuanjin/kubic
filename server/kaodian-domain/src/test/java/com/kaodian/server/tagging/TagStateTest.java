package com.kaodian.server.tagging;

import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.TagOrigin;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态推导与未分类口径 —— {@code M2-打标管线与模型接入} §4.3 / §8.1。
 */
class TagStateTest {

    private static final long USER = 10001L;
    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final Syllabus TREE = SyllabusLoader.loadDefault();

    private static Touch touch() {
        return new Touch("t-1", USER, "growth-rate", "自己刷题", TouchKind.PHOTO, NOW, null, null);
    }

    private static RecordTag tag(String id, String node, Instant confirmedAt, boolean discarded) {
        return new RecordTag(id, USER, "t-1", node, RecordTag.MANUAL_CONFIDENCE,
                TagOrigin.MANUAL, confirmedAt, discarded);
    }

    @Test
    @DisplayName("🔴 未分类的口径 = TS-05 ∪ TS-06,TS-00 / TS-01 不算")
    void unclassifiedIsExactlyTwoStates() {
        for (TagState state : TagState.values()) {
            assertEquals(state == TagState.TS_05 || state == TagState.TS_06,
                    TagState.isUnclassified(state),
                    state + " 算不算未分类,全库只许这一处说了算");
        }
        // TS-00 / TS-01 算进去的话,这个数会随后台重试自己跳动,而用户什么都没做。
        assertFalse(TagState.isUnclassified(TagState.TS_00));
        assertFalse(TagState.isUnclassified(TagState.TS_01));
    }

    @Test
    @DisplayName("六种成因各自落到哪一格 —— §4.4 那张表逐行")
    void everyOutcomeLandsOnItsState() {
        // 有效标签全丢掉,状态才轮得到「为什么没对上」来决定。
        List<RecordTag> discardedOnly = List.of(tag("primary-t-1", "growth-rate", NOW, true));
        record Row(TagAttempt.Outcome outcome, TagState expected) {}
        for (Row row : List.of(
                new Row(TagAttempt.Outcome.NO_MATCH, TagState.TS_05),
                new Row(TagAttempt.Outcome.NOT_RECALLED, TagState.TS_05),
                new Row(TagAttempt.Outcome.NO_MATERIAL, TagState.TS_05),
                new Row(TagAttempt.Outcome.SYLLABUS_EMPTY, TagState.TS_05),
                new Row(TagAttempt.Outcome.UNAVAILABLE, TagState.TS_06),
                new Row(TagAttempt.Outcome.QUOTA_EXHAUSTED, TagState.TS_06))) {
            TagAttempt attempt = TagAttempt.settled("t-1", USER, row.outcome(), NOW);
            assertEquals(row.expected(), TagState.of(touch(), discardedOnly, attempt, TREE),
                    row.outcome() + " 落错格了");
        }
    }

    @Test
    @DisplayName("🔴 NOT_RECALLED 与 NO_MATCH 在界面上一格不差,在库里必须分得开(C-1 那次复核查的就是它)")
    void notRecalledAndNoMatchShareAStateButNotAValue() {
        List<RecordTag> none = List.of(tag("primary-t-1", "growth-rate", NOW, true));
        assertEquals(TagState.of(touch(), none,
                        TagAttempt.settled("t-1", USER, TagAttempt.Outcome.NOT_RECALLED, NOW), TREE),
                TagState.of(touch(), none,
                        TagAttempt.settled("t-1", USER, TagAttempt.Outcome.NO_MATCH, NOW), TREE));
        // 而枚举值不同 —— 合并它们,C-1 就从「已知盲点」变成「永远查不出来」。
        assertTrue(TagAttempt.Outcome.NOT_RECALLED != TagAttempt.Outcome.NO_MATCH);
    }

    @Test
    @DisplayName("还没触发 → TS-00;已触发未回 → TS-01;有候选 → TS-02;确认了 → TS-03/TS-07")
    void theHappyPathStates() {
        RecordTag candidate = new RecordTag("tag-1", USER, "t-1", "interval-growth", 0.9,
                TagOrigin.AUTO, null, false);
        assertEquals(TagState.TS_02,
                TagState.of(touch(), List.of(tag("primary-t-1", "growth-rate", NOW, true), candidate),
                        TagAttempt.settled("t-1", USER, TagAttempt.Outcome.SUGGESTED, NOW), TREE));

        assertEquals(TagState.TS_01, TagState.of(touch(), List.of(),
                TagAttempt.settled("t-1", USER, TagAttempt.Outcome.RUNNING, NOW), TREE));
        assertEquals(TagState.TS_00, TagState.of(touch(), List.of(), null, TREE));

        // 手动挑的 → TS-07;模型挑的被确认 → TS-03。区别只在显示,不在行为。
        assertEquals(TagState.TS_07,
                TagState.of(touch(), List.of(tag("primary-t-1", "growth-rate", NOW, false)), null, TREE));
        assertEquals(TagState.TS_03, TagState.of(touch(),
                List.of(new RecordTag("tag-1", USER, "t-1", "interval-growth", 0.9,
                        TagOrigin.AUTO, NOW, false)), null, TREE));
    }

    @Test
    @DisplayName("🔴 确认 → 丢弃 → 恢复:三步之后覆盖度与第一步之前相等,而且状态是 TS-02")
    void restoreNeverRaisesCoverage() {
        Touch touch = touch();
        RecordTag confirmed = tag("primary-t-1", "growth-rate", NOW, false);
        int before = coveredNodes(touch, List.of(confirmed));

        RecordTag discarded = confirmed.discard();
        RecordTag restored = discarded.restore();

        assertFalse(restored.discarded(), "恢复之后不该还是丢弃态");
        assertNull(restored.confirmedAt(),
                "🔴 不清空 confirmedAt 的话,这条会直接落回 TS-03 —— 那是一条系统触发、"
                        + "且终点计覆盖度的转移,U2.2 §2.4「没有任何一条系统触发的转移会让覆盖度上升」当场破");
        assertEquals(TagState.TS_02, TagState.of(touch, List.of(restored), null, TREE));

        // 覆盖度那一格回来了(丢弃时它掉出去过),但它是「待确认」——
        // 而 TS-02 本来就在分子里(分子 = discarded=0),所以这个数与第一步之前相等,不是变大。
        assertEquals(before, coveredNodes(touch, List.of(restored)),
                "恢复表达的是「我想再看看」,不是「我确认」—— 它不该把覆盖度推高一格");
        assertEquals(TagOrigin.MANUAL, restored.origin(), "来源是来源不是状态,恢复不改它");
    }

    private static int coveredNodes(Touch touch, List<RecordTag> tags) {
        CoverageService service = new CoverageService();
        return service.summarize(service.compute(TREE, List.of(touch), tags, NOW)).covered();
    }
}
