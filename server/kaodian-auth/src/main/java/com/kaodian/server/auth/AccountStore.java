package com.kaodian.server.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 账号、身份、手机号密文的存放处。
 *
 * <h2>规则放在 store,不放在 controller</h2>
 *
 * docs/technical/后端系统设计与组件接入.md §二 已经写死的两条纪律之一:<b>controller 可以再写一个,store 只有这一个。</b>
 * 所以「同一个 identity 不能挂到两个账号上」这类不变式在这里兑现,
 * 不在接口层靠自觉。
 */
public interface AccountStore {

    Optional<AppUser> findById(long userId);

    /** 登录查号的那一步。{@code (type, identifier)} 是唯一索引。 */
    Optional<AppUser> findByIdentity(IdentityType type, String identifier);

    List<UserIdentity> identitiesOf(long userId);

    /**
     * 发一个新号 —— <b>发号器在 store 里,不在 service 里</b>({@code B0} §3.2)。
     *
     * <h2>为什么不能让 {@link AccountService} 自己算</h2>
     *
     * {@code long} 是连续的,发号必须先读一次当前最大值 ——
     * 而那一读只有 store 手里的锁能保护。旧的 {@code u_}+UUID 没有这个问题(随机不用读),
     * 这正是<b>换成连续 id 之后必须多出这个方法</b>的原因 —— 它不是顺手加的。
     *
     * <p>🔴 <b>实现必须保证同一个号不被发两次,而「读最大值 + 1」做不到这一点</b> ——
     * 发号与 {@link #create} 是两次调用,中间锁是放开的。踩法与后果见
     * {@link FileAccountStore#nextUserId()}(一个被并发测试当场抓住的 500)。
     *
     * <p>发出去的号<b>不保证被用掉</b>,所以 id 允许跳号。要求不跳号就得在失败时把号还回去,
     * 而那是一个会写错的回滚。
     */
    long nextUserId();

    /** 建过几个账号(含已注销的)。启动期与 {@link SignupLedger#totalCount()} 对账用。 */
    int countCreated();

    /**
     * 建账号 + 第一行 identity。<b>两件事必须一起成功</b> ——
     * 只建了账号没建 identity,那个账号谁也登不进去,而且它会占着一个 id 永远存在。
     *
     * @param phoneSecret 手机号通道时非空;微信通道为 {@code null}
     * @throws IllegalStateException identity 已被占用
     */
    AppUser create(AppUser user, UserIdentity firstIdentity, PhoneNumberSecret phoneSecret);

    /**
     * 给已有账号加一条 identity(绑手机号 / 绑微信)。
     *
     * @throws IdentityTakenException 目标 identity 已属他人 —— <b>调用方据此返回可合并提示,
     *                                不自动合并</b>(docs/technical/INDEX.md §6.1)
     */
    void addIdentity(UserIdentity identity, PhoneNumberSecret phoneSecret);

    /** 手机号密文。没绑手机号则为空。 */
    Optional<PhoneNumberSecret> phoneSecretOf(long userId);

    /**
     * 注销 —— <b>一次原子落盘做四件事</b>({@code M5-账号与登录通道} §5.1):
     * ① {@code status} 改 {@code DEACTIVATED} ② 写 {@code deletedAt}
     * ③ <b>摘掉该账号的全部 identity</b> ④ <b>删掉手机号密文</b>。
     *
     * <p>⚠️ 上一版这里写的是「只改状态与 {@code deletedAt}」,而实现
     * ({@link FileAccountStore#deactivate})一直是四件事 —— 读接口的人会以为 identity 还在。
     * 这是同一模块内文档与实现的一处不一致,{@code M5} §十二 已登记,<b>改的是注释不是行为</b>。
     *
     * <p>③ 为什么必须摘:不摘的话那个手机号<b>永远登不回来也永远给不了别人</b>,
     * 而手机号是会被运营商回收的。
     *
     * <p>🔴 行为层({@code Touch} / {@code RecordTag} / {@code UserAssertion})<b>不在这里删</b> ——
     * 那三个 {@code deleteAllOf} 归 {@code app} 编排,在本方法<b>之后</b>调
     * ({@code M5} §5.2)。在这里删就等于建出 {@code auth → domain} 这条不许有的边。
     *
     * <p>🔴 硬删时点由 {@code L-A5} 的律师稿定,本层不做。
     */
    void deactivate(long userId, Instant now);

    /**
     * 执行合并:{@code from} 的全部 identity 改挂到 {@code to},{@code from} 标记注销,写留痕。
     *
     * <p><b>不可逆。</b> 调用方必须先走过预览与二次确认(docs/technical/INDEX.md §7.1)。
     */
    AccountMergeLog merge(long fromUserId, long toUserId, int movedRecordCount, Instant now);

    List<AccountMergeLog> mergeLogs();

    // —— 密钥指纹与换钥({@code R-59})——

    /**
     * 数据上盖着的那个密钥指纹。老数据(本机制上线之前写的)没有,返回空。
     *
     * @see PhoneKeyGuard
     */
    Optional<PhoneKeyFingerprint> keyFingerprint();

    /** 盖章。<b>没有手机号数据时也要盖</b> —— 空库盖上章,下次换钥就能被发现。 */
    void stampKeyFingerprint(PhoneKeyFingerprint fingerprint);

    /** 库里有几个手机号身份。指纹守卫用它判断「有没有数据可丢」。 */
    int phoneIdentityCount();

    /**
     * 换钥:把每一个手机号用新密钥重算一遍,<b>并原子地一起落盘</b>。
     *
     * <h2>为什么这件事必须在 store 里做,不能由上层循环调用</h2>
     *
     * 它要同时改两个地方:{@code user_identity.identifier} 和 {@code phone_enc}。
     * 而这两个之间有一条不变式 —— <b>{@code identifier} 必须等于 {@code phoneSecret.hmac()}</b>,
     * 否则那个账号<b>再也查不到</b>(而这正是 {@code R-59} 本身)。
     * <p>
     * 上层逐条改,中途失败一次就会留下一半新一半旧的数据 ——
     * 那比全部用旧密钥更糟:全旧还能再换一次,一半一半谁也修不了。
     *
     * @param rehash 旧的三形态 → 新的三形态。实现方式是「用旧 AES 解出明文,再用新密钥算一遍」
     * @return 重算了几条
     */
    int rekeyPhones(java.util.function.UnaryOperator<PhoneNumberSecret> rehash,
                    PhoneKeyFingerprint newFingerprint);

    /** identity 已被别人占了。 */
    class IdentityTakenException extends RuntimeException {

        private final long ownerUserId;

        public IdentityTakenException(String message, long ownerUserId) {
            super(message);
            this.ownerUserId = ownerUserId;
        }

        /** 占用者。合并预览要它 —— <b>但绝不回给客户端</b>,那等于告诉别人「这个号有账号」。 */
        public long ownerUserId() {
            return ownerUserId;
        }
    }
}
