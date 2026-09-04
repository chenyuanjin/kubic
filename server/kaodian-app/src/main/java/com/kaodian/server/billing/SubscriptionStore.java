package com.kaodian.server.billing;

import java.util.Optional;

/**
 * 订阅存储。唯一索引在 {@code userId} 上 —— <b>续费延长 {@code expiresAt},不新建行</b>。
 *
 * <p>🔴 <b>没有 {@code deactivate(...)} / {@code markExpired(...)}</b>:
 * 「是否生效」派生自 {@code expiresAt}({@link Subscription#isActive}),
 * 一个能把「已过期」写进去的方法就是那个不该存在的 {@code status} 列的内部版本。
 */
public interface SubscriptionStore {

    Optional<Subscription> find(long userId);

    /**
     * 延长到期日并写上新档位 —— 🔴 <b>必须与 {@link QuotaStore#grant} 在同一次原子写里</b>
     * ({@code M7-额度与订单} §3.3 步 ⑤)。
     *
     * <p>两次写分开,中间挂掉就会出现「<b>会员期延长了、额度还是免费档</b>」——
     * 而这个状态<b>没有任何一条路径会发现它</b>,因为订单已经是 {@code PAID}。
     */
    Subscription save(Subscription subscription);
}
