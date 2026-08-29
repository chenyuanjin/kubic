package com.kaodian.server.auth;

import java.time.Instant;

/**
 * 一个账号 —— <b>主表不放任何登录凭证</b>(docs/10 §5.2)。
 *
 * <p>没有 {@code phone}、没有 {@code openid}、没有 {@code password}。
 * 凭证一律在 {@link UserIdentity} 里,一个通道一行。
 * 这样合并两个账号时改的是 identity 的归属,主表一个字段都不用动。
 *
 * @param id        账号 id
 * @param nickname  昵称。可空 —— 手机号通道注册时根本没有昵称,而<b>逼用户起名会在
 *                  离开成本最低的那一秒多加一个页面</b>(docs/13 §1.7)
 * @param status    账号状态
 * @param createdAt 建号时刻。<b>关卡 3「累计 50 个陌生注册」的判据数据源</b>,见 {@link SignupLedger}
 * @param deletedAt 注销时刻;未注销为 {@code null}。
 *                  🔴 <b>软删之后什么时候硬删,本代码不定</b> —— docs/10 §6.1 明确把它留给
 *                  {@code L-A5} 的律师稿。这里只保证「注销后立刻登不进来、令牌全部失效」
 */
public record AppUser(
        String id,
        String nickname,
        AccountStatus status,
        Instant createdAt,
        Instant deletedAt
) {

    public AppUser {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("账号必须有 id");
        }
        if (status == null) {
            throw new IllegalArgumentException("账号必须有状态");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("账号必须有建号时刻 —— 关卡 3 的判据全靠它");
        }
        if (status == AccountStatus.DEACTIVATED && deletedAt == null) {
            throw new IllegalArgumentException("已注销的账号必须有注销时刻");
        }
    }

    public static AppUser fresh(String id, Instant now) {
        return new AppUser(id, null, AccountStatus.ACTIVE, now, null);
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public AppUser deactivated(Instant now) {
        return new AppUser(id, nickname, AccountStatus.DEACTIVATED, createdAt, now);
    }

    public AppUser renamed(String newNickname) {
        return new AppUser(id, newNickname, status, createdAt, deletedAt);
    }
}
