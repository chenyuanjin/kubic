package com.kaodian.server.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code M5-6} 四叶 → {@code 401} 三档({@code M5-账号与登录通道} §4.3)。
 *
 * <p>四条断言缺一不可:少任何一条,{@code TOKEN_EXPIRED} 与 {@code ACCOUNT_DEACTIVATED}
 * 就又回到「没有出生的地方」那一格。
 */
class TokenCheckTest {

    private static final long USER = 10001L;

    @TempDir
    Path dir;

    private TestClock clock;

    private TokenService service() {
        clock = new TestClock("2026-09-01T00:00:00Z");
        return new TokenService(new FileTokenStore(dir.resolve("t.json")), clock);
    }

    @Test
    @DisplayName("🔴 四叶各自分得开 —— 上一版把它们折叠成一个 Optional,于是三个错误码没有出生的地方")
    void fourLeavesAreDistinguishable() {
        TokenService s = service();

        // Valid
        IssuedToken t = s.issue(USER, TokenScope.FULL, "iPhone");
        assertInstanceOf(TokenCheck.Valid.class, s.check(t.plaintext()));

        // Invalid:没带 / 格式不对 / 查不到 —— 三种合成一叶是有意的
        assertInstanceOf(TokenCheck.Invalid.class, s.check(null));
        assertInstanceOf(TokenCheck.Invalid.class, s.check("  "));
        assertInstanceOf(TokenCheck.Invalid.class, s.check("这不是令牌"));
        assertInstanceOf(TokenCheck.Invalid.class, s.check("at_" + "x".repeat(43)));

        // Revoked:带着 userId 出来,那正是「再查一次账号状态」的入口
        IssuedToken revoked = s.issue(USER, TokenScope.FULL, "iPad");
        assertTrue(s.revoke(revoked.plaintext()));
        var r = assertInstanceOf(TokenCheck.Revoked.class, s.check(revoked.plaintext()));
        assertEquals(USER, r.userId());

        // Expired:令牌行确实存在过,只是过了 expiresAt
        IssuedToken stale = s.issue(USER, TokenScope.FULL, "旧手机");
        clock.advance(TokenService.LIFETIME.plus(Duration.ofDays(1)));
        assertInstanceOf(TokenCheck.Expired.class, s.check(stale.plaintext()));
    }

    @Test
    @DisplayName("🔴 查不到令牌行的时候永远说不出 ACCOUNT_DEACTIVATED —— 泄露面由结构限死")
    void unknownTokenNeverCarriesAUserId() {
        TokenService s = service();
        s.issue(USER, TokenScope.FULL, "真的那条");

        // 一个从没签发过的、格式完全合法的令牌
        TokenCheck check = s.check("at_" + "A".repeat(43));

        // 🔴 它必须是 Invalid,不是 Revoked —— Invalid 没有 userId 字段,
        //    所以「这个账号注销了」这句话在这条路上【在类型上就说不出来】。
        //    靠一条「记得别在这里查账号」的规矩是守不住的,靠没有那个值才守得住。
        assertInstanceOf(TokenCheck.Invalid.class, check);
    }

    @Test
    @DisplayName("🔴 既被吊销又已过期的令牌走「已吊销」那一档 —— 否则注销满 30 天后语义会静默改变")
    void revokedWinsOverExpired() {
        TokenService s = service();
        IssuedToken t = s.issue(USER, TokenScope.FULL, "手机");
        assertTrue(s.revoke(t.plaintext()));

        clock.advance(TokenService.LIFETIME.plus(Duration.ofDays(1)));

        // 反过来判的话:注销当天旧令牌说「已注销」,30 天后同一条令牌改说「登录过期」——
        // 同一个用户、同一个动作,两句不同的话,而中间没有任何一次代码改动。
        var r = assertInstanceOf(TokenCheck.Revoked.class, s.check(t.plaintext()));
        assertEquals(USER, r.userId());
    }

    @Test
    @DisplayName("verify 是 check 的投影 —— 两处各写一遍判断,「一致」就只剩注释在保证")
    void verifyIsAProjectionOfCheck() {
        TokenService s = service();
        IssuedToken good = s.issue(USER, TokenScope.FULL, "手机");
        IssuedToken bad = s.issue(USER, TokenScope.FULL, "平板");
        s.revoke(bad.plaintext());

        assertTrue(s.verify(good.plaintext()).isPresent());
        assertTrue(s.verify(bad.plaintext()).isEmpty());
        assertTrue(s.verify("at_" + "B".repeat(43)).isEmpty());
    }
}
