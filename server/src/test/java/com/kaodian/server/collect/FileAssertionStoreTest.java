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
 * 「我已掌握」落盘的测试 —— 形态照抄 {@code FileRecordTagStoreTest},<b>只验不一样的那几处</b>。
 *
 * <p>原子写、临时文件、并发写锁那几条与行为层是同一份实现,不在这里重验一遍:
 * <b>两份互相抄的断言会一起腐烂</b>。这里管三件事:两个方向的幂等真的落在存储层、
 * 坏文件不静默变空、以及文件里只出现声明过的那两个键。
 */
class FileAssertionStoreTest {

    private static final Instant EARLIER = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-25T12:00:00Z");

    @TempDir
    Path dataDir;

    private FileAssertionStore store() {
        return new FileAssertionStore(dataDir.resolve("assertions.json"));
    }

    @Test
    @DisplayName("🔴 没有种子,而且第一次访问不写盘 —— 没有人「默认已掌握」")
    void anAbsentFileIsAnEmptyTableAndStaysAbsent() {
        FileAssertionStore store = store();

        assertEquals(0, store.count());
        assertEquals(List.of(), store.findAll());
        assertNull(store.find("growth-rate"));
        assertFalse(Files.exists(store.dataFile()), "只是读了一下,不该在用户目录里造出文件");
    }

    @Test
    @DisplayName("写进去、换个实例读出来,两个字段一个不少")
    void anAssertionSurvivesAReopen() {
        store().put(new UserAssertion("growth-rate", EARLIER));

        List<UserAssertion> read = store().findAll();
        assertEquals(1, read.size());
        assertEquals("growth-rate", read.get(0).nodeCode());
        assertEquals(EARLIER, read.get(0).assertedAt());
    }

    /**
     * 🔴 幂等落在存储层,不落在调用方。
     *
     * <p>「我已掌握」在界面上是那种<b>连点会重复发请求</b>的按钮。让调用方「先查再写」
     * 有一个窗口:两个线程各自查到「没有」,然后各自写一行,于是概览里那个
     * 「已声明 N 个」变成 N+1 —— 而它正是 docs/10 §6.4 要求单列出来给用户看的那个数。
     */
    @Test
    @DisplayName("🔴 重复声明同一个考点:不新增一行,而且不刷新 assertedAt")
    void puttingTwiceNeitherDuplicatesNorRefreshes() {
        FileAssertionStore store = store();
        store.put(new UserAssertion("growth-rate", EARLIER));

        UserAssertion again = store.put(new UserAssertion("growth-rate", LATER));

        assertEquals(EARLIER, again.assertedAt(),
                "重复声明刷新了时刻 —— 连点两下不该改写「你在 X 月 X 日说过你会了」这句话");
        assertEquals(1, store.count());
        assertEquals(1, store().count(), "而且磁盘上也只有一行,不是只在内存里去重");
        assertEquals(EARLIER, store().find("growth-rate").assertedAt());
    }

    @Test
    @DisplayName("🔴 取消一个没声明过的考点:返回 false,不抛,也不写盘")
    void removingSomethingNeverAssertedIsNotAnError() {
        FileAssertionStore store = store();

        assertFalse(store.remove("growth-rate"));
        assertFalse(Files.exists(store.dataFile()), "什么都没变就不该写盘");

        store.put(new UserAssertion("growth-rate", EARLIER));
        assertFalse(store.remove("share-calc"), "删的是别人,同样返回 false");
        assertEquals(1, store.count());
    }

    @Test
    @DisplayName("取消要落盘 —— 只改内存的话进程一重启,按掉的考点全回到盲区榜上")
    void removalIsPersisted() {
        FileAssertionStore store = store();
        store.put(new UserAssertion("growth-rate", EARLIER));
        store.put(new UserAssertion("share-calc", LATER));

        assertTrue(store.remove("growth-rate"));

        assertEquals(List.of("share-calc"),
                store().findAll().stream().map(UserAssertion::nodeCode).toList());
    }

    @Test
    @DisplayName("取消之后再声明:是一次新的声明,时刻按新的算")
    void reAssertingAfterRemovalStartsOver() {
        FileAssertionStore store = store();
        store.put(new UserAssertion("growth-rate", EARLIER));
        store.remove("growth-rate");

        assertEquals(LATER, store.put(new UserAssertion("growth-rate", LATER)).assertedAt());
    }

    /**
     * 🔴 坏文件宁可启动不了,也不当成 0 行。
     *
     * <p>与 {@code FileRecordTagStore#parse} 同一条:{@code path("assertions")} 在缺键、
     * 键名写错、根节点是数组时都只是安静地给回一个 MissingNode,而下一次 {@code put}
     * 是<b>全量重写</b>。丢声明不会让覆盖率变化(它本来就不进那个数),但会让用户按掉的考点
     * <b>集体回到盲区榜上</b> —— 而那正是他按这个按钮想让它停下来的事。
     */
    @Test
    @DisplayName("🔴 坏文件宁可启动不了,也不当成 0 行 —— 下一次写入是全量重写")
    void aBrokenFileFailsLoudlyInsteadOfBecomingEmpty() throws IOException {
        for (String broken : new String[]{
                "{}",
                "{\"assertion\":[]}",
                "[]",
                "{\"assertions\":{}}"}) {
            write(broken);
            assertThrows(IllegalStateException.class, () -> store().findAll(), broken);
        }
    }

    @Test
    @DisplayName("行里少了必填字段 / 时刻解析不了 → 同样吵着失败,不静默跳过那一行")
    void aBrokenRowFailsLoudlyToo() throws IOException {
        for (String row : new String[]{
                "{\"assertedAt\":\"2026-08-20T09:00:00Z\"}",
                "{\"nodeCode\":\"growth-rate\"}",
                "{\"nodeCode\":\"growth-rate\",\"assertedAt\":\"上周三\"}"}) {
            write("{\"assertions\":[" + row + "]}");
            assertThrows(IllegalStateException.class, () -> store().findAll(), row);
        }
    }

    @Test
    @DisplayName("🔴 文件里多塞的键读不进来 —— 手工往里写一段题干也到不了任何地方")
    void unknownKeysInTheFileAreIgnored() throws IOException {
        write("""
                {"assertions":[{"nodeCode":"growth-rate","assertedAt":"2026-08-20T09:00:00Z",
                  "note":"某年某省考资料分析材料第一段……","label":"【某机构】增长率速算"}]}
                """);

        assertEquals(1, store().count());

        // 再写一次是全量重写,那两个键必须不会被原样带回去
        FileAssertionStore store = store();
        store.put(new UserAssertion("share-calc", LATER));
        String onDisk = Files.readString(store.dataFile(), StandardCharsets.UTF_8);
        assertFalse(onDisk.contains("某年某省考"), "读进来的东西不该带着文件里那段内容:" + onDisk);
        assertFalse(onDisk.contains("某机构"), onDisk);
    }

    @Test
    @DisplayName("落盘的键就是那两个,一个不多 —— 一条声明的全部内容就是 code 加一个时刻")
    void theFileCarriesExactlyTheDeclaredKeys() throws IOException {
        FileAssertionStore store = store();
        store.put(new UserAssertion("growth-rate", EARLIER));

        String onDisk = Files.readString(store.dataFile(), StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("\"nodeCode\""), onDisk);
        assertTrue(onDisk.contains("\"assertedAt\""), onDisk);
        assertTrue(onDisk.contains("2026-08-20T09:00:00Z"), onDisk);
    }

    private void write(String json) throws IOException {
        Files.writeString(dataDir.resolve("assertions.json"), json, StandardCharsets.UTF_8);
    }
}
