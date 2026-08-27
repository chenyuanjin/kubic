package com.kaodian.server.auth;

import java.util.Optional;

/**
 * 验证码与号码锁的存放处。
 *
 * <h2>每个号留两条,不是一条 —— 而这一条是被测试逼出来的</h2>
 *
 * 直觉上「同号重新发了一条 → 旧的作废」在存储上就是覆盖,每个号留最新一条即可。
 * <b>但那样一来 {@link SmsCode.State#SUPERSEDED} 这个终态永远观察不到</b>:
 * 用户拿着旧码来验,库里只剩新码,哈希对不上 → 回的是「验证码不对」。
 * <p>
 * 而 docs/13 §1.8 明确要求这是<b>四句不同的话</b>里的一句:
 *
 * <table border="1">
 *   <caption>为什么不能合并</caption>
 *   <tr><th>终态</th><th>用户该做的事</th></tr>
 *   <tr><td>输错</td><td>重输</td></tr>
 *   <tr><td><b>已作废(同号发了新的)</b></td><td><b>用新的那条</b></td></tr>
 * </table>
 *
 * 说成「验证码不对」的代价很具体:用户以为自己手抖,<b>对着那条旧码反复输,
 * 把自己输到锁定</b> —— 而他手机里其实躺着一条能用的新码。
 * <p>
 * 所以每号留两个槽:{@link #findLatest} 与 {@link #findSuperseded}。
 * 再多留没有意义 —— 更早的那些码用户早就看不到了。
 */
public interface SmsCodeStore {

    /** 这个号最近一条码。没发过则为空。 */
    Optional<SmsCode> findLatest(String phoneHmac);

    /** 上一条(已作废)。用来把「用新的那条」和「你输错了」区分开。 */
    Optional<SmsCode> findSuperseded(String phoneHmac);

    /**
     * 发出一条新码。
     *
     * <p><b>作废旧码这件事由 store 做,不由调用方做</b> —— 与「规则放在 store 而不是
     * controller」是同一条(docs/13 §二)。调用方少写一步就少一个能忘的地方。
     */
    void issue(SmsCode code);

    /**
     * 🔴 <b>核销:比对 + 改状态必须在<u>同一把锁</u>里完成。</b>
     *
     * <p>写成「{@link #findLatest} 读出来 → 服务层比对 → {@code update(consumed)}」的话,
     * 两个并发请求会<b>都读到 {@code SENT}、都比对通过、都核销</b> ——
     * 「单次使用」这个承诺就断了。而它断掉的后果不是多登录一次:
     * 两条请求会同时走进「查不到账号 → 建号」,撞出一个本不该存在的重复建号竞态。
     *
     * <p>所以这里是一个 compare-and-set,不是两次调用。
     *
     * @return 真的由这次调用核销掉了吗。{@code false} = 码不对、状态不是 SENT、或者<b>已经被别人核销了</b>
     */
    boolean consumeIfSent(String phoneHmac, String codeHmac);

    /** 记一次输错(错误次数 +1)。与 {@link #recordFailure} 分开:这条只动码,不动锁。 */
    void countAttempt(String phoneHmac);

    /** 这个号的错误计数与锁定窗口。从没错过则返回 {@link PhoneLock#clean}。 */
    PhoneLock lockOf(String phoneHmac);

    /**
     * 🔴 <b>记一次失败并返回新的锁状态 —— 读-改-写必须原子。</b>
     *
     * <p>原来的 {@code lockOf(...) → afterFailure(...) → putLock(...)} 是三步:
     * 两个并发的错误猜测会<b>都读到 {@code failedCount=0}、都写 1</b>,计数只前进一格。
     * 于是「错 5 次锁定」在并发下变成「错 10 次锁定」——
     * <b>攻击者只要并发就能把这道闸的强度打对折。</b>
     */
    PhoneLock recordFailure(String phoneHmac, java.time.Instant now);

    /** 校验成功后清零。 */
    void clearLock(String phoneHmac);
}
