package com.kaodian.server.auth;

/**
 * 一次令牌校验的<b>档位</b> —— {@code 接口契约} §1.2「未授权三档」与
 * docs/technical/backend/B0-平台底座与横切契约.md §5.3 的形状。
 *
 * <h2>为什么 {@link TokenService#verify} 不够</h2>
 *
 * {@code verify} 把四种失败折叠成一个空的 {@code Optional}。
 * 而契约要求 {@code 401} 必须能区分「从没登录」/「登录过期」/「已注销」
 * ({@code UNAUTHORIZED} / {@code TOKEN_EXPIRED} / {@code ACCOUNT_DEACTIVATED}):
 * 区分不出来,登录门的副标题({@code U5.1})就写不出来。
 *
 * <h2>🔴 四叶不等于四个 code —— {@link Revoked} 是内部档</h2>
 *
 * <table border="1">
 *   <caption>档位 → 对外 code</caption>
 *   <tr><th>这里的叶子</th><th>对外</th><th>泄露了什么</th></tr>
 *   <tr><td>{@link Expired}</td><td>{@code TOKEN_EXPIRED}</td>
 *       <td><b>什么都没有</b> —— 持有这个令牌的人本来就知道它曾经有效</td></tr>
 *   <tr><td>{@link Revoked} + 账号 {@code ACTIVE}</td><td>{@code UNAUTHORIZED}</td>
 *       <td>与「查不到」仍然分不开。{@code POST /tokens/revoke-all} 存在的意义
 *           正是让被吊销的一方什么都不知道</td></tr>
 *   <tr><td>{@link Revoked} + 账号 {@code DEACTIVATED}</td><td>{@code ACCOUNT_DEACTIVATED}</td>
 *       <td>只对已注销本人说出「你已注销」—— 而他本人就是执行注销的那个人</td></tr>
 *   <tr><td>{@link Invalid}</td><td>{@code UNAUTHORIZED}</td><td>同第二行</td></tr>
 * </table>
 *
 * 所以「已吊销」<b>不单独成一个 code</b>:那才是真的送信息 ——
 * 它等于告诉持有者「这个令牌曾经是真的」。
 *
 * <p>⚠️ 本轮只提供这个形状与 {@link TokenService#check};消费它的是<b>下一轮的鉴权过滤器</b>
 * (B0-4 §5.2)。今天没有任何 controller 的 401 分支读它。
 */
public sealed interface TokenCheck
        permits TokenCheck.Valid, TokenCheck.Expired, TokenCheck.Revoked, TokenCheck.Invalid {

    /** 有效。{@code token} 已经滑过续期,与 {@link TokenService#verify} 返回的是同一条。 */
    record Valid(AccessToken token) implements TokenCheck {
    }

    /** 令牌确实存在过,只是过了 {@code expiresAt}。 */
    record Expired() implements TokenCheck {
    }

    /**
     * 已吊销。🔴 <b>内部档,不直接映射成一个 code。</b>
     *
     * <p>🔴 <b>{@code userId} 是这三档能落地的唯一原因</b> —— 没有它就查不到账号状态,
     * {@code UNAUTHORIZED} 与 {@code ACCOUNT_DEACTIVATED} 分不开。
     * 而 {@link Invalid} 那一叶<b>查不到令牌行,永远没有 userId 可查</b>,
     * 于是 {@code ACCOUNT_DEACTIVATED} 在结构上就说不出口 ——
     * <b>泄露面由结构限死,不靠实现者自觉。</b>
     */
    record Revoked(long userId) implements TokenCheck {
    }

    /** 没带头 / 格式不对 / 查不到。 */
    record Invalid() implements TokenCheck {
    }
}
