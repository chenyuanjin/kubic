package com.kaodian.server.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 令牌方案 —— docs/technical/INDEX.md §7.4 与 docs/technical/后端系统设计与组件接入.md §1.9。
 *
 * <p>这里钉住的核心是那四个字:<b>立即失效</b>。它是不用 JWT 的全部理由,
 * 所以它必须有测试。
 */
class TokenServiceTest {

    @TempDir
    Path dir;

    private TestClock clock;
    private TokenService tokens;

    private TokenService service() {
        clock = new TestClock("2026-09-01T00:00:00Z");
        tokens = new TokenService(new FileTokenStore(dir.resolve("t.json")), clock);
        return tokens;
    }

    @Test
    @DisplayName("明文只出现一次,库里只有 SHA-256")
    void plaintextNeverStored() {
        IssuedToken issued = service().issue(10001L, TokenScope.FULL, "iPhone · Safari");

        assertTrue(issued.plaintext().startsWith("at_"));
        assertEquals(TokenService.sha256(issued.plaintext()), issued.stored().tokenHash());
        assertNotEquals(issued.plaintext(), issued.stored().tokenHash());
        // toString 覆写存在的意义:某天有人写下 log.debug("issued {}", token) 也只会打出掩码
        assertFalse(issued.toString().contains(issued.plaintext()));
    }

    @Test
    @DisplayName("前缀参与哈希 —— 把 ro_ 改成 at_ 换不出写能力")
    void prefixIsPartOfTheHash() {
        TokenService s = service();
        IssuedToken ro = s.issue(10001L, TokenScope.READONLY, "MCP");
        assertTrue(ro.plaintext().startsWith("ro_"));

        String forged = "at_" + ro.plaintext().substring(3);
        assertTrue(s.verify(forged).isEmpty(), "改前缀应当查不到任何一行");

        // 原样的 ro_ 仍然有效,但它的作用域来自库,不是来自前缀
        assertEquals(TokenScope.READONLY, s.verify(ro.plaintext()).orElseThrow().scope());
    }

    @Test
    @DisplayName("吊销立刻生效 —— 这是 JWT 做不到、因而被排除的那一条")
    void revokeIsImmediate() {
        TokenService s = service();
        IssuedToken t = s.issue(10001L, TokenScope.FULL, "iPad");
        assertTrue(s.verify(t.plaintext()).isPresent());

        assertTrue(s.revoke(t.plaintext()));
        assertTrue(s.verify(t.plaintext()).isEmpty(), "下一次调用就应当失效,不等过期");

        // 幂等:「退出登录」在网络不稳时会被点两次,第二次报错只会让人以为没退成功
        assertFalse(s.revoke(t.plaintext()));
    }

    @Test
    @DisplayName("注销时全量吊销:多设备一起断")
    void revokeAllOfUser() {
        TokenService s = service();
        IssuedToken a = s.issue(10001L, TokenScope.FULL, "手机");
        IssuedToken b = s.issue(10001L, TokenScope.FULL, "电脑");
        IssuedToken other = s.issue(10002L, TokenScope.FULL, "别人的手机");

        assertEquals(2, s.revokeAll(10001L));
        assertTrue(s.verify(a.plaintext()).isEmpty());
        assertTrue(s.verify(b.plaintext()).isEmpty());
        assertTrue(s.verify(other.plaintext()).isPresent(), "不能误伤别的账号");
    }

    @Test
    @DisplayName("别人的会话吊销不了 —— 越权是显式失败,不是静默无事发生")
    void cannotRevokeSomeoneElsesSession() {
        TokenService s = service();
        IssuedToken mine = s.issue(10001L, TokenScope.FULL, "我的");
        IssuedToken theirs = s.issue(10002L, TokenScope.FULL, "他的");

        assertThrows(IllegalArgumentException.class,
                () -> s.revokeByHash(10001L, theirs.stored().tokenHash()));
        assertTrue(s.revokeByHash(10001L, mine.stored().tokenHash()));
    }

    @Test
    @DisplayName("30 天滑动:用一次就往后推;30 天不用则过期")
    void slidingExpiry() {
        TokenService s = service();
        IssuedToken t = s.issue(10001L, TokenScope.FULL, "手机");

        clock.advance(Duration.ofDays(20));
        AccessToken slid = s.verify(t.plaintext()).orElseThrow();
        assertEquals(clock.instant().plus(TokenService.LIFETIME), slid.expiresAt(),
                "用一次就应当从此刻起重新算 30 天");

        // 从这次滑动之后一直不用。注意不能在中途再 verify 一次来「看看还在不在」——
        // 那次 verify 自己就会再滑一次,于是永远测不到过期。
        clock.advance(TokenService.LIFETIME.plusDays(1));
        assertTrue(s.verify(t.plaintext()).isEmpty(), "连续 31 天没用,应当过期");
    }

    @Test
    @DisplayName("滑动落盘被节流:一小时内的重复使用不重写文件")
    void slideIsThrottled() {
        TokenService s = service();
        IssuedToken t = s.issue(10001L, TokenScope.FULL, "手机");

        clock.advance(Duration.ofMinutes(30));
        AccessToken first = s.verify(t.plaintext()).orElseThrow();
        // 推进不到一小时 → 原样返回,过期时刻仍是签发时那个
        assertEquals(t.stored().expiresAt(), first.expiresAt());

        clock.advance(Duration.ofHours(2));
        AccessToken second = s.verify(t.plaintext()).orElseThrow();
        assertTrue(second.expiresAt().isAfter(t.stored().expiresAt()));
    }

    @Test
    @DisplayName("设备列表按最后使用时间倒序,当前这条能被认出来")
    void sessionList() {
        TokenService s = service();
        s.issue(10001L, TokenScope.FULL, "旧设备");
        clock.advance(Duration.ofHours(2));
        IssuedToken newer = s.issue(10001L, TokenScope.FULL, "新设备");

        List<AccessToken> list = s.sessionsOf(10001L);
        assertEquals(2, list.size());
        assertEquals("新设备", list.get(0).deviceLabel());
        assertEquals(newer.stored().tokenHash(), list.get(0).tokenHash());
    }

    @Test
    @DisplayName("重启之后令牌仍然有效 —— 会话落在文件里,不在内存里")
    void survivesRestart() {
        Path file = dir.resolve("restart.json");
        TestClock c = new TestClock("2026-09-01T00:00:00Z");
        IssuedToken t = new TokenService(new FileTokenStore(file), c)
                .issue(10001L, TokenScope.FULL, "手机");

        TokenService afterRestart = new TokenService(new FileTokenStore(file), c);
        assertTrue(afterRestart.verify(t.plaintext()).isPresent());
    }

    // —— TokenCheck 四档(B0 §5.3 · 接口契约 §1.2「未授权三档」)——

    @Test
    @DisplayName("🔴 check 拆四档,而 Revoked 带得出 userId —— 三档能落地的唯一原因")
    void checkSplitsFourLeaves() {
        TokenService s = service();
        IssuedToken good = s.issue(10001L, TokenScope.FULL, "手机");
        IssuedToken revoked = s.issue(10002L, TokenScope.FULL, "被踢的那台");
        IssuedToken expired = s.issue(10003L, TokenScope.FULL, "放了很久的那台");
        s.revoke(revoked.plaintext());

        assertInstanceOf(TokenCheck.Valid.class, s.check(good.plaintext()));

        // 已吊销 —— 内部档,而它必须带着 userId:没有它就查不到账号状态,
        // UNAUTHORIZED 与 ACCOUNT_DEACTIVATED 分不开。
        TokenCheck r = s.check(revoked.plaintext());
        assertInstanceOf(TokenCheck.Revoked.class, r);
        assertEquals(10002L, ((TokenCheck.Revoked) r).userId());

        clock.advance(TokenService.LIFETIME.plusDays(1));
        assertInstanceOf(TokenCheck.Expired.class, s.check(expired.plaintext()));

        // 🔴 查不到令牌行的那一叶永远没有 userId 可查 —— 泄露面由结构限死,不靠实现者自觉。
        assertInstanceOf(TokenCheck.Invalid.class, s.check("at_" + "x".repeat(43)));
        assertInstanceOf(TokenCheck.Invalid.class, s.check("没有前缀"));
        assertInstanceOf(TokenCheck.Invalid.class, s.check(null));
    }

    @Test
    @DisplayName("verify 就是 check 的 Valid 那一叶 —— 滑动续期不会有两套行为")
    void verifyIsCheckValid() {
        TokenService s = service();
        IssuedToken t = s.issue(10001L, TokenScope.FULL, "手机");

        clock.advance(Duration.ofDays(5));
        assertEquals(s.verify(t.plaintext()).orElseThrow().expiresAt(),
                ((TokenCheck.Valid) s.check(t.plaintext())).token().expiresAt(),
                "两条路必须给出同一个 expiresAt,否则续期就有了两套行为");
    }
}
