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

    private final Map<String, Instant> states = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public OneTimeStateStore(Clock clock) {
        this.clock = clock;
    }

    public String issue() {
        if (states.size() > SWEEP_THRESHOLD) {
            sweep();
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
