package com.kaodian.server.billing;

/**
 * 一个人、一个自然月、一个池子的额度账本行({@code M7-额度与订单} §2.2)。
 *
 * <h2>🔴 不变式:{@code 0 <= used <= granted}</h2>
 *
 * 由 {@link QuotaStore#consume} 的<b>条件更新</b>保证 —— {@code used} 的唯一增长路径带着
 * {@code AND used < granted},受影响 0 行即视为耗尽。所以 {@link #remaining()}
 * <b>在结构上取不到负数</b>,而不是靠调用方各自记得判一次。
 *
 * <h2>组合唯一:{@code (userId, periodYm, quotaType)}</h2>
 *
 * 同一人同一月同一类只有一行。
 *
 * <p>🔴 <b>这个 record 里没有 prompt、没有答案、没有图片、没有 {@code nodeCode}</b> ——
 * 红线 4(库里不存在能装下题干的字段)在这一层的形态就是「本来就没有能装的地方」,
 * 不需要为它加任何例外({@code M7} §2.2)。
 *
 * @param userId    {@code B0-2}:{@code long},起始 10001,{@code 0} 不是合法值
 * @param periodYm  {@code "2026-09"},自然月。时区裁定见 {@code BillingProperties#zone}
 * @param quotaType 闭集两值
 * @param granted   发放时写进这一行的数字,<b>不是编译期常量</b>
 * @param used      已用。见上面的不变式
 */
public record QuotaPeriod(
        long userId,
        String periodYm,
        QuotaType quotaType,
        int granted,
        int used) {

    /**
     * 余量 —— 🔴 <b>派生值,不是存储列</b>({@code M7} §6.3 / §8.3)。
     *
     * <h2>那个 {@code max} 是一层可见的兜底,不是一次修复</h2>
     *
     * 扣减路径上它<b>永远不生效</b>({@code M7} §2.6 论证 5:条件更新保证 {@code used <= granted})。
     * 它唯一会生效的场景是退款写法 A 把 {@code granted} 下调到 {@code used} 之下 ——
     * 🔴 <b>所以它不能被当成「选 A 也没关系」的理由</b>:它让界面不显示负数,
     * <b>不让账实相符</b>。若最终选 A,要一起补的是「下调 {@code granted} 时怎么处置
     * {@code used > granted} 的行」,那是一次契约变更,不是一次实现细节({@code M7} §契约增量 9)。
     */
    public int remaining() {
        return Math.max(granted - used, 0);
    }

    /** 还能不能扣一次。🔴 <b>它是预检,不是许可</b> —— 真正的判定在 {@link QuotaStore#consume} 的条件更新里。 */
    public boolean hasRemaining() {
        return used < granted;
    }
}
