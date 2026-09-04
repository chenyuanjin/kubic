package com.kaodian.server.auth;

/**
 * 一次令牌校验的四种结果 —— <b>四叶,不是一个 {@code Optional}</b>
 * ({@code M5-账号与登录通道} §4.3)。
 *
 * <h2>为什么 {@link TokenService#verify} 那个 {@code Optional} 不够用</h2>
 *
 * 它把四种失败折叠成一个空值,于是契约 §10.5 里的 {@code TOKEN_EXPIRED}
 * <b>在代码里根本没有出生的地方</b>({@code B0} §6.4 登记的落差之一)。
 * 而端确实需要区分:过期要的是「重新登录」,格式不对要的是「这个串不是令牌」。
 *
 * <h2>🔴 {@code 401} 是三档,而这里是四叶 —— 差的那一格正是它的全部设计</h2>
 *
 * <table border="1">
 *   <caption>四叶 → 三档</caption>
 *   <tr><th>叶</th><th>对外 {@code code}</th><th>判定依据</th></tr>
 *   <tr><td>{@link Valid}</td><td>放行</td><td>令牌</td></tr>
 *   <tr><td>{@link Expired}</td><td>{@code TOKEN_EXPIRED}</td><td>令牌</td></tr>
 *   <tr><td>{@link Revoked}</td><td><b>要再查一次账号才知道</b>:账号仍 {@code ACTIVE} →
 *       {@code UNAUTHORIZED};账号 {@code DEACTIVATED} → {@code ACCOUNT_DEACTIVATED}</td>
 *       <td><b>账号</b></td></tr>
 *   <tr><td>{@link Invalid}</td><td>{@code UNAUTHORIZED}</td><td>令牌</td></tr>
 * </table>
 *
 * {@code Revoked} <b>带着 {@code userId} 而对外没有一个对应的码</b> —— 这正是它存在的形状:
 * 它是调用方「再查一次账号状态」的入口,不是一个响应。
 *
 * <h2>{@code B0} §5.3 那条红线原样保住</h2>
 *
 * {@code B0} 的顾虑是「已吊销单独成档 = 告诉持有者这个令牌曾经是真的」。
 * 这里第三档的判定依据是<b>账号状态不是令牌状态</b>:被踢下线的一方(账号还活着)
 * 拿到的仍然是 {@code UNAUTHORIZED},他什么都不知道。
 * <p>
 * 而且泄露面被<b>结构</b>限死,不靠一条要记住的规矩:
 * 🔴 <b>查不到令牌行的时候永远走 {@link Invalid}</b> —— 那时候根本没有 {@code userId} 可查,
 * 所以「这个账号注销了」这句话在那条路上<b>说不出来</b>。
 *
 * <h2>账号那一次查询落在 {@code app},不落在这里</h2>
 *
 * {@code M5} §十一:令牌服务只回答令牌的事。让 {@link TokenService} 自己去查账号状态,
 * 等于把「账号」这个概念塞进一个只做随机、哈希、比时间的类里。
 */
public sealed interface TokenCheck {

    /** 有效。{@code token} 已经滑过续期。 */
    record Valid(AccessToken token) implements TokenCheck {
    }

    /** 令牌行确实存在过,只是过了 {@code expiresAt}。 */
    record Expired() implements TokenCheck {
    }

    /**
     * 令牌行存在但已被吊销。
     *
     * <p>🔴 <b>内部档,不直接映射成一个 {@code code}</b> —— 调用方拿 {@code userId}
     * 再查一次账号状态才能决定说哪一句。
     */
    record Revoked(long userId) implements TokenCheck {
    }

    /** 没带 / 格式不对 / 查不到。<b>这三种分不开是有意的。</b> */
    record Invalid() implements TokenCheck {
    }
}
