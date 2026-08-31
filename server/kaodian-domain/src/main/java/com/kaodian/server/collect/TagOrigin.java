package com.kaodian.server.collect;

import java.util.Locale;

/**
 * 一条标签<b>从哪来</b> —— 不是它现在什么状态。
 *
 * <h2>🔴 写入后不可变(docs/技术架构 §5.2 {@code record_tag} 那一行)</h2>
 *
 * 契约原文:「{@code origin} 记的是这条标签从哪来,不是它现在什么状态 ——
 * 用户确认只写 {@code confirmed_at},不把 {@code auto} 改成 {@code manual}。
 * 改了,{@code 1.2.5.2} 那套准确率口径(标对的/标了的)在真实数据上就再也算不出来了。」
 * <p>
 * 这句话的分量值得说透:准确率的分母是<b>模型标了多少条</b>,分子是<b>其中用户认可了多少条</b>。
 * 一旦「用户确认」这个动作把 {@code auto} 改写成 {@code manual},被认可的那些就从分母里消失了 ——
 * <b>剩下的分母全是模型标错的,准确率恒等于 0,而且没有任何一处会报错。</b>
 * 这不是数据变脏,是这个指标彻底算不出来,且事后无法从库里补救。
 *
 * <h2>为什么这个枚举上没有 label 字段</h2>
 *
 * 别处的枚举({@code TouchKind}、{@code NodeState})都带一个中文 {@code label},
 * 因为它们要显示给用户看。{@code origin} 不是给用户看的 —— 它是<b>算指标用的机器值</b>。
 * 给它加一个「自动识别 / 手动挂载」的中文名,下一步就是界面上出现「这条是自动的」这种提示,
 * 再下一步就是有人为了让提示好看而去改它。<b>没有显示需求,就不给显示留位置。</b>
 */
public enum TagOrigin {

    /** 模型从候选集里挑的。{@code confidence} 是模型自报的分,已经过了阈值与出口自检。 */
    AUTO,

    /**
     * 用户自己从树里挑的 —— 采集时挑的,或事后手动挂上去的。
     *
     * <p>手动标签<b>没有「有多确定」这回事</b>,所以它的 {@code confidence} 恒为
     * {@link RecordTag#MANUAL_CONFIDENCE},由构造器强制。这不是为了好看:
     * 允许手动标签带一个模型分,就等于允许把一次识别的结果<b>换个 origin 存进来</b>,
     * 而那正好是上面那条「准确率再也算不出来」的另一种走法。
     */
    MANUAL;

    /**
     * 落库与出接口时的写法 —— 契约里是小写的 {@code auto} / {@code manual}(docs/技术架构 §5.2)。
     *
     * <p>写成方法而不是给枚举加一个 {@code String wire} 字段,是上面「不给显示留位置」的同一条:
     * 字段会被人拿去装别的,方法不会。
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 反查。认不出来就抛 —— 一个存坏了的 origin 不该被悄悄当成 manual。 */
    public static TagOrigin ofWireName(String wire) {
        for (TagOrigin origin : values()) {
            if (origin.wireName().equals(wire)) {
                return origin;
            }
        }
        throw new IllegalArgumentException("不认识的标签来源:" + wire + " —— 只有 auto 与 manual");
    }
}
