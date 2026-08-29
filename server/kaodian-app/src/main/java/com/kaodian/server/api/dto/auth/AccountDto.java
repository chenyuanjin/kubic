package com.kaodian.server.api.dto.auth;

import java.time.Instant;
import java.util.List;

/**
 * 「我的账号」。
 *
 * <p>🔴 这里<b>没有</b>手机号明文、没有 openid、没有 unionid。
 * {@code identities} 只是一串通道名({@code phone} / {@code wx_union}),
 * 用来回答「我绑了什么」,而不是「我绑的是哪一个」。
 * <p>
 * 手机号只以 {@code maskedPhone} 的形态出现,而那已经足够让用户认出是不是自己的号。
 */
public record AccountDto(
        String userId,
        String nickname,
        Instant createdAt,
        String maskedPhone,
        List<String> identities,
        int activeSessionCount
) {
}
