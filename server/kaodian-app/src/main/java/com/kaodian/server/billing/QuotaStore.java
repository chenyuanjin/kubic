package com.kaodian.server.billing;

import java.util.Optional;

/**
 * 额度账本 —— <b>一次扣减是一次原子写</b>({@code M7-额度与订单} §2.3)。
 *
 * <h2>🔴 接口上找不到的三个方法,各自是一条红线(§2.4)</h2>
 *
 * <table>
 *   <caption>不能有的方法与理由</caption>
 *   <tr><th>找不到的</th><th>为什么不能有</th></tr>
 *   <tr><td>{@code refund(...)} / {@code restore(...)} / 任何让 {@code used} 变小的方法</td>
 *       <td>失败根本不扣,所以没有「退还」这个动作({@code U7.1} §2.5)。
 *           有这个方法,界面上迟早长出「额度退还中」这一态</td></tr>
 *   <tr><td>{@code setUsed(...)} / {@code setRemaining(...)}</td>
 *       <td>只要能直接写 {@code used},{@code used <= granted} 这条不变式就<b>不再由结构保证</b>,
 *           而是由「记得带条件」保证</td></tr>
 *   <tr><td>{@code deductWithoutCall(...)}</td>
 *       <td>扣减只发生在 AI 端点内部、外部调用成功之后({@code 接口契约} §6.7.1)。
 *           一个不带流水的扣减方法,就是<b>那个不该存在的扣减端点的内部版本</b></td></tr>
 * </table>
 *
 * <h2>🔴 扣减没有端点</h2>
 *
 * {@code 接口契约} §6.7.1:有端点客户端就能<b>只调不扣、或只扣不调</b>。
 * 所以这个接口只被 {@code app} 的 AI 端点内部调用,不被任何 controller 直接暴露。
 */
public interface QuotaStore {

    Optional<QuotaPeriod> find(long userId, String periodYm, QuotaType type);

    /**
     * 发放 / 抬档。已存在则把 {@code granted} 抬到 {@code max(旧, 新)},🔴 <b>{@code used} 一格不动</b>。
     *
     * <p>到账时按新档位重发本周期额度({@code U7.6} §2.6)走的就是这一条。
     * <p>
     * 🔴 <b>只升不降</b>是 §2.6 那条不变式论证的前提之一:{@code granted} 下调是退款写法 A
     * 的动作,而 A/B/C 未选({@code M7} §6.2),<b>技术侧不替产品选</b>。
     */
    QuotaPeriod grant(long userId, String periodYm, QuotaType type, int granted);

    /**
     * 🔴 <b>一次原子写。</b>三步要么全成、要么全不成({@code M7} §2.3):
     *
     * <ol>
     *   <li>按 {@code (userId, endpoint, idempotencyKey)} 落 {@link AiCallLog}。
     *       撞唯一键且旧行 {@code SUCCESS} → 整体回滚,返回 {@link ConsumeResult.Replayed},<b>不扣</b>;
     *       撞唯一键且旧行 {@code FAILED} → <b>就地覆盖那一行</b>({@code 接口契约} §1.5)</li>
     *   <li>{@code used = used + 1} <b>且带条件 {@code used < granted}</b>。
     *       受影响 0 行 → 整体回滚,返回 {@link ConsumeResult.Exhausted},<b>不扣、不留流水</b></li>
     *   <li>提交</li>
     * </ol>
     *
     * 🔴 <b>绝不「先查再写」</b>:先查再写在两端并发时会各自读到「还有」,然后各自加一 ——
     * 那正是 {@code U7.1} §2.5 最后一行「两端并发用光 → 不会扣穿」要挡的东西。
     *
     * @param periodYm 🔴 <b>在最外层算一次,一路传下去</b>({@code M7} §2.5)。
     *                 不许在实现内部再算一次 —— 00:00:00 前后各算一次会落到两行上,
     *                 而那正好是「扣了两个月各一次」
     */
    ConsumeResult consume(long userId, String periodYm, QuotaType type, AiCallLog call);

    /**
     * 失败调用只留流水、不动 {@code used}。
     *
     * <p>返回的行允许被后来的一次成功覆盖(§2.7)。
     */
    void recordFailure(AiCallLog failedCall);

    /** 这个人一共留了几行流水 —— 「账单与扣减一一对应」那条判据的读口(§2.6)。 */
    long countCallsByUser(long userId);
}
