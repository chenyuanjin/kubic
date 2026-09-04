package com.kaodian.server.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>红线 2 的落点</b>({@code B0} §11.2 把它归给本模块,{@code M7-额度与订单} §2.6)。
 *
 * <h2>要证的只有一句</h2>
 *
 * <b>任何交错顺序下,{@code used} 都不会超过 {@code granted},因而 {@code remaining} 取不到负数。</b>
 *
 * <h2>🔴 这条判据先红过一次才算数(CLAUDE.md)</h2>
 *
 * 验证方式:把 {@link FileQuotaStore#consume} 第 ② 步的条件更新改成「先查后写」——
 * 在锁<b>外</b> {@code find} 一次、判一次 {@code hasRemaining},进锁之后不再判。
 * <p>
 * <b>实测(2026-09-04)</b>:这么改之后本用例判红 ——
 * {@code 不多发:成功次数必须恰好等于发放数 ==> expected: <10> but was: <28>}。
 * <b>28 次成功、发放只有 10 次</b>,也就是扣穿了 18 次。改回条件更新后复跑通过(7/7)。
 */
class QuotaLedgerConcurrencyTest {

    private static final long USER = 10001L;
    private static final String PERIOD = "2026-09";

    @TempDir
    Path dir;

    private FileQuotaStore store;

    @BeforeEach
    void setUp() {
        store = new FileQuotaStore(dir.resolve("billing-quota.json"));
    }

    @Test
    void 五十个线程同时扣一次_成功次数恰好等于发放数且余量不为负() throws Exception {
        store.grant(USER, PERIOD, QuotaType.AI_CAPTURE, 10);
        AtomicInteger ok = new AtomicInteger();

        runConcurrently(50, i -> {
            ConsumeResult r = store.consume(USER, PERIOD, QuotaType.AI_CAPTURE, callWithKey("k-" + i));
            if (r instanceof ConsumeResult.Consumed) {
                ok.incrementAndGet();
            }
        });

        assertEquals(10, ok.get(), "不多发:成功次数必须恰好等于发放数");

        QuotaPeriod p = store.find(USER, PERIOD, QuotaType.AI_CAPTURE).orElseThrow();
        assertEquals(10, p.used(), "不扣穿:used 必须恰好等于发放数");
        assertTrue(p.granted() - p.used() >= 0, "🔴 余量不为负");
        assertEquals(0, p.remaining(), "余量恰好用完");

        // 账单与扣减一一对应 —— 不产生第 granted+1 行流水。
        assertEquals(10, store.countCallsByUser(USER), "流水行数必须等于扣减次数");
    }

    @Test
    void 同一幂等键重放不重复扣() {
        store.grant(USER, PERIOD, QuotaType.AI_ASK, 5);

        ConsumeResult first = store.consume(USER, PERIOD, QuotaType.AI_ASK, callWithKey("same"));
        assertInstanceOf(ConsumeResult.Consumed.class, first);

        ConsumeResult second = store.consume(USER, PERIOD, QuotaType.AI_ASK, callWithKey("same"));
        assertInstanceOf(ConsumeResult.Replayed.class, second,
                "🔴 重放不是一次成功 —— 把它当成成功扣一次,一次断网重连就把额度扣光了");

        assertEquals(1, store.find(USER, PERIOD, QuotaType.AI_ASK).orElseThrow().used());
        assertEquals(1, store.countCallsByUser(USER), "重放不产生第二行流水");
    }

    /**
     * 🔴 唯一键是三列,不是单列 {@code idempotencyKey}(§2.7)。
     *
     * <p>同一条记录先走转写、再走打标建议时两次带着<b>同一个 {@code clientToken}</b>
     * ({@code 接口契约} §6.7.2 约束 2 允许复用)。单列唯一会把第二次当成重放 ——
     * 不扣额度,而且返回第一次的转写结果。
     */
    @Test
    void 同一个键在两个端点上是两次扣减() {
        store.grant(USER, PERIOD, QuotaType.AI_CAPTURE, 5);

        store.consume(USER, PERIOD, QuotaType.AI_CAPTURE,
                call("/api/v1/records/7/audio", "shared-token", CallStatus.SUCCESS));
        ConsumeResult second = store.consume(USER, PERIOD, QuotaType.AI_CAPTURE,
                call("/api/v1/records/7/tags/suggest", "shared-token", CallStatus.SUCCESS));

        assertInstanceOf(ConsumeResult.Consumed.class, second,
                "两个 endpoint 是两行,各扣一次 —— 单列唯一会让这里变成 Replayed");
        assertEquals(2, store.find(USER, PERIOD, QuotaType.AI_CAPTURE).orElseThrow().used());
    }

    /** 耗尽时整体回滚:不扣、<b>不留流水</b>(§2.3 步 ②)。 */
    @Test
    void 耗尽时不扣也不留流水() {
        store.grant(USER, PERIOD, QuotaType.AI_ASK, 1);
        store.consume(USER, PERIOD, QuotaType.AI_ASK, callWithKey("first"));

        ConsumeResult exhausted = store.consume(USER, PERIOD, QuotaType.AI_ASK, callWithKey("second"));

        assertInstanceOf(ConsumeResult.Exhausted.class, exhausted);
        assertEquals(1, store.find(USER, PERIOD, QuotaType.AI_ASK).orElseThrow().used());
        assertEquals(1, store.countCallsByUser(USER), "🔴 不产生第 granted+1 行流水");
    }

    /** 失败调用只留流水、不动 {@code used};那一行允许被后来的一次成功覆盖(§2.3 步 ①)。 */
    @Test
    void 失败不扣而且允许同键重试() {
        store.grant(USER, PERIOD, QuotaType.AI_ASK, 3);

        store.recordFailure(call("/api/v1/ai/ask", "retry-me", CallStatus.FAILED));
        assertEquals(0, store.find(USER, PERIOD, QuotaType.AI_ASK).orElseThrow().used(),
                "🔴 失败根本不扣 —— 所以账本里没有『退还』这个动作");

        ConsumeResult retry = store.consume(USER, PERIOD, QuotaType.AI_ASK,
                call("/api/v1/ai/ask", "retry-me", CallStatus.SUCCESS));
        assertInstanceOf(ConsumeResult.Consumed.class, retry, "上次失败 → 允许重试(`接口契约` §1.5)");
        assertEquals(1, store.find(USER, PERIOD, QuotaType.AI_ASK).orElseThrow().used());
        assertEquals(1, store.countCallsByUser(USER), "覆盖那一行,不是再加一行");
    }

    /** {@code grant} 只升不降 —— §2.6 那条不变式论证的前提。 */
    @Test
    void 发放只升不降而且不动已用() {
        store.grant(USER, PERIOD, QuotaType.AI_CAPTURE, 30);
        store.consume(USER, PERIOD, QuotaType.AI_CAPTURE, callWithKey("one"));

        store.grant(USER, PERIOD, QuotaType.AI_CAPTURE, 300);
        QuotaPeriod up = store.find(USER, PERIOD, QuotaType.AI_CAPTURE).orElseThrow();
        assertEquals(300, up.granted());
        assertEquals(1, up.used(), "抬档不动 used");

        store.grant(USER, PERIOD, QuotaType.AI_CAPTURE, 30);
        QuotaPeriod down = store.find(USER, PERIOD, QuotaType.AI_CAPTURE).orElseThrow();
        assertEquals(300, down.granted(), "🔴 只升不降:下调 granted 是退款写法 A 的动作,而 A/B/C 未选");
    }

    /** 落盘之后重新载入,数一格不差 —— 提交点是那一次落盘。 */
    @Test
    void 重新载入之后账目一格不差() {
        store.grant(USER, PERIOD, QuotaType.AI_CAPTURE, 5);
        store.consume(USER, PERIOD, QuotaType.AI_CAPTURE, callWithKey("a"));
        store.consume(USER, PERIOD, QuotaType.AI_CAPTURE, callWithKey("b"));

        FileQuotaStore reopened = new FileQuotaStore(dir.resolve("billing-quota.json"));
        QuotaPeriod p = reopened.find(USER, PERIOD, QuotaType.AI_CAPTURE).orElseThrow();
        assertEquals(5, p.granted());
        assertEquals(2, p.used());
        assertEquals(2, reopened.countCallsByUser(USER));
    }

    // ——————————————————— 夹具 ———————————————————

    private static AiCallLog callWithKey(String key) {
        return call("/api/v1/ai/ask", key, CallStatus.SUCCESS);
    }

    private static AiCallLog call(String endpoint, String key, CallStatus status) {
        QuotaType type = endpoint.contains("/ai/ask") ? QuotaType.AI_ASK : QuotaType.AI_CAPTURE;
        return new AiCallLog(0L, USER, type, endpoint, key, "openrouter", "minimax/minimax-m3:free",
                status, 120, 3400L, Instant.parse("2026-09-04T00:00:00Z"));
    }

    /**
     * {@code n} 个线程<b>同时</b>起跑。
     *
     * <p>闸门(两个 latch)是必要的:不放闸的话线程会一个接一个地起来,
     * 于是「并发」在测试里从来没有真的发生过 —— 而这条判据的全部意义就是并发。
     */
    private static void runConcurrently(int n, IntConsumer body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 32))) {
            for (int i = 0; i < n; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        body.accept(index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发用例超时");
        }
    }
}
