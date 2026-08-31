package com.kaodian.server.auth;

import com.kaodian.server.auth.vendor.CaptchaVerifier;
import com.kaodian.server.auth.vendor.SmsDeliveryException;
import com.kaodian.server.auth.vendor.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 验证码的发与验 —— docs/后端详设 §1.8 那两张图的全部实现。
 *
 * <h2>四道闸的顺序就是这个类的全部要点</h2>
 *
 * <pre>
 *   ① 行为验证(滑块)   → 不通过:拒绝,<b>一分钱没花</b>
 *   ② 单号频控 1/60s · 10/日 → 超限:拒绝,告知<b>准确</b>重置时点
 *   ③ 单 IP 频控 20/日        → 超限:同上
 *   ④ 调运营商发短信          → <b>这一步开始花钱</b>
 * </pre>
 *
 * 契约(docs/技术架构 §6.1)把这五条约束都列全了,<b>但没有写它们的先后</b> ——
 * 而顺序恰恰是这件事的全部。把①挪到④后面,前三道闸就只是在给账单排队。
 *
 * <h2>四种终态,四句不同的话</h2>
 *
 * 合并成一句「验证码错误」的具体代价:用户拿着过期的码反复输,把自己输到锁定。
 * 所以 {@link VerifyOutcome} 是一个 sealed interface 而不是一个 boolean ——
 * <b>让「只回一句话」在类型上就写不出来</b>。
 */
public class SmsCodeService {

    private static final Logger log = LoggerFactory.getLogger(SmsCodeService.class);

    /** docs/技术架构 §6.1:5 分钟有效。 */
    public static final Duration CODE_TTL = Duration.ofMinutes(5);

    private static final int CODE_DIGITS = 6;

    private final SmsCodeStore store;
    private final SmsRateLimiter limiter;
    private final CaptchaVerifier captcha;
    private final SmsSender sender;
    private final PhoneCipher cipher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SmsCodeService(SmsCodeStore store, SmsRateLimiter limiter, CaptchaVerifier captcha,
                          SmsSender sender, PhoneCipher cipher, Clock clock) {
        this.store = store;
        this.limiter = limiter;
        this.captcha = captcha;
        this.sender = sender;
        this.cipher = cipher;
        this.clock = clock;
    }

    // —— 发 ——

    /**
     * 走完四道闸,发一条。
     *
     * @param rawPhone 用户原样输入的号,规整在 {@link PhoneCipher#normalize} 里做
     * @param ip       调用方 IP;取不到传空串
     */
    public SendOutcome send(String rawPhone, SmsPurpose purpose, String captchaTicket,
                            String captchaRandstr, String ip) {
        String phone;
        try {
            phone = PhoneCipher.normalize(rawPhone);
        } catch (IllegalArgumentException e) {
            return new SendOutcome.BadPhone(e.getMessage());
        }
        String phoneHmac = cipher.hmacOfOpaque(phone);
        Instant now = clock.instant();

        // 号被锁着的时候不该再发。发了也没用 —— 校验那一侧一样会拒,
        // 而这条短信是要花钱的。锁定态在这里就要挡住,不能等到校验。
        PhoneLock lock = store.lockOf(phoneHmac);
        if (lock.isLockedAt(now)) {
            return new SendOutcome.PhoneLocked(lock.lockedUntil());
        }

        // ① 行为验证 —— 一分钱没花的那一步
        CaptchaVerifier.Verdict verdict = captcha.verify(captchaTicket, captchaRandstr, ip);
        if (!verdict.passed()) {
            log.info("滑块未通过 ip={} reason={}", ip, verdict.reason());
            return new SendOutcome.CaptchaFailed();
        }

        // ②③ 频控。先占名额再发 —— 反过来的话并发的两个请求会同时通过
        SmsRateLimiter.Decision decision = limiter.reserve(phoneHmac, ip, now);
        switch (decision) {
            case SmsRateLimiter.Decision.TooFrequent d -> {
                return new SendOutcome.TooFrequent(d.retryAt());
            }
            case SmsRateLimiter.Decision.PhoneDailyExhausted d -> {
                return new SendOutcome.DailyExhausted(d.resetAt(), d.limit(), true);
            }
            case SmsRateLimiter.Decision.IpDailyExhausted d -> {
                return new SendOutcome.DailyExhausted(d.resetAt(), d.limit(), false);
            }
            case SmsRateLimiter.Decision.Allowed ignored -> {
                // 继续
            }
        }

        // 先写库再发短信:反过来的话,发送成功但落库失败会得到一条用户收到了、
        // 服务端却不认的码 —— 那是最难被解释的一种失败。
        // 旧码作废由 store 自己做(SmsCodeStore#issue),这里不重复一遍。
        String code = newCode();
        String codeHmac = cipher.hmacOfOpaque(code);
        store.issue(new SmsCode(phoneHmac, codeHmac, purpose,
                now, now.plus(CODE_TTL), SmsCode.State.SENT));

        // ④ 花钱
        try {
            sender.sendVerificationCode(PhoneCipher.toE164(phone), code);
        } catch (SmsDeliveryException e) {
            if (e.definitelyNotCharged()) {
                // 我们自己的配置问题(签名没批、模板没审、余额不足),不该吃掉用户的日额度。
                limiter.releaseDaily(phoneHmac, ip);
                // 而且这条码【确定没送达】—— 用户手里不可能有它。留着它,
                // 下一次发码时它会被挪进 superseded 槽,于是那个槽被一条用户从没见过的码占着,
                // 真正该说「请用最新收到的那一条」的场景反而说不出来了。
                store.discard(phoneHmac, codeHmac);
            }
            // 🔴 「不确定」时【不清】—— 短信可能已经在路上,删掉它等于让用户即将收到的码验不过去。
            log.warn("短信发送失败 vendorCode={} definitelyNotCharged={}",
                    e.vendorCode(), e.definitelyNotCharged(), e);
            return new SendOutcome.SendFailed(e.definitelyNotCharged());
        }

        return new SendOutcome.Sent(now.plus(CODE_TTL),
                sender.isReal() ? null : code);      // 非真实发送时把码带回去,仅本机开发用
    }

    // —— 验 ——

    /**
     * 校验。<b>成功即核销,单次使用。</b>
     *
     * @return 五种结果之一。调用方必须处理全部五种 —— sealed 让这件事在编译期被要求
     */
    public VerifyOutcome verify(String rawPhone, String code, SmsPurpose purpose) {
        String phone;
        try {
            phone = PhoneCipher.normalize(rawPhone);
        } catch (IllegalArgumentException e) {
            return new VerifyOutcome.BadPhone(e.getMessage());
        }
        String phoneHmac = cipher.hmacOfOpaque(phone);
        Instant now = clock.instant();

        PhoneLock lock = store.lockOf(phoneHmac);
        if (lock.isLockedAt(now)) {
            return new VerifyOutcome.Locked(lock.lockedUntil());
        }

        // 🔴 先看看用户拿的是不是【上一条】—— 那是「请用最新收到的那一条」,不是「你输错了」。
        // 这一步必须在计错误次数之前:说成输错的话,用户会对着旧码反复输,
        // 而他手机里其实躺着一条能用的新码(docs/后端详设 §1.8)。
        // 顺带:它【不计入错误次数】—— 拿着自己刚收到过的码不是在猜。
        //
        // 但作废槽里的码同样会过期。一条三天前的旧码回「请用最新收到的那一条」是误导 ——
        // 那条新的多半也早过期了,用户该做的是重发。所以这里也要判 TTL。
        String attemptHmac = cipher.hmacOfOpaque(code);
        if (store.findSuperseded(phoneHmac)
                .filter(old -> now.isBefore(old.expiresAt()))
                .filter(old -> constantTimeEquals(old.codeHmac(), attemptHmac))
                .isPresent()) {
            return new VerifyOutcome.Superseded();
        }

        Optional<SmsCode> found = store.findLatest(phoneHmac);
        if (found.isEmpty()) {
            return new VerifyOutcome.NoneOutstanding();
        }
        SmsCode c = found.get();

        switch (c.effectiveStateAt(now)) {
            case EXPIRED -> {
                return new VerifyOutcome.Expired();
            }
            case SUPERSEDED -> {
                // 最新槽里的那条不该是这个状态(作废的会被挪到 superseded 槽)。
                // 留着这个分支只是为了 switch 穷尽 —— 真出现说明 store 的不变式坏了。
                return new VerifyOutcome.Superseded();
            }
            case CONSUMED -> {
                // 已经用过了。它和「过期」不是一回事:用过说明这条码<b>成功登录过一次</b>,
                // 用户多半是在返回键之后又点了一次提交。让他重发,而不是让他以为码错了。
                return new VerifyOutcome.NoneOutstanding();
            }
            case SENT -> {
                // 落到下面
            }
        }

        // 🔴 用途必须一起比 —— 防跨场景重放,见 SmsPurpose。
        // 用途不符按「输错」处理:告诉调用方「这条码不是给这件事用的」等于给攻击者指路。
        boolean ok = c.purpose() == purpose && constantTimeEquals(c.codeHmac(), attemptHmac);
        if (!ok) {
            // 🔴 读-改-写在 store 的锁里完成。分三步写的话,两个并发的错误猜测会
            // 都读到同一个 failedCount、都写 +1 —— 计数只前进一格,
            // 而那意味着攻击者只要并发就能把「错 5 次锁定」变成「错 10 次锁定」。
            PhoneLock next = store.recordFailure(phoneHmac, now);
            if (next.isLockedAt(now)) {
                return new VerifyOutcome.Locked(next.lockedUntil());
            }
            return new VerifyOutcome.Wrong(next.remainingAttempts());
        }

        // 🔴 比对 + 核销在 store 的同一把锁里(compare-and-set)。
        // 写成「上面比对通过 → 这里 update(consumed)」的话,两个并发请求会都比对通过、
        // 都核销 —— 「单次使用」就断了,而它断掉会把两条请求同时送进「查不到账号 → 建号」。
        if (!store.consumeIfSent(phoneHmac, c.codeHmac(), purpose)) {
            // 抢输了:这一瞬间被另一个请求核销掉了。对用户就是「已经用过了」。
            return new VerifyOutcome.NoneOutstanding();
        }
        store.clearLock(phoneHmac);                     // 成功即清零
        return new VerifyOutcome.Passed(phone, phoneHmac);
    }

    // —— 原语 ——

    /** 六位数字,含前导零。{@code 042317} 是合法验证码,截掉前导零会让 10% 的码变成五位。 */
    private String newCode() {
        return String.format("%0" + CODE_DIGITS + "d", random.nextInt(1_000_000));
    }

    /**
     * 定长比较。
     *
     * <p>这里比的是两个等长的十六进制 HMAC,理论上的时序侧信道极窄;
     * 但这是<b>唯一一处拿用户输入去比对秘密</b>的地方,写成常数时间的成本是三行。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // —— 结果类型 ——

    /** 发送的结果。 */
    public sealed interface SendOutcome {

        /**
         * 发出去了。
         *
         * @param expiresAt   这条码什么时候过期,回给前端做倒计时
         * @param devCode     🔴 <b>仅当发送器不是真实供应商时非空</b>。本机开发用,
         *                    生产环境永远是 {@code null} —— 见 {@link SmsSender#isReal}
         */
        record Sent(Instant expiresAt, String devCode) implements SendOutcome {
        }

        record BadPhone(String detail) implements SendOutcome {
        }

        /** 第①道闸没过。 */
        record CaptchaFailed() implements SendOutcome {
        }

        /** 单号 1/60s。{@code retryAt} 是准确时点,不是「请稍后」。 */
        record TooFrequent(Instant retryAt) implements SendOutcome {
        }

        /** @param perPhone {@code true} 是单号 10/日,{@code false} 是单 IP 20/日 */
        record DailyExhausted(Instant resetAt, int limit, boolean perPhone) implements SendOutcome {
        }

        /** 号被锁着,发了也验不了。 */
        record PhoneLocked(Instant unlockAt) implements SendOutcome {
        }

        record SendFailed(boolean quotaRefunded) implements SendOutcome {
        }
    }

    /**
     * 校验的结果 —— <b>六种,不是一个 boolean</b>。
     *
     * <p>docs/后端详设 §1.8 的那张表列了合并成一句话的后果。用 sealed interface 表达,
     * 是为了让「回一句笼统的话」这个选项在类型层面就不存在:
     * 调用方必须逐个分支处理,漏一个编译不过。
     */
    public sealed interface VerifyOutcome {

        /**
         * 通过。
         *
         * @param phone     规整后的手机号明文 —— 上层要用它建号/绑定
         * @param phoneHmac 同一个号的哈希,直接就是 {@code user_identity.identifier}
         */
        record Passed(String phone, String phoneHmac) implements VerifyOutcome {
        }

        record BadPhone(String detail) implements VerifyOutcome {
        }

        /** 输错了。{@code remainingAttempts} 让用户知道还有几次,而不是撞到锁定才发现。 */
        record Wrong(int remainingAttempts) implements VerifyOutcome {
        }

        /** 过期了 —— 用户该做的是<b>重发</b>。 */
        record Expired() implements VerifyOutcome {
        }

        /** 作废了(同号发了新的)—— 用户该做的是<b>用新的那条</b>。 */
        record Superseded() implements VerifyOutcome {
        }

        /** 没有待验证的码(从没发过 / 已经用掉了)。 */
        record NoneOutstanding() implements VerifyOutcome {
        }

        /** 锁定。{@code unlockAt} 是准确时点。 */
        record Locked(Instant unlockAt) implements VerifyOutcome {
        }
    }
}
