package com.kaodian.server.billing;

import java.time.Instant;

/**
 * 一个人的订阅({@code M7-额度与订单} §5.3)。
 *
 * <h2>🔴 不建 {@code status} 列,{@code active} 派生自 {@code expiresAt}</h2>
 *
 * {@code 技术架构与接口契约} §5.5.2 的字段清单上有一列 {@code status},本文不建它
 * ({@code M7} §十二 冲突 4 / §契约增量 5):
 * {@code status} 与 {@code expiresAt} 表达<b>同一件事</b>,而它需要一个定时任务把「到期」
 * 写进去才准 —— <b>没有那个任务它就是一个会静默过期的第二真源</b>,
 * 而两个真源里先过期的一定是那个没人盯着的。
 *
 * <h2>续费不新建行</h2>
 *
 * 唯一索引建在 {@code userId} 上,续费 = <b>延长 {@code expiresAt}</b>,
 * 历史在 {@code payment_order} 里({@code 技术架构与接口契约} §5.5.2)。
 *
 * @param userId    唯一索引就建在它上面
 * @param planCode  当前档位
 * @param expiresAt 到期时点。🔴 <b>{@code null} 表示免费档</b>,界面那一行整行不渲染 ——
 *                  不返回「永久有效」这类字符串({@code U7.6} §2.6)
 */
public record Subscription(long userId, String planCode, Instant expiresAt) {

    /** 🔴 派生,不是存储列。见类注释。 */
    public boolean isActive(Instant now) {
        return expiresAt != null && expiresAt.isAfter(now);
    }

    /**
     * 续费:从<b>较晚的那个时点</b>起算再加一个周期。
     *
     * <p>没到期就续,应当在原到期日<b>之后</b>接着算 —— 从 {@code now} 起算会白白吃掉
     * 用户还没用完的那一段;已过期再续则从 {@code now} 起算,否则新买的一个月有一半是过去时。
     */
    public Instant extendFrom(Instant now) {
        return expiresAt == null || expiresAt.isBefore(now) ? now : expiresAt;
    }
}
