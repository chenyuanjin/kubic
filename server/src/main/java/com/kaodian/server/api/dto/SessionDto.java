package com.kaodian.server.api.dto;

import com.kaodian.server.auth.AccessToken;

import java.time.Instant;

/**
 * 设备管理页(D26)的一行。
 *
 * @param tokenHash 吊销这一条时要回传的值。<b>它是哈希,不是令牌</b> ——
 *                  拿着它登不了任何东西,只能用来指认「就是这一条」
 * @param current   是不是当前这台。<b>界面必须标出来</b>,否则用户会把自己踢下线然后以为是 bug
 */
public record SessionDto(
        String tokenHash,
        String deviceLabel,
        Instant issuedAt,
        Instant lastUsedAt,
        Instant expiresAt,
        boolean revoked,
        boolean current
) {

    public static SessionDto from(AccessToken t, String currentHash) {
        return new SessionDto(t.tokenHash(), t.deviceLabel(), t.issuedAt(), t.lastUsedAt(),
                t.expiresAt(), t.isRevoked(), t.tokenHash().equals(currentHash));
    }
}
