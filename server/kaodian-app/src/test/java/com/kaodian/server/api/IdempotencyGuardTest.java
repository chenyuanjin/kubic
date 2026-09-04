package com.kaodian.server.api;

import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.support.IdempotencyGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 「请求键」幂等({@code 接口契约} §1.5 / §3.3,{@code B0} §7.3)。
 *
 * <p>锚定键是 {@code (userId, path, Idempotency-Key)} 三列 —— 下面两条「互不干扰」正是这三列的判据,
 * 少一列就会在别人身上或别的端点上静默命中。
 */
class IdempotencyGuardTest {

    private static final String PATH = "/api/billing/orders";
    private static final Duration DAY = Duration.ofHours(24);

    private final MutableClock clock = new MutableClock("2026-09-04T00:00:00Z");
    private final IdempotencyGuard guard = new IdempotencyGuard(clock);

    @Test
    @DisplayName("第一次是 Fresh;complete 之后重放拿到的是同一个结果,不是再跑一次")
    void freshThenCompleteThenReplay() {
        assertInstanceOf(IdempotencyGuard.Fresh.class, guard.begin(1L, PATH, "k", DAY));

        Object order = new Object();
        guard.complete(1L, PATH, "k", order);

        IdempotencyGuard.Replay replay = assertInstanceOf(IdempotencyGuard.Replay.class,
                guard.begin(1L, PATH, "k", DAY));
        assertEquals(order, replay.result(), "重放必须是上一次那个结果 —— 不再扣额度、不再产生第二笔账单");
    }

    @Test
    @DisplayName("上一次还没 complete —— 第二次是 InFlight(调用方回 409 IN_PROGRESS)")
    void secondAttemptWhileStillRunningIsInFlight() {
        guard.begin(1L, PATH, "k", DAY);

        assertInstanceOf(IdempotencyGuard.InFlight.class, guard.begin(1L, PATH, "k", DAY));
    }

    @Test
    @DisplayName("🔴 不同 userId 用同一个键互不干扰 —— 单列唯一会让 A 的键顶掉 B 的")
    void sameKeyFromAnotherUserIsAFreshRequest() {
        guard.begin(1L, PATH, "k", DAY);
        guard.complete(1L, PATH, "k", "A 的订单");

        assertInstanceOf(IdempotencyGuard.Fresh.class, guard.begin(2L, PATH, "k", DAY),
                "B 带着同一个键来,拿到的绝不能是 A 的结果");
    }

    @Test
    @DisplayName("🔴 不同 path 用同一个键互不干扰 —— 端上被允许复用同一个 clientToken 当这个头")
    void sameKeyOnAnotherEndpointIsAFreshRequest() {
        guard.begin(1L, "/api/records/7/audio", "k", DAY);
        guard.complete(1L, "/api/records/7/audio", "k", "转写结果");

        assertInstanceOf(IdempotencyGuard.Fresh.class,
                guard.begin(1L, "/api/records/7/tags/suggest", "k", DAY),
                "第二个端点拿到第一个端点的结果,就是牛头不对马嘴的答案 + 真实发生的账单");
    }

    @Test
    @DisplayName("过了保留期又是 Fresh;保留期由调用方按端点给,没有统一天数")
    void expiredEntryIsFreshAgain() {
        Duration tenMinutes = Duration.ofMinutes(10);           // DELETE /account 那一档
        guard.begin(1L, "/api/account", "k", tenMinutes);
        guard.complete(1L, "/api/account", "k", "已注销");

        clock.advance(Duration.ofMinutes(9));
        assertInstanceOf(IdempotencyGuard.Replay.class, guard.begin(1L, "/api/account", "k", tenMinutes));

        clock.advance(Duration.ofMinutes(2));
        assertInstanceOf(IdempotencyGuard.Fresh.class, guard.begin(1L, "/api/account", "k", tenMinutes));
    }

    @Test
    @DisplayName("上次失败 → 允许重试,不是永远撞在 InFlight 上")
    void failedAttemptCanBeRetried() {
        guard.begin(1L, PATH, "k", DAY);
        guard.fail(1L, PATH, "k");

        assertInstanceOf(IdempotencyGuard.Fresh.class, guard.begin(1L, PATH, "k", DAY));
    }

    @Test
    @DisplayName("标了要带而没带 → 400 IDEMPOTENCY_KEY_REQUIRED")
    void missingKeyIsRejected() {
        for (String missing : new String[]{null, "", "   "}) {
            ApiException rejected = assertThrows(ApiException.class, () -> guard.begin(1L, PATH, missing, DAY));
            assertEquals(HttpStatus.BAD_REQUEST, rejected.status());
            assertEquals("IDEMPOTENCY_KEY_REQUIRED", rejected.code());
        }
    }

    /** 能往前拨的时钟 —— 保留期是这一层唯一的时间规则,用真实时间测它等于不测。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(String iso) {
            this.now = Instant.parse(iso);
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
