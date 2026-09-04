package com.kaodian.server.billing;

/**
 * 订单状态机的五个态、三个终态({@code M7-额度与订单} §4.1)。
 *
 * <h2>🔴 列名与 API 字段都叫 {@code state},不是 {@code status}</h2>
 *
 * {@code 技术架构与接口契约} §5.5.2 与那张 ER 图写的是 {@code status},
 * {@code 接口契约} §8.4 / §8.5 / §8.7 的响应字段写的是 {@code state} —— 两边都是目标态。
 * <b>裁定取 {@code state},库列与 API 字段同名</b>({@code M7} §十二 冲突 8):
 * 不同名就要有一层映射,而<b>那层映射今天没有任何一份文档写过</b> ——
 * 一个没人写下来的映射,等于每个人各写一遍。
 *
 * <p>⚠ 只读令牌那张表的 {@code status}({@code ACTIVE}/{@code REVOKED}/{@code EXPIRED},
 * {@code 接口契约} §6.7)是<b>另一个实体</b>,不受本条影响。
 *
 * <h2>🔴 {@code CONFIRMING → CLOSED} 这条边不存在</h2>
 *
 * 钱可能已在我方,关掉它就是把一笔已付款的单变成不可查。超时关闭<b>只对 {@code PENDING} 生效</b>。
 */
public enum OrderState {

    /** 已下单、尚未收到任何上游确认。 */
    PENDING(false),

    /** 收款确认中或发放进行中 —— <b>唯一会带 {@code grantState} 的一态</b>。 */
    CONFIRMING(false),

    /** 🔴 终态。{@code PAID} 本身就说明发放完成,所以这一态<b>不带 {@code grantState}</b>。 */
    PAID(true),

    /** 终态。超时关闭或用户主动关单,只从 {@code PENDING} 来。 */
    CLOSED(true),

    /**
     * 终态。⚪ <b>只由平台侧 / Apple 侧发起,我方没有端点</b>({@code M7} §六)。
     *
     * <p>🔴 进了这一态之后<b>额度与会员期一格不动</b>。这不是一个裁定,是「退款写法 A/B/C 未选」
     * 的直接后果 —— <b>在规则定下来之前,不动是唯一一个不预设规则的动作</b>({@code M7} §6.4)。
     */
    REFUNDED(true);

    private final boolean terminal;

    OrderState(boolean terminal) {
        this.terminal = terminal;
    }

    /** 终态 —— {@code settle} 第 ① 步撞上它就立即返回,什么都不做。 */
    public boolean isTerminal() {
        return terminal;
    }
}
