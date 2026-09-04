package com.kaodian.server.billing;

/**
 * 额度闸在 {@code app} 这一侧的实现({@code M2-打标管线与模型接入} §2.5,
 * {@code M7-额度与订单} §11.1)。
 *
 * <h2>依赖方向:一条新边都不建</h2>
 *
 * {@code domain} 只知道「要调模型前先问一句能不能」,<b>它不知道额度、账单、订单存在</b>。
 * 接口 {@code ModelCallGate} 落 {@code kaodian-domain · tagging}(归 {@code M2}),
 * 实现落这里({@code app → domain} 与 {@code app →} 商业化<b>两条都是既有边</b>);
 * 由 controller 构造本类,作为 {@code suggest(...)} 的<b>入参</b>传进去 ——
 * {@code domain} 不注入、不查找、不知道实现类名。
 *
 * <h2>⚠️ 今天还没有 {@code implements ModelCallGate},这是有意的</h2>
 *
 * {@code com.kaodian.server.tagging.ModelCallGate} 由 {@code M2}(KUBI-102)落地,
 * 那个包今天不存在。本类<b>逐字照 {@code M2} §2.5 的签名</b>写了那两个方法,
 * 接口一落地,{@code M2} 加一行 {@code implements ModelCallGate} 即可,方法体一个字不改。
 * <b>本模块不去 {@code domain} 里替 {@code M2} 建那个文件</b> —— 那是跨手改别人的层。
 *
 * <h2>🔴 {@link #acquire()} 是预检,<u>不扣</u></h2>
 *
 * 时序是{@code 接口契约} §6.7.1 定死的:<b>先调后扣</b> —— 失败不扣,所以扣减必须发生在
 * 外部调用成功之后,由 {@link QuotaService#consume} 完成。
 * <p>
 * 于是 {@link #release()} <b>是一个空操作,而且必须是</b>:{@code acquire} 什么都没拿走,
 * 就没有什么可以退回。<b>一个真的会把 {@code used} 减回去的 {@code release}
 * 就是 {@link QuotaStore} 上那个被明令不许存在的 {@code refund(...)}</b>({@code M7} §2.4)。
 * <p>
 * ⚠ 代价 {@code M7} §2.6 已经认下:预检通过之后并发扣减仍可能撞回滚,
 * 「<b>外部账单已产生、扣不进去</b>」这一次的成本我方自己承担,界面按耗尽处置。
 * 🔴 <b>不许为了消掉这一格而改成「先扣后调」</b>。
 */
public final class QuotaModelCallGate {

    private final QuotaService quotas;
    private final long userId;
    private final String periodYm;
    private final QuotaType quotaType;

    /**
     * @param periodYm 🔴 <b>由调用方在最外层算一次传进来</b>(§2.5),本类不自己算 ——
     *                 跨月零点那一秒各算一次会落到两行上
     */
    public QuotaModelCallGate(QuotaService quotas, long userId, String periodYm, QuotaType quotaType) {
        this.quotas = quotas;
        this.userId = userId;
        this.periodYm = periodYm;
        this.quotaType = quotaType;
    }

    /** 拿一次外部调用的许可。{@code false} = 拿不到({@code domain} 不需要知道原因)。 */
    public boolean acquire() {
        return quotas.peek(userId, periodYm, quotaType).hasRemaining();
    }

    /**
     * 退回一次未兑现的许可。只在「压根没看成」时调用。
     *
     * <p>🔴 <b>空操作</b> —— {@link #acquire()} 是预检不是扣减,见类注释。
     */
    public void release() {
        // 什么都不做,而且这一点是契约:acquire 没拿走任何东西。
    }
}
