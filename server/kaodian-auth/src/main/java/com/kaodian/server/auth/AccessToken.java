package com.kaodian.server.auth;

import java.time.Instant;

/**
 * 一条会话 —— <b>库里只有 SHA-256,没有令牌原值</b>(docs/技术架构 §7.4)。
 *
 * <p>所以「重新看一遍我的令牌」这件事在产品上不存在:签发时返回一次,
 * 丢了就重新签一条。这不是省事,是让<b>一次数据库泄露不等于一批账号被接管</b>。
 *
 * @param tokenHash   明文令牌(含前缀)的 SHA-256,十六进制。<b>主键</b>
 * @param userId      属于谁
 * @param scope       能干什么。🔴 授权只看这个字段,不看前缀({@link TokenScope#hintFromPrefix})
 * @param deviceLabel 这台设备叫什么,如「iPhone · Safari」。设备管理页(D26)显示它
 * @param issuedAt    签发时刻
 * @param lastUsedAt  最后一次使用。设备管理页靠它排序,也是滑动续期的依据
 * @param expiresAt   过期时刻。30 天滑动
 * @param revokedAt   吊销时刻;未吊销为 {@code null}
 */
public record AccessToken(
        String tokenHash,
        String userId,
        TokenScope scope,
        String deviceLabel,
        Instant issuedAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt
) {

    public AccessToken {
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException("令牌必须有哈希");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("令牌必须属于某个账号");
        }
        if (scope == null) {
            throw new IllegalArgumentException("令牌必须有作用域");
        }
        if (issuedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("令牌必须有签发与过期时刻");
        }
    }

    /**
     * 此刻还能用吗。
     *
     * <p>吊销先于过期判断,只是为了让日志里能分清两者 —— 对调用方没有区别,
     * 但对「用户说他被莫名踢下线了」这件事有区别。
     */
    public boolean isUsableAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public AccessToken revoked(Instant now) {
        return new AccessToken(tokenHash, userId, scope, deviceLabel, issuedAt, lastUsedAt, expiresAt, now);
    }

    public AccessToken slid(Instant now, Instant newExpiry) {
        return new AccessToken(tokenHash, userId, scope, deviceLabel, issuedAt, now, newExpiry, revokedAt);
    }
}
