package com.kaodian.server.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code B0-2} 账号 id 统一为 {@code long} —— 发号器、JSON 形态、以及那道<b>拒绝启动</b>的守卫
 * ({@code M5-账号与登录通道} §一)。
 */
class UserIdTest {

    @TempDir
    Path dir;

    private FileAccountStore store() {
        return new FileAccountStore(dir.resolve("accounts.json"));
    }

    @Test
    @DisplayName("发号器从 10001 起,而且顺着已有的最大值往上走")
    void issuesFrom10001() {
        FileAccountStore s = store();
        assertEquals(10001L, s.nextUserId());

        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        s.create(AppUser.fresh(10001L, now),
                new UserIdentity(10001L, IdentityType.WX_OPEN, "o_a", now), null);

        assertEquals(10002L, s.nextUserId());
        assertEquals(1, s.countCreated());
    }

    @Test
    @DisplayName("🔴 同一个号不发第二次 —— 「读最大值 + 1」在这里是不够的")
    void neverIssuesTheSameIdTwice() {
        FileAccountStore s = store();

        // 发号与建号是两次调用,中间锁是放开的。只读最大值的话这两次都会回 10001,
        // 第二个建号撞在「账号 id 已存在」上 —— 那是 IllegalStateException,
        // 不在 AccountService#createOrJoin 的捕获范围里,一路逃成 500。
        long first = s.nextUserId();
        long second = s.nextUserId();

        assertNotEquals(first, second, "🔴 发出去就要推高水位,否则登录页连点两次就是一个 500");
        assertEquals(first + 1, second);
    }

    @Test
    @DisplayName("🔴 读到 u_ 形态的老 id → 拒绝启动,而且消息里把出路写全")
    void refusesToStartOnLegacyUserId() throws IOException {
        Path file = dir.resolve("accounts.json");
        Files.writeString(file, """
                {
                  "users": [
                    {"id": "u_3f2a9c", "status": "ACTIVE", "createdAt": "2026-08-01T00:00:00Z"}
                  ],
                  "identities": [],
                  "phoneSecrets": [],
                  "mergeLogs": []
                }
                """, StandardCharsets.UTF_8);

        var e = assertThrows(AuthJsonFile.LegacyUserIdException.class,
                () -> new FileAccountStore(file).countCreated());

        // 🔴 只说「指纹不匹配」式的异常会让人直接去找怎么把它关掉。
        //    这一条必须自己带着出路,而且要说清没有迁移器这件事。
        assertTrue(e.getMessage().contains("u_3f2a9c"), "要指出是哪一条数据");
        assertTrue(e.getMessage().contains("没有迁移器"), "要说明为什么不迁,不然下一个人会去写一个");
        assertTrue(e.getMessage().contains("10001"), "要给出新形态长什么样");
    }

    @Test
    @DisplayName("🔴 落盘时 id 是字符串,不是 JSON number —— int64 进 number 在 JS 那侧会丢精度")
    void idIsWrittenAsAJsonString() throws IOException {
        FileAccountStore s = store();
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        s.create(AppUser.fresh(10001L, now),
                new UserIdentity(10001L, IdentityType.WX_OPEN, "o_a", now), null);

        String json = Files.readString(dir.resolve("accounts.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"id\" : \"10001\""), "账号 id 要带引号,实得:" + json);
        assertTrue(json.contains("\"userId\" : \"10001\""), "身份行上的 userId 同样带引号");

        // 重新读一遍还是同一个号 —— 字符串形态不是一次单程的格式化
        assertEquals(10001L, new FileAccountStore(dir.resolve("accounts.json"))
                .findById(10001L).orElseThrow().id());
    }

    @Test
    @DisplayName("id 必须 ≥ 10001 —— 0 和负数在类型上通得过,在构造器上通不过")
    void rejectsOutOfRangeIds() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> AppUser.fresh(0L, now));
        assertThrows(IllegalArgumentException.class, () -> AppUser.fresh(-1L, now));
        assertThrows(IllegalArgumentException.class,
                () -> new UserIdentity(0L, IdentityType.PHONE, "x", now));
    }
}
