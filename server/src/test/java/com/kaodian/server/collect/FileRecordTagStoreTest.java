package com.kaodian.server.collect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签层落盘的测试 —— 形态照抄 {@code FileTouchStoreTest},<b>只验不一样的那几处</b>。
 *
 * <p>原子写、临时文件、并发写锁那几条与行为层是同一份实现,不在这里重验一遍;
 * 重验的价值不高,而<b>两份互相抄的断言会一起腐烂</b>。这里管三件事:
 * 没有种子、坏文件不静默变空、以及 {@code put} 那道 origin 拒绝真的落在存储层。
 */
class FileRecordTagStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    @TempDir
    Path dataDir;

    private FileRecordTagStore store() {
        return new FileRecordTagStore(dataDir.resolve("record-tags.json"));
    }

    private static RecordTag auto(String id, String recordId, String nodeCode) {
        return new RecordTag(id, recordId, nodeCode, 0.91, TagOrigin.AUTO, null, false);
    }

    @Test
    @DisplayName("🔴 没有种子,而且第一次访问不写盘 —— 44% 由主标签推出来,一行都不必存")
    void anAbsentFileIsAnEmptyTableAndStaysAbsent() {
        // 造一个 record-tags-demo.json 去镜像那 8 条种子记录是很自然的做法,
        // 但那会多出一份【会和行为层对不上】的状态:种子记录改了 nodeCode,标签文件不会跟着改。
        FileRecordTagStore store = store();

        assertEquals(0, store.count());
        assertEquals(List.of(), store.findAll());
        assertFalse(Files.exists(store.dataFile()), "只是读了一下,不该在用户目录里造出文件");
    }

    @Test
    @DisplayName("写进去、换个实例读出来,七个字段一个不少")
    void aTagSurvivesAReopen() {
        RecordTag written = new RecordTag("tag-1", "t-1", "growth-rate",
                0.913, TagOrigin.AUTO, NOW, true);
        store().put(written);

        List<RecordTag> read = store().findAll();

        assertEquals(1, read.size());
        assertEquals(written, read.get(0), "落盘再读回来必须逐字段相等 —— 差一个字段就是一次静默的数据损失");
    }

    @Test
    @DisplayName("confirmedAt 为空的标签落盘再读回来仍然是空 —— 不能被读成「某个时刻」")
    void anUnconfirmedTagStaysUnconfirmed() {
        // 读侧若把缺键读成 Instant.EPOCH 或「现在」,一批没人确认过的自动标签会集体变成已确认,
        // 而准确率口径(标对的/标了的)的分子会一夜之间等于分母。
        store().put(auto("tag-1", "t-1", "growth-rate"));
        assertNull(store().findAll().get(0).confirmedAt());
    }

    @Test
    @DisplayName("🔴 origin 那道拒绝落在存储层,重开一个实例照样拒 —— 它不是内存里的一个标志")
    void theOriginGuardIsPersistedNotRemembered() {
        store().put(auto("tag-1", "t-1", "growth-rate"));

        FileRecordTagStore reopened = store();
        RecordTag flipped = new RecordTag("tag-1", "t-1", "growth-rate",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false);

        assertThrows(IllegalArgumentException.class, () -> reopened.put(flipped));
        assertEquals(TagOrigin.AUTO, store().find("tag-1").origin(), "磁盘上那一行也不许被改");
    }

    @Test
    @DisplayName("put 同一个 id 是更新不是追加;confirm 之后仍然只有一行")
    void puttingTheSameIdTwiceUpdatesInPlace() {
        FileRecordTagStore store = store();
        RecordTag tag = auto("tag-1", "t-1", "growth-rate");
        store.put(tag);
        store.put(tag.confirm(NOW));

        assertEquals(1, store.count(), "确认是改一行,不是加一行");
        assertEquals(NOW, store.find("tag-1").confirmedAt());
        assertEquals(TagOrigin.AUTO, store.find("tag-1").origin());
    }

    @Test
    @DisplayName("级联删只带走这条记录名下的行,别人的一行不动")
    void deleteByRecordIsScoped() {
        FileRecordTagStore store = store();
        store.put(auto("tag-1", "t-1", "growth-rate"));
        store.put(auto("tag-2", "t-1", "share-calc"));
        store.put(auto("tag-3", "t-2", "share-calc"));

        assertEquals(2, store.deleteByRecord("t-1"));
        assertEquals(List.of("tag-3"), store().findAll().stream().map(RecordTag::id).toList(),
                "而且删除要落盘 —— 只改内存的话进程一重启丢弃过的标签就全回来了");
        assertEquals(0, store.deleteByRecord("t-1"), "删一次不存在的返回 0,不抛");
    }

    @Test
    @DisplayName("🔴 坏文件宁可启动不了,也不当成 0 行 —— 下一次写入是全量重写,会把真实数据盖掉")
    void aBrokenFileFailsLoudlyInsteadOfBecomingEmpty() throws IOException {
        // 与 FileTouchStore#parse 同一条教训:path("tags") 在缺键、键名写错、根节点是数组时
        // 都只是安静地给回一个 MissingNode。丢标签比丢记录轻,但不是没有后果:
        // 用户丢弃过的错标会集体复活,重新计进覆盖度。
        for (String broken : new String[]{
                "{}",
                "{\"tag\":[]}",
                "[]",
                "{\"tags\":{}}"}) {
            write(broken);
            assertThrows(IllegalStateException.class, () -> store().findAll(), broken);
        }
    }

    @Test
    @DisplayName("行里少了必填字段 / origin 认不出来 → 同样吵着失败,不静默跳过那一行")
    void aBrokenRowFailsLoudlyToo() throws IOException {
        for (String row : new String[]{
                "{\"recordId\":\"t-1\",\"nodeCode\":\"growth-rate\",\"origin\":\"auto\"}",
                "{\"id\":\"tag-1\",\"nodeCode\":\"growth-rate\",\"origin\":\"auto\"}",
                "{\"id\":\"tag-1\",\"recordId\":\"t-1\",\"origin\":\"auto\"}",
                "{\"id\":\"tag-1\",\"recordId\":\"t-1\",\"nodeCode\":\"growth-rate\"}",
                "{\"id\":\"tag-1\",\"recordId\":\"t-1\",\"nodeCode\":\"growth-rate\",\"origin\":\"MANUAL\"}"}) {
            write("{\"tags\":[" + row + "]}");
            assertThrows(IllegalStateException.class, () -> store().findAll(), row);
        }
    }

    @Test
    @DisplayName("🔴 文件里多塞的键读不进来 —— 手工往里写一段题干也到不了任何地方")
    void unknownKeysInTheFileAreIgnored() throws IOException {
        // 与 FileTouchStore 同一条:读写都逐字段列举。即便有人手工往文件里塞了内容,
        // 它既进不了内存,更不会因为 RecordTag 将来多了个字段就悄悄流回文件。
        write("""
                {"tags":[{"id":"tag-1","recordId":"t-1","nodeCode":"growth-rate",
                  "origin":"auto","confidence":0.91,"discarded":false,
                  "stem":"某年某省考资料分析材料第一段……","label":"【某机构】增长率速算"}]}
                """);

        assertEquals(1, store().count());

        // 再写一次是全量重写,那两个键必须不会被原样带回去
        FileRecordTagStore store = store();
        store.put(auto("tag-2", "t-2", "share-calc"));
        String onDisk = Files.readString(store.dataFile(), StandardCharsets.UTF_8);
        assertFalse(onDisk.contains("某年某省考"), "读进来的东西不该带着文件里那段内容:" + onDisk);
        assertFalse(onDisk.contains("某机构"), onDisk);
    }

    @Test
    @DisplayName("落盘的键就是那七个,一个不多 —— 文件里能出现哪些键由代码显式列出")
    void theFileCarriesExactlyTheDeclaredKeys() throws IOException {
        FileRecordTagStore store = store();
        store.put(new RecordTag("tag-1", "t-1", "growth-rate", 0.91, TagOrigin.AUTO, NOW, true));

        String onDisk = Files.readString(store.dataFile(), StandardCharsets.UTF_8);
        for (String key : new String[]{"id", "recordId", "nodeCode", "confidence",
                "origin", "confirmedAt", "discarded"}) {
            assertTrue(onDisk.contains("\"" + key + "\""), "少了键 " + key + ":" + onDisk);
        }
        assertTrue(onDisk.contains("\"auto\""), "origin 按契约写小写(docs/10 §5.2)");
    }

    private void write(String json) throws IOException {
        Files.writeString(dataDir.resolve("record-tags.json"), json, StandardCharsets.UTF_8);
    }
}
