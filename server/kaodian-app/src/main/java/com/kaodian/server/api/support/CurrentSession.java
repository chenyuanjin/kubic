package com.kaodian.server.api.support;

import com.kaodian.server.auth.AccessToken;
import com.kaodian.server.auth.TokenScope;

/**
 * 当前请求背后的那条会话。控制器方法上声明一个这个类型的参数即可拿到,
 * 解析由 {@link CurrentSessionResolver} 做。
 *
 * <p>拿到它就意味着<b>令牌已经验过</b> —— 控制器里不该再出现任何一次
 * 「先看看有没有 Authorization 头」。那种写法漏一处就是一个不设防的端点。
 */
public record CurrentSession(AccessToken token) {

    public long userId() {
        return token.userId();
    }

    /** 账号 id 对外一律以<b>字符串</b>传输({@code B0} §3.3):int64 在 JS 里过不了 {@code Number} 那一关。 */
    public String userIdString() {
        return Long.toString(token.userId());
    }

    public TokenScope scope() {
        return token.scope();
    }

    /**
     * 写操作前的检查。
     *
     * <p>🔴 但请注意它<b>不是</b> {@code ro_} 令牌的主要防线 —— 主要防线是
     * MCP/CLI 那一侧根本换不出写能力({@link TokenScope})。
     * 这里是第二道:同一条纪律在 docs/technical/INDEX.md §6.5 已经用过一次,
     * <b>一道锁失效不该导致整条线失守</b>。
     */
    public void requireWrite() {
        if (!token.scope().canWrite()) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "READONLY_TOKEN",
                    "这是只读令牌,换不出写能力。MCP 与 CLI 一律只读(docs/technical/INDEX.md §6.7.3)。");
        }
    }

    /**
     * 🔴 <b>只读令牌不能管理令牌 —— 不论方法,{@code GET} 也不行</b>
     * ({@code M5-账号与登录通道} §4.5)。
     *
     * <h2>为什么它不能靠 {@link #requireWrite} 覆盖</h2>
     *
     * {@code requireWrite} 只拦写操作,而 {@code GET /api/v1/tokens} 是<b>读</b>。
     * 少了这一条,一条泄露出去的 {@code ro_} 令牌就能列出这个账号的全部会话,
     * 再顺着列表把所有 {@code at_} 全吊销掉 ——
     * <b>一个只读凭证不该有制造拒绝服务的能力。</b>
     *
     * <p>⚠️ 这是<b>第二道</b>锁。第一道是 {@code B0-4} 的鉴权过滤器
     * ({@code /tokens/**} 一律 {@code 403},不论方法),它在本模块的基线上还没落地 ——
     * 所以今天这一道是唯一在跑的那道。过滤器落地之后这里也不删:
     * 同一条纪律在 {@link #requireWrite} 上已经用过一次,<b>一道锁失效不该导致整条线失守</b>。
     */
    public void requireTokenManagement() {
        if (!token.scope().canWrite()) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "READONLY_TOKEN",
                    "只读令牌不能管理登录设备 —— 换不出这个能力,不是被判断为不许用。");
        }
    }
}
