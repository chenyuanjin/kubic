package com.kaodian.server.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code B0-2} §3.4 那道守卫 —— <b>存量 {@code u_} 账号数据必须拒绝启动</b>,
 * 而且打出来的必须是那条<b>带出路</b>的消息,不是 Jackson 的解析错误。
 *
 * <h2>为什么这个测试比守卫本身还重要</h2>
 *
 * 守卫写对了但消息被包掉,效果等于没写:撞上它的人看到的是一句
 * 「账号记录的 id 不是 int64」,而那句话不告诉任何人下一步该做什么 ——
 * 一个没有出路的守卫会被人在半夜关掉,然后它就永远关着了。
 */
class LegacyUserIdGuardTest {

    @TempDir
    Path dir;

    /** B0-2 之前那种文件:id 是 {@code "u_" + UUID}。 */
    private Path legacyFile() throws Exception {
        Path f = dir.resolve("auth-accounts.json");
        Files.writeString(f, """
                {
                  "users": [
                    {"id": "u_3f2a9c1d4e5b6a7c8d9e0f1a2b3c4d5e",
                     "status": "ACTIVE", "createdAt": "2026-08-01T00:00:00Z"},
                    {"id": "u_aaaabbbbccccddddeeeeffff00001111",
                     "status": "ACTIVE", "createdAt": "2026-08-02T00:00:00Z"}
                  ],
                  "identities": [],
                  "phoneSecrets": [],
                  "mergeLogs": []
                }
                """);
        return f;
    }

    @Test
    @DisplayName("🔴 存量 u_ 账号 → 拒绝启动,而且给的是那条有出路的消息")
    void legacyDataRefusesStartup() throws Exception {
        FileAccountStore store = new FileAccountStore(legacyFile());

        var e = assertThrows(FileAccountStore.LegacyUserIdException.class,
                () -> store.findById(10001L));

        String msg = e.getMessage();
        System.out.println("=== 拒绝启动时打印的消息 ===\n" + msg);

        assertTrue(msg.contains("检测到 B0-2 之前的存量账号数据(id 以 u_ 开头),共 2 条。"), msg);
        assertTrue(msg.contains("本机开发数据:删掉 ~/.kaodian/auth-*.json 重新注册即可。"), msg);
        assertTrue(msg.contains("这台机器上的数据不能删:停手上报,不要绕过这一条。"), msg);

        // 🔴 这一条是本测试的全部要点:消息不能被 AuthJsonFile 那句「文件内容不合法」包掉,
        // 也不能是 Jackson / requiredLong 抛出的「不是 int64」——
        // 用户看到的必须是上面那条带出路的话。
        assertFalse(msg.contains("内容不合法"), msg);
        assertFalse(msg.contains("不是 int64"), msg);
    }

    @Test
    @DisplayName("🔴 起始值 10001 —— 0 在结构上不是一个合法 userId")
    void firstIdIs10001() {
        FileAccountStore store = new FileAccountStore(dir.resolve("fresh.json"));
        assertEquals(10001L, store.nextUserId());
        assertEquals(10002L, store.nextUserId());
        assertThrows(IllegalArgumentException.class, () -> AppUser.fresh(0L, Instant.now()),
                "0 正是 AgentController 那个硬编码哨兵,它不能是一个账号");
    }

    @Test
    @DisplayName("🔴 发号与账号数据同一次原子落盘 —— 不会出现「发了号但账号没写进去」")
    void cursorPersistsWithTheAccount() {
        Path f = dir.resolve("seq.json");
        FileAccountStore store = new FileAccountStore(f);
        Instant now = Instant.parse("2026-09-04T00:00:00Z");

        long id = store.nextUserId();
        store.create(AppUser.fresh(id, now),
                new UserIdentity(id, IdentityType.WX_UNION, "union_a", now), null);

        // 重开一个 store = 一次重启:游标必须已经在盘上,不能把同一个号再发一次。
        assertEquals(id + 1, new FileAccountStore(f).nextUserId());
    }
}
