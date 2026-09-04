package com.kaodian.server.api.dto.auth;

import com.kaodian.server.auth.AccessToken;

import java.time.Instant;

/**
 * 登录设备列表({@code GET /api/v1/tokens})的一行 —— 界面 D26。
 *
 * <h2>🔴 它替代了旧的 {@code SessionDto},四处都改了({@code M5-账号与登录通道} §9.7)</h2>
 *
 * <table border="1">
 *   <caption>契约 §7.4 与旧代码对不上的四处</caption>
 *   <tr><th>#</th><th>旧</th><th>现在</th><th>为什么</th></tr>
 *   <tr><td>1</td><td>{@code GET /account/sessions}</td><td>{@code GET /api/v1/tokens}</td>
 *       <td>取契约的路径。留在 {@code /account} 下,「只读令牌不能管理令牌」这条锁要写两处路径</td></tr>
 *   <tr><td>2</td><td>{@code tokenHash}</td><td>{@code tokenId}</td>
 *       <td><b>值仍是那个哈希,只是外部名字不该泄露它是怎么算出来的</b></td></tr>
 *   <tr><td>3</td><td>{@code revoked} 字段</td><td><b>删</b></td>
 *       <td>见下</td></tr>
 *   <tr><td>4</td><td>{@code scope} 没有</td><td>补上</td><td>契约 §7.4 要它,而 {@code ro_} 与 {@code at_} 在这一页上必须能分开</td></tr>
 * </table>
 *
 * <h2>为什么没有 {@code revoked} / {@code revokedAt}</h2>
 *
 * 这个端点<b>只返回此刻可用的行</b>({@code U5.6} §6.2 ※11:「陈旧列表上的『退出这台』
 * 若仍可点,用户会以为退掉了一台其实已经不在的设备」)——
 * 那一页回答的是「<b>现在</b>有谁登着」,不是历史。
 * <p>
 * 🔴 而<b>一个只会返回可用行的接口带一个永远不出现的字段,是在邀请端去实现一段永远跑不到的分支</b>。
 * 所以这两处是连着的:口径定成「只返可用行」,字段就必须一起删掉。
 *
 * <h2>{@code tokenId} 是不透明字符串,不是 {@code int64}</h2>
 *
 * 这是 {@code B0} §3.2「标识一律 int64」的<b>唯一例外</b>({@code M5} §十三 增量 4):
 * 它不是发号器发的号,是令牌哈希的一次对外投影。改成 {@code int64} 等于给令牌加一个
 * <b>可枚举的序号</b>,而可枚举意味着「这个账号有几条令牌」变成一个能被数出来的事实。
 *
 * @param tokenId     吊销这一条时回传的值。<b>它是哈希,不是令牌</b> —— 拿着它登不了任何东西
 * @param deviceLabel 🔴 <b>服务端签发时生成,取自归一化后的 {@code User-Agent} 机型串,不可改</b>
 *                    ({@code L-9} 已关闭)。能改就多一个自由文本字段,而自由文本字段是红线要盯的东西。
 *                    代价认下:两台同型号设备看起来一样,用 {@link #lastUsedAt} 区分
 * @param scope       {@code full} / {@code readonly}
 * @param current     是不是当前这台。<b>界面必须标出来</b>,否则用户会把自己踢下线然后以为是 bug
 */
public record TokenDto(
        String tokenId,
        String deviceLabel,
        Instant issuedAt,
        Instant lastUsedAt,
        Instant expiresAt,
        String scope,
        boolean current
) {

    public static TokenDto from(AccessToken t, String currentHash) {
        return new TokenDto(t.tokenHash(), t.deviceLabel(), t.issuedAt(), t.lastUsedAt(),
                t.expiresAt(), t.scope().wireName(), t.tokenHash().equals(currentHash));
    }
}
