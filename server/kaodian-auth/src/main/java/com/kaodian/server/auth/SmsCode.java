package com.kaodian.server.auth;

import java.time.Instant;

/**
 * 一条已发出的验证码 —— docs/technical/后端系统设计与组件接入.md §1.8 那张状态机的数据形态。
 *
 * <h2>🔴 这里存的是验证码的 HMAC,不是六位数字</h2>
 *
 * 六位数字被哈希后仍然只有 10⁶ 种可能,离线爆破一瞬间就完 —— 所以用<b>带密钥的</b>
 * HMAC({@link PhoneCipher#hmacOfOpaque})。没有密钥就爆不动。
 * <p>
 * 这一层防的不是暴力猜码(那是 {@link PhoneLock} 的失败计数与锁定管的),而是
 * <b>「数据文件被拷走的那五分钟里,正在登录的人被顶掉」</b>。
 *
 * @param phoneHmac 哪个号的。与 {@link UserIdentity#identifier} 同一把哈希,于是能直接对上账号
 * @param codeHmac  验证码的 HMAC
 * @param purpose   用途。校验时必须一起比,防跨场景重放
 * @param issuedAt  发出时刻
 * @param expiresAt 过期时刻,发出后 5 分钟
 * @param state     此刻的状态
 */
public record SmsCode(
        String phoneHmac,
        String codeHmac,
        SmsPurpose purpose,
        Instant issuedAt,
        Instant expiresAt,
        State state
) {

    /**
     * 四种终态,<b>四句不同的话</b>(docs/technical/后端系统设计与组件接入.md §1.8)。
     *
     * <p>合并成一句「验证码错误」的具体代价:用户拿着一条已过期的码反复输,
     * 每输一次错误计数加一,最后<b>把自己输到锁定</b> —— 而他真正该做的只是点一下重发。
     */
    public enum State {
        /** 已发送,等着被核销。 */
        SENT,
        /** 已核销。单次使用,用完即死。 */
        CONSUMED,
        /** 已过期。用户该做的是<b>重发</b>。 */
        EXPIRED,
        /** 已作废 —— 同一个号又发了一条新的。用户该做的是<b>用新的那条</b>。 */
        SUPERSEDED
    }

    public SmsCode {
        if (phoneHmac == null || phoneHmac.isBlank()) {
            throw new IllegalArgumentException("验证码必须挂在一个号上");
        }
        if (codeHmac == null || codeHmac.isBlank()) {
            throw new IllegalArgumentException("验证码必须有哈希");
        }
        if (purpose == null || state == null) {
            throw new IllegalArgumentException("验证码必须有用途和状态");
        }
        if (issuedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("验证码必须有时间");
        }
    }

    /**
     * 把「过期」这件事从时间推出来 —— <b>不靠定时任务改状态</b>。
     *
     * <p>靠定时任务的话,任务没跑起来的那段时间里过期的码仍然可用,而且没人会发现。
     * 状态由时间派生,这个失败模式就不存在。
     */
    public State effectiveStateAt(Instant now) {
        if (state == State.SENT && !now.isBefore(expiresAt)) {
            return State.EXPIRED;
        }
        return state;
    }

    public SmsCode consumed() {
        return new SmsCode(phoneHmac, codeHmac, purpose, issuedAt, expiresAt, State.CONSUMED);
    }

    public SmsCode superseded() {
        return new SmsCode(phoneHmac, codeHmac, purpose, issuedAt, expiresAt, State.SUPERSEDED);
    }

}
