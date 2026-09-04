package com.kaodian.server.tagging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待补队列 —— {@code M2-打标管线与模型接入} §五。
 *
 * <h2>队列不是一张新表,所以这里也没有第二个存储要测</h2>
 *
 * 全部断言都落在<b>同一张 {@code TagAttempt} 上的一个谓词</b>。
 * 「出队了但 {@code outcome} 没改」这种分叉在这个形态里写不出来 —— 那正是不建第二张表的理由。
 */
class TagAttemptStoreTest {

    private static final long USER = 10001L;
    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    @DisplayName("🔴 只有 UNAVAILABLE 能排下一次 —— 别的结局带着 nextRetryAt 落库当场抛")
    void onlyUnavailableCanBeQueued() {
        for (TagAttempt.Outcome outcome : TagAttempt.Outcome.values()) {
            if (outcome.retryable()) {
                continue;
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new TagAttempt("t-1", USER, outcome, 1, NOW.plusSeconds(30), NOW),
                    outcome + " 排进了队列 —— 那条记录会被反复捞起来重认,而它早就有结论了");
        }
        assertTrue(TagAttempt.Outcome.UNAVAILABLE.retryable());
        assertFalse(TagAttempt.Outcome.QUOTA_EXHAUSTED.retryable(),
                "拿不到许可是用户侧状态,不是链路故障 —— 重试只会反复撞同一道闸");
    }

    @Test
    @DisplayName("退避 30s / 5min / 30min,第三次之后停 —— 停在 TS-06,两个人工出口都还在")
    void backoffThenStop() {
        TagAttempt first = TagAttempt.unavailable("t-1", USER, null, NOW);
        assertEquals(1, first.attempts());
        assertEquals(NOW.plus(Duration.ofSeconds(30)), first.nextRetryAt());

        TagAttempt second = TagAttempt.unavailable("t-1", USER, first, NOW);
        assertEquals(2, second.attempts());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), second.nextRetryAt());

        TagAttempt third = TagAttempt.unavailable("t-1", USER, second, NOW);
        assertEquals(TagAttempt.MAX_ATTEMPTS, third.attempts());
        assertNull(third.nextRetryAt(), "到上限就停止自动重试,不是无限退避下去");
        assertFalse(third.queued());
    }

    @Test
    @DisplayName("dueForRetry 只捞到点的那些,按 nextRetryAt 升序")
    void dueForRetryIsTimeOrdered(@TempDir Path dir) {
        TagAttemptStore store = new FileTagAttemptStore(dir.resolve("a.json"));
        store.put(TagAttempt.unavailable("t-2", USER, null, NOW.minusSeconds(60)));   // 到点
        store.put(TagAttempt.unavailable("t-1", USER, null, NOW.minusSeconds(120)));  // 更早到点
        store.put(TagAttempt.unavailable("t-3", USER, null, NOW));                    // 还没到

        List<TagAttempt> due = store.dueForRetry(NOW, 10);
        assertEquals(List.of("t-1", "t-2"), due.stream().map(TagAttempt::recordId).toList());
        assertEquals(3, store.pendingCount(USER), "还没到点的也在队列里,只是这次没捞它");
    }

    @Test
    @DisplayName("🔴 队列满时丢最旧,而且一条记录都没动 —— 丢的是「稍后再认」,不是用户记的那一笔")
    void queueFullDropsOldestAndNeverTouchesRecords(@TempDir Path dir) {
        Path file = dir.resolve("a.json");
        TagAttemptStore store = new FileTagAttemptStore(file);
        for (int i = 0; i <= TagAttempt.QUEUE_CAPACITY; i++) {   // 201 条
            store.put(TagAttempt.unavailable("t-" + i, USER, null, NOW.plusSeconds(i)));
        }

        assertEquals(TagAttempt.QUEUE_CAPACITY, store.pendingCount(USER));
        assertNull(store.find(USER, "t-0"), "最旧那条被丢掉了");
        assertNotNull(store.find(USER, "t-" + TagAttempt.QUEUE_CAPACITY), "最新那条留着");
        // 🔴 这个类碰不到行为层,结构上就丢不掉一条记录(I-1)。判据落在文件上:
        //    塞满 201 条之后,这个目录里除了队列自己那个文件什么都没多、什么都没少。
        try (var entries = Files.list(dir)) {
            assertEquals(List.of(file.getFileName().toString()),
                    entries.map(f -> f.getFileName().toString()).sorted().toList(),
                    "队列写盘时碰了别的文件 —— 而记录落地是 I-1,它一个字节都不该被这里影响");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("落盘再读回来,六个字段一个不差;删记录连队列里那一行一起走")
    void roundTripsAndDeletes(@TempDir Path dir) {
        Path file = dir.resolve("a.json");
        new FileTagAttemptStore(file).put(TagAttempt.unavailable("t-1", USER, null, NOW));

        TagAttempt read = new FileTagAttemptStore(file).find(USER, "t-1");
        assertEquals("t-1", read.recordId());
        assertEquals(USER, read.userId());
        assertEquals(TagAttempt.Outcome.UNAVAILABLE, read.outcome());
        assertEquals(1, read.attempts());
        assertEquals(NOW.plus(Duration.ofSeconds(30)), read.nextRetryAt());
        assertEquals(NOW, read.updatedAt());

        TagAttemptStore store = new FileTagAttemptStore(file);
        assertEquals(1, store.deleteByRecord(USER, "t-1"));
        assertNull(store.find(USER, "t-1"));
    }

    @Test
    @DisplayName("🔴 认不出来的文件吵着失败,绝不当成 0 行 —— 下一次写入是全量重写")
    void aBrokenFileRefusesToLookEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.json");
        Files.writeString(file, "{\"attempt\":[]}");   // 键名写错了一个字母
        assertThrows(IllegalStateException.class, () -> new FileTagAttemptStore(file).find(USER, "t-1"),
                "当成 0 行的话,下一次写入会把磁盘上真实排着的队整个盖掉,"
                        + "而界面上那些记录仍然写着「稍后再认」");
    }

    @Test
    @DisplayName("🔴 表里没有一个字段能装下内容 —— 六个分量全是标识/枚举名/计数/时刻")
    void noFieldCanHoldContent() {
        assertEquals(6, TagAttempt.class.getRecordComponents().length,
                "加一个 lastVendorResponse,R-07 的类型层保护当场绕过");
        for (var component : TagAttempt.class.getRecordComponents()) {
            assertFalse(List.of("text", "label", "name", "stem", "response", "raw")
                            .contains(component.getName()),
                    "这张表存的是「走到了哪一步」,不是「模型说了什么」:" + component.getName());
        }
    }
}
