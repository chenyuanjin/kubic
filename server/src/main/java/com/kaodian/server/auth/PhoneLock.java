package com.kaodian.server.auth;

import java.time.Instant;

/**
 * 一个号上的连续错误计数与锁定窗口。
 *
 * <h2>为什么锁定挂在号上,不挂在账号上</h2>
 *
 * 锁定发生的那一刻<b>可能还没有账号</b> —— 注册即登录(docs/13 §1.7),
 * 账号是在验证码校验通过的那一瞬间才建的。挂在账号上意味着「猜一个陌生号的验证码」
 * 永远不会被锁定,而那正是要防的那件事。
 *
 * @param phoneHmac   哪个号
 * @param failedCount 连续错了几次。<b>校验成功即清零</b>
 * @param lockedUntil 锁到什么时候;未锁定为 {@code null}
 */
public record PhoneLock(String phoneHmac, int failedCount, Instant lockedUntil) {

    /** 错满这个数就锁(docs/10 §6.1)。 */
    public static final int MAX_FAILURES = 5;

    /** 锁多久(docs/13 §1.8 的状态机)。 */
    public static final java.time.Duration LOCK_WINDOW = java.time.Duration.ofMinutes(30);

    public PhoneLock {
        if (phoneHmac == null || phoneHmac.isBlank()) {
            throw new IllegalArgumentException("锁必须挂在一个号上");
        }
        if (failedCount < 0) {
            throw new IllegalArgumentException("错误次数不能为负");
        }
    }

    public static PhoneLock clean(String phoneHmac) {
        return new PhoneLock(phoneHmac, 0, null);
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * 又错了一次。够数就锁。
     *
     * <p>注意锁上之后 {@code failedCount} <b>归零</b>:否则解锁后的第一次输错会立刻再锁 30 分钟,
     * 而用户完全不知道自己为什么只有一次机会。
     */
    public PhoneLock afterFailure(Instant now) {
        int n = failedCount + 1;
        if (n >= MAX_FAILURES) {
            return new PhoneLock(phoneHmac, 0, now.plus(LOCK_WINDOW));
        }
        return new PhoneLock(phoneHmac, n, lockedUntil);
    }

    /** 还能错几次。用来给用户一个准确的数,而不是「请重试」。 */
    public int remainingAttempts() {
        return Math.max(0, MAX_FAILURES - failedCount);
    }
}
