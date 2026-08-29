package com.kaodian.server.coverage;

import com.kaodian.server.collect.Touch;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 一个考点的五种状态。
 *
 * <h2>五个状态全部只由「有没有 / 几次 / 多久前」推出</h2>
 *
 * 01 §2.2 划的能力边界是:永不判断「对不对」。所以这里的推导只用三类输入 ——
 * <ul>
 *   <li><b>有没有</b>:这个考点下有没有记录</li>
 *   <li><b>几次</b>:练了几道、对了几道(<b>都是用户自己填的数</b>)</li>
 *   <li><b>多久前</b>:最近一次触达距今多久</li>
 * </ul>
 *
 * 没有任何一步需要知道某道题的正确答案,也没有任何模型参与判断。
 * {@link #WEAK} 用到的正确率是<b>用户输入的两个整数相除</b>,不是产品判出来的分数 ——
 * 这条区别是这个产品能不做教研的全部原因,界面上那句「正确率是你自己填的数」说的就是它。
 */
public enum NodeState {

    /** 空白 —— 没有任何记录。<b>这就是盲区</b>,是 {@code 骨架层 − 行为层} 的差集元素。 */
    EMPTY("空白"),

    /** 仅接触 —— 听过课、看过讲义,但一道题都没练。区别于「完全没碰过」。 */
    TOUCHED_ONLY("仅接触"),

    /** 生疏 —— 练过,但最近一次已经超过 {@link #RUSTY_AFTER} 没再碰。 */
    RUSTY("生疏"),

    /** 弱 —— 近期练过,但<b>用户自己填的</b>正确率低于 {@link #WEAK_BELOW}。 */
    WEAK("弱"),

    /** 稳 —— 近期练过,且用户自己填的正确率不低于 {@link #WEAK_BELOW}。 */
    STABLE("稳");

    /**
     * 超过这个时长没碰 → 生疏。
     * <p>纯时间判断,与答得怎么样无关。
     */
    public static final Duration RUSTY_AFTER = Duration.ofDays(30);

    /**
     * 用户自填正确率低于此值 → 弱。
     *
     * <p><b>这是一条显示分组的阈值,不是评分。</b> 它作用在用户敲进来的两个整数上,
     * 产品没有判过任何一道题。把它设成常量而不是模型输出,正是为了让这一点在代码里
     * 一眼可见 —— 任何时候这里出现「模型」「预测」「评估」,就是越过了 01 §2.2。
     */
    public static final double WEAK_BELOW = 0.60;

    private final String label;

    NodeState(String label) {
        this.label = label;
    }

    /** 界面上显示的中文名。 */
    public String label() {
        return label;
    }

    /** 是否计入覆盖度(即「有记录」)。空白之外都算碰过。 */
    public boolean covered() {
        return this != EMPTY;
    }

    /**
     * 由一个考点下的全部触达记录推出它的状态。
     *
     * @param touches 该考点下的记录,可以为空
     * @param now     判定基准时刻(注入而非 {@code Instant.now()},便于测试与回放)
     */
    public static NodeState derive(List<Touch> touches, Instant now) {
        if (touches == null || touches.isEmpty()) {
            return EMPTY;                                   // 有没有:没有
        }

        int practiced = 0;
        int correct = 0;
        Instant latest = null;

        for (Touch t : touches) {
            if (latest == null || t.occurredAt().isAfter(latest)) {
                latest = t.occurredAt();                    // 多久前
            }
            if (t.hasDrill()) {                             // 几次
                practiced += t.drill().practiced();
                correct += t.drill().correct();
            }
        }

        if (practiced == 0) {
            return TOUCHED_ONLY;                            // 碰过,但没练过
        }
        if (Duration.between(latest, now).compareTo(RUSTY_AFTER) > 0) {
            return RUSTY;                                   // 练过,但太久没碰
        }
        double accuracy = (double) correct / practiced;     // 用户自填的两个数相除
        return accuracy < WEAK_BELOW ? WEAK : STABLE;
    }
}
