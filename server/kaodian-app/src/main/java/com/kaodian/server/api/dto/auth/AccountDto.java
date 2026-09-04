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
 *
 * <h2>🔴 这里也没有 {@code nickname} 了({@code M5-账号与登录通道} §9.9)</h2>
 *
 * 微信那条路上昵称头像<b>一概丢弃</b>({@code U5.3} §三),而手机号那条路
 * 根本没有昵称来源 —— 于是它是一个<b>永远为 {@code null} 的字段</b>。
 * 留着它的代价不是多一个 key,是<b>下一个人会去把它填上</b>:
 * 要填就得从某个地方拿,而唯一拿得到的地方是微信,那一格正是被红线关掉的。
 *
 * <p>⚠️ {@code AppUser.nickname} 本身不动 —— 那是服务端内部字段,
 * 删它要改持久化格式,不在本轮的范围里。这里删掉的是它的<b>对外投影</b>。
 *
 * @param userId 🔴 {@code long} 以<b>字符串</b>传输({@code B0} §3.3),如 {@code "10001"}
 */
public record AccountDto(
        String userId,
        Instant createdAt,
        String maskedPhone,
        List<String> identities,
        int activeSessionCount
) {
}
