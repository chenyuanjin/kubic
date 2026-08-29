package com.kaodian.server.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信授权回跳用的一次性 {@code state} —— <b>防 CSRF 的那一半</b>。
 *
 * <h2>不校验 state 会怎样</h2>
 *
 * 攻击者先自己走一遍微信授权,拿到<b>属于他自己</b>的 {@code code};
 * 然后诱导已登录的受害者访问一个带着这个 code 的回跳地址。
 * 受害者的浏览器带着自己的登录态把这个 code 提交上来,服务端一看
 * 「这个 unionid 没绑过」→ <b>把攻击者的微信绑到了受害者的账号上</b>。
 * 此后攻击者用自己的微信就能登进受害者的账号。
 * <p>
 * state 由服务端生成、服务端记住、服务端校验,这条路才是死的。
 * <b>前端自己生成的 state 服务端无从验证,那等于没有 state。</b>
 *
 * <h2>放内存,不落盘</h2>
 *
 * 与 {@code AccountService} 的合并令牌同一条:重启导致 state 失效是<b>收紧</b>,
 * 最坏结果是用户重新点一次登录。反过来(重启导致锁失效)才必须落盘。
 */
public class OneTimeStateStore {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int BYTES = 24;

    /** 超过这个数就顺手清一次过期项。没有定时任务,也不需要。 */
    private static final int SWEEP_THRESHOLD = 512;

    /**
     * 🔴 硬上限。清过一遍之后仍然超,就<b>拒绝再签发</b>。
     *
     * <h2>为什么光有 sweep 不够</h2>
     *
     * {@code /auth/wechat/authorize-url} 是<b>不需要登录</b>的 —— 任何人都能调。
     * 只靠 sweep 的话有两个问题:清理只清得掉<b>已过期</b>的,而 10 分钟内狂签的那些一个都清不掉;
     * 而且 sweep 是 O(n),一旦越过阈值就<b>每次签发都扫一遍全表</b> —— 变成平方级。
     * <p>
     * 上限撞上时拒绝签发,而不是继续涨。代价是<b>正常用户在被攻击期间点不了微信登录</b> ——
     * 但那好过整个进程被拖垮:前者只影响一条入口,后者影响所有人,包括正在记录的人。
     * (「记录动作永不失败」是那条更高的线。)
     */
    private static final int MAX_STATES = 20_000;

    private final Map<String, Instant> states = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public OneTimeStateStore(Clock clock) {
        this.clock = clock;
    }

    public String issue() {
        if (states.size() > SWEEP_THRESHOLD) {
            sweep();
            if (states.size() >= MAX_STATES) {
                // 清过一遍还这么多 = 有人在刷。拒绝签发,别让内存跟着涨。
                throw new IllegalStateException(
                        "微信授权请求过于频繁,请稍后再试(待核销的 state 已达上限 " + MAX_STATES + ")");
            }
        }
        byte[] b = new byte[BYTES];
        random.nextBytes(b);
        String s = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        states.put(s, clock.instant().plus(TTL));
        return s;
    }

    /**
     * 核销。<b>一次性</b> —— 校验通过即移除,同一个 state 用不了第二次。
     *
     * @return 通过与否
     */
    public boolean consume(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        Instant expiry = states.remove(state);
        return expiry != null && clock.instant().isBefore(expiry);
    }

    private void sweep() {
        Instant now = clock.instant();
        for (Iterator<Map.Entry<String, Instant>> it = states.entrySet().iterator(); it.hasNext(); ) {
            if (!now.isBefore(it.next().getValue())) {
                it.remove();
            }
        }
    }
}
