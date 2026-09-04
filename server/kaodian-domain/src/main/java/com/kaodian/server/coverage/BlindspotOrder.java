package com.kaodian.server.coverage;

/**
 * 「先补这几个」的排序口径 —— <b>四个,没有第五个</b>({@code M3-骨架与覆盖度差集} §9.3)。
 *
 * <h2>🔴 为什么是闭集枚举而不是一个 String</h2>
 *
 * 契约写着「未知 {@code orderBy} → {@code 422 UNKNOWN_ORDER_BY},<b>不许静默按默认返回</b>」。
 * 「不许静默」这句话只有在取值是<b>封闭类型</b>时才守得住:接一个 String 再
 * {@code switch} + {@code default -> 默认口径},就是那句被禁的静默,而且不会有任何东西报错。
 *
 * <p>线上名字是 {@code snake_case}({@link #wireName()}),Java 侧是 {@code UPPER_SNAKE}。
 * 两边不同名是有意的:{@link #of} 是唯一的入口,谁想绕过闭集校验就得先绕过它。
 */
public enum BlindspotOrder {

    /** 近五年出现次数,多的在前。<b>没有出现次数记录的沉到末尾</b> —— 见 {@link #missingKeyFirst()}。 */
    RECENT5Y_COUNT("recent5y_count"),

    /**
     * 最近一次碰过的时刻,久的在前。
     *
     * <p>🔴 <b>从没碰过的排在最前</b>,不是最后:这一栏问的是「多久没碰了」,
     * 而从没碰过就是最久的那一档 —— 它正是差集的正主。
     */
    LAST_TOUCH_AT("last_touch_at"),

    /** 碰过几次,少的在前。 */
    TOUCH_COUNT("touch_count"),

    /** 骨架自然序 —— 树上从上到下的顺序。 */
    SYLLABUS_ORDER("syllabus_order");

    private final String wireName;

    BlindspotOrder(String wireName) {
        this.wireName = wireName;
    }

    /** 契约里那个 {@code snake_case} 名字;响应里回显的也是它。 */
    public String wireName() {
        return wireName;
    }

    /**
     * 这个口径下,<b>排序键缺失</b>的节点排在最前还是最后。
     *
     * <p>端靠这一点画分组分隔线:契约 §9.3「分组边界由排序键的 key 缺不缺唯一确定,
     * 服务端已经把它们排在该在的一端」。🔴 <b>所以不加 {@code group} / {@code section} 字段</b> ——
     * 加一个就是给同一个事实造第二个来源。
     */
    public boolean missingKeyFirst() {
        return this == LAST_TOUCH_AT;
    }

    /**
     * {@code snake_case} → 枚举。
     *
     * @return 不在闭集里时返回 {@code null} —— 调用方负责把它翻成 {@code 422 UNKNOWN_ORDER_BY}。
     *         🔴 <b>这里不许兜底成默认值</b>,理由见类注释
     */
    public static BlindspotOrder of(String wireName) {
        for (BlindspotOrder o : values()) {
            if (o.wireName.equals(wireName)) {
                return o;
            }
        }
        return null;
    }
}
