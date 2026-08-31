package com.kaodian.server.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 账号、身份、手机号密文的存放处。
 *
 * <h2>规则放在 store,不放在 controller</h2>
 *
 * docs/后端详设 §二 已经写死的两条纪律之一:<b>controller 可以再写一个,store 只有这一个。</b>
 * 所以「同一个 identity 不能挂到两个账号上」这类不变式在这里兑现,
 * 不在接口层靠自觉。
 */
public interface AccountStore {

    Optional<AppUser> findById(String userId);

    /** 登录查号的那一步。{@code (type, identifier)} 是唯一索引。 */
    Optional<AppUser> findByIdentity(IdentityType type, String identifier);

    List<UserIdentity> identitiesOf(String userId);

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
     *                                不自动合并</b>(docs/技术架构 §6.1)
     */
    void addIdentity(UserIdentity identity, PhoneNumberSecret phoneSecret);

    /** 手机号密文。没绑手机号则为空。 */
    Optional<PhoneNumberSecret> phoneSecretOf(String userId);

    /** 注销。只改状态与 {@code deletedAt};🔴 硬删时点由 {@code L-A5} 的律师稿定,本层不做。 */
    void deactivate(String userId, Instant now);

    /**
     * 执行合并:{@code from} 的全部 identity 改挂到 {@code to},{@code from} 标记注销,写留痕。
     *
     * <p><b>不可逆。</b> 调用方必须先走过预览与二次确认(docs/技术架构 §7.1)。
     */
    AccountMergeLog merge(String fromUserId, String toUserId, int movedRecordCount, Instant now);

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

        private final String ownerUserId;

        public IdentityTakenException(String message, String ownerUserId) {
            super(message);
            this.ownerUserId = ownerUserId;
        }

        /** 占用者。合并预览要它 —— <b>但绝不回给客户端</b>,那等于告诉别人「这个号有账号」。 */
        public String ownerUserId() {
            return ownerUserId;
        }
    }
}
