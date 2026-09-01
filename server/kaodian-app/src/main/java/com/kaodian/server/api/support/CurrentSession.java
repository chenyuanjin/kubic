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

    public String userId() {
        return token.userId();
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
}
