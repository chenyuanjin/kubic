package com.kaodian.server.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 能往前拨的时钟。
 *
 * <p>鉴权这一层里几乎每一条规则都是关于时间的 —— 5 分钟有效、60 秒冷却、
 * 30 天滑动、30 分钟锁定、跨自然日清零。用真实时间测这些等于不测。
 */
final class TestClock extends Clock {

    private Instant now;

    TestClock(String iso) {
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
