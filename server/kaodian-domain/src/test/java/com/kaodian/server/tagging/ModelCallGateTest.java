package com.kaodian.server.tagging;

import com.kaodian.server.collect.InMemoryRecordTagStore;
import com.kaodian.server.collect.RecordTagStore;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.recognize.RecognitionResult;
import com.kaodian.server.recognize.RecognitionUnavailableException;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.SyllabusLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 许可闸的四条 —— {@code M2-打标管线与模型接入} §2.4 那张表逐行。
 *
 * <h2>为什么这些断言值钱</h2>
 *
 * 「扣在调用前还是调用后」「失败退不退」这两件事没有任何一处会在写错时报错:
 * 扣在调用后仍然跑得通,只是并发下会超发;失败不退也跑得通,只是用户为一次没成功的识别
 * 付四次钱。<b>两种错法都不抛异常、不留日志、不改变任何测试的绿色</b> ——
 * 所以它们只能靠这一组计数断言钉住。
 */
class ModelCallGateTest {

    private static final long USER = 10001L;
    private static final byte[] MATERIAL = "一张图".getBytes();

    /** 一道会计数的闸 —— 净扣减 = {@code acquired - released}。 */
    private static final class CountingGate implements ModelCallGate {
        private final boolean open;
        int acquired;
        int released;

        CountingGate(boolean open) {
            this.open = open;
        }

        @Override
        public boolean acquire() {
            if (!open) {
                return false;
            }
            acquired++;
            return true;
        }

        @Override
        public void release() {
            released++;
        }

        int net() {
            return acquired - released;
        }
    }

    /** 一个会数「厂商被打了几次」的桩。 */
    private static final class CountingTagger implements VisionTagger {
        private final RecognitionResult answer;
        private final boolean blowUp;
        int calls;

        CountingTagger(RecognitionResult answer, boolean blowUp) {
            this.answer = answer;
            this.blowUp = blowUp;
        }

        @Override
        public RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates) {
            calls++;
            if (blowUp) {
                throw new RecognitionUnavailableException("链路不通");
            }
            return answer;
        }
    }

    @Test
    @DisplayName("🔴 召回为空:许可账本一次都不动 —— 压根没打算发起外部调用(I-4)")
    void recallEmptyDoesNotTouchQuota() {
        CountingGate gate = new CountingGate(true);
        CountingTagger tagger = new CountingTagger(RecognitionResult.of("interval-growth", 0.9), false);

        Suggestions.suggest(tagger, gate, "没有一个字能切出关键词的来源");

        assertEquals(0, gate.acquired, "召回为空还去问一次许可,等于让「调了也只能瞎猜」那条也要付钱");
        assertEquals(0, tagger.calls, "召回为空绝不调模型");
    }

    @Test
    @DisplayName("🔴 拿不到许可:记录照样成立,这条不打标,而且不进自动重试队列")
    void refusedPermitNeverEntersTheQueue() {
        CountingGate gate = new CountingGate(false);
        CountingTagger tagger = new CountingTagger(RecognitionResult.of("interval-growth", 0.9), false);

        Suggestions run = Suggestions.suggest(tagger, gate, Suggestions.RECALLING_SOURCE);

        assertEquals(TagAttempt.Outcome.QUOTA_EXHAUSTED, run.suggestion.outcome());
        assertEquals(0, tagger.calls, "拿不到许可就不该有任何外部调用");
        assertNull(run.attempt().nextRetryAt(),
                "🔴 拿不到许可不是链路故障,是用户侧状态 —— 重试只会反复撞同一道闸");
        assertEquals(0, run.store.pendingCount(USER));
    }

    @Test
    @DisplayName("🔴 压根没看成 → 退回那一次;而「看了但没认出来」→ 扣了不退")
    void unavailableRefundsButNoMatchDoesNot() {
        CountingGate refunded = new CountingGate(true);
        Suggestions blewUp = Suggestions.suggest(
                new CountingTagger(null, true), refunded, Suggestions.RECALLING_SOURCE);
        assertEquals(TagAttempt.Outcome.UNAVAILABLE, blewUp.suggestion.outcome());
        assertEquals(0, refunded.net(),
                "不退的话这条会重试三次,用户为一次没成功的识别付四次钱");

        CountingGate charged = new CountingGate(true);
        Suggestions looked = Suggestions.suggest(
                new CountingTagger(RecognitionResult.noMatch(0.42), false),
                charged, Suggestions.RECALLING_SOURCE);
        assertEquals(TagAttempt.Outcome.NO_MATCH, looked.suggestion.outcome());
        assertEquals(1, charged.net(),
                "🔴 模型真的看了,外部账单已经产生 —— 退它等于让「宁可丢弃率高」变成免费的");
    }

    @Test
    @DisplayName("🔴 恒等式:净扣减 == 未退回的外部调用次数,逐次对得上")
    void chargeEqualsUnrefundedCalls() {
        CountingGate gate = new CountingGate(true);
        int net = 0;
        int unrefundedCalls = 0;

        // ① 召回为空:不调、不扣
        Suggestions.suggest(new CountingTagger(RecognitionResult.noMatch(), false), gate, "无线索");
        assertEquals(net, gate.net());

        // ② 链路不通:调了一次,退了 —— 不计入「未退回」
        CountingTagger blowUp = new CountingTagger(null, true);
        Suggestions.suggest(blowUp, gate, Suggestions.RECALLING_SOURCE);
        assertEquals(1, blowUp.calls);
        assertEquals(net, gate.net(), "退回之后净扣减不变");

        // ③ 看了没认出来:调了一次,不退
        CountingTagger sawNothing = new CountingTagger(RecognitionResult.noMatch(0.3), false);
        Suggestions.suggest(sawNothing, gate, Suggestions.RECALLING_SOURCE);
        unrefundedCalls += sawNothing.calls;
        net += 1;
        assertEquals(net, gate.net());

        // ④ 命中:调了一次,不退
        CountingTagger hit = new CountingTagger(RecognitionResult.of("interval-growth", 0.9), false);
        Suggestions.suggest(hit, gate, Suggestions.RECALLING_SOURCE);
        unrefundedCalls += hit.calls;
        net += 1;

        assertEquals(net, gate.net());
        assertEquals(unrefundedCalls, gate.net(),
                "🔴 恒等式:净扣减 == 未退回的外部调用次数。写成「== 打到厂商的次数」会红 ——"
                        + "失败的那几次都退了");
    }

    @Test
    @DisplayName("🔴 管线的出口是「待确认」,不是「已确认」—— 任何分支落下的标签 confirmedAt 恒为 null")
    void pipelineNeverProducesConfirmed() {
        Suggestions run = Suggestions.suggest(
                new CountingTagger(RecognitionResult.of("interval-growth", 0.9), false),
                new CountingGate(true), Suggestions.RECALLING_SOURCE);

        assertEquals(TagAttempt.Outcome.SUGGESTED, run.suggestion.outcome());
        assertNull(run.suggestion.tag().confirmedAt(),
                "顺手填上「等于现在」会让准确率口径的分子恒等于分母,"
                        + "而且它是一条系统触发、终点计覆盖度的转移");
        assertTrue(run.suggestion.candidateCount() > 0, "命中时候选集全集也要带回去");
    }

    // ———————————————————— 装置 ————————————————————

    /** 跑一次管线,把闸、桩、库都拎回来。 */
    private record Suggestions(TaggingService.Suggestion suggestion, InMemoryTagAttemptStore store,
                               String recordId) {

        static final String RECALLING_SOURCE = "自己刷题 · 增长率专项";

        static Suggestions suggest(VisionTagger tagger, ModelCallGate gate, String sourceName) {
            InMemoryTagAttemptStore store = new InMemoryTagAttemptStore();
            RecordTagStore tags = new InMemoryRecordTagStore();
            Touch touch = new Touch("t-1", USER, "growth-rate", sourceName, TouchKind.PHOTO,
                    Instant.parse("2026-09-01T10:00:00Z"), null, null);
            TouchStore touches = new SingleTouchStore(touch);

            TaggingService service = new TaggingService(touches, tags, store,
                    SyllabusLoader.loadDefault(), new CandidateRecall(), tagger,
                    Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC));
            return new Suggestions(service.suggest(touch, MATERIAL, "image/jpeg", gate), store, touch.id());
        }

        TagAttempt attempt() {
            return store.find(USER, recordId);
        }
    }

    /** 只装得下一条记录的行为层 —— 这些用例不关心行为层。 */
    private record SingleTouchStore(Touch only) implements TouchStore {

        @Override
        public List<Touch> findAll(long userId) {
            return userId == only.userId() ? List.of(only) : List.of();
        }

        @Override
        public List<Touch> findAllAcrossUsers() {
            return List.of(only);
        }

        @Override
        public Touch findByClientToken(long userId, String clientToken) {
            return null;
        }

        @Override
        public Touch append(Touch touch) {
            return touch;
        }

        @Override
        public Touch delete(long userId, String id) {
            return null;
        }

        @Override
        public int count(long userId) {
            return findAll(userId).size();
        }

        @Override
        public int countByNodeAcrossUsers(String nodeCode) {
            return only.nodeCode().equals(nodeCode) ? 1 : 0;
        }

        @Override
        public int reassign(String fromNodeCode, String toNodeCode) {
            return 0;
        }
    }
}
