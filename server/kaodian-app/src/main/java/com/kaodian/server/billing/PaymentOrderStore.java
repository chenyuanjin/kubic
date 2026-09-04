package com.kaodian.server.billing;

import com.kaodian.server.api.dto.common.Cursor;
import com.kaodian.server.api.dto.common.Page;

import java.util.List;
import java.util.Optional;

/**
 * 订单存储({@code M7-额度与订单} §4.3)。
 *
 * <h2>🔴 保留期限:形状补,天数不补</h2>
 *
 * {@code 接口契约} §8.6 已裁定「不设保留期」(缺口 22,{@code G-9} 关闭)。
 * 落成这个接口上的一个<b>空缺</b> —— 没有 {@code purge(...)}、没有 {@code deleteBefore(...)}、
 * 没有总量上限检查。
 * <p>
 * 「界面翻到底说『没有更多』」—— <b>而这句话是真的</b>。加一条保留策略才需要重开 {@code G-9}。
 */
public interface PaymentOrderStore {

    Optional<PaymentOrder> findByOutTradeNo(String outTradeNo);

    /** 按 {@code (createdAt, outTradeNo)} 倒序翻页。形状是 {@code B0} §7.1 的 {@link Page},一个字不改。 */
    Page<PaymentOrder> findByUser(long userId, Cursor.Position cursor, int limit);

    /** 这个人这个档位上还没终结的单 —— 下单幂等(§3.4)与 {@code pendingOrders}(§5.3)共用它。 */
    List<PaymentOrder> findOpenByUser(long userId);

    /** 早于阈值仍未终结的单 —— 定时补偿扫的就是它(§3.1 路三)。 */
    List<PaymentOrder> findStale(java.time.Instant before);

    PaymentOrder save(PaymentOrder order);

    /**
     * 认领一个上游交易号 —— <b>自然键的唯一性检查</b>({@code M7} §3.3 步 ③)。
     *
     * @return {@code true} 这个交易号第一次见,已经写进这一单;
     *         {@code false} 撞唯一键 —— <b>视为已处理,返回成功但不重复发放</b>
     */
    boolean claimTransactionId(String outTradeNo, String transactionId);

    // 🔴 没有 purge(...)、没有 deleteBefore(...)、没有 retainedDays、没有总量上限。见类注释。
}
