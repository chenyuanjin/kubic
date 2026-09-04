package com.kaodian.server.coverage;

/**
 * 「先补这几个」的四选一段控 —— {@code M3-骨架与覆盖度差集} §9.3。
 *
 * <p>🔴 它是一个<b>选择器</b>,不是一个附加条件:每一档<b>恰好</b>对应 {@link NodeState}
 * 的一个取值(或它们的并),所以「已断言节点榜排除」与「{@code filter=asserted} 时反过来只列它们」
 * 是<b>同一条规则的两次读法</b>,不是两条要各自维护的规则。
 *
 * <p>归档节点一档都不进 —— {@link NodeState#ARCHIVED} 不在任何一档的取值集合里。
 */
public enum BlindspotFilter {

    /** 全部进分母的节点。🔴 已断言的只在这一档和 {@link #ASSERTED} 里出现,默认档里不出现。 */
    ALL("all"),

    /** 🔴 <b>默认档</b> —— 没碰过、也没说过「我已经会了」。差集的正主。 */
    UNTOUCHED("untouched"),

    /** 碰过的。 */
    TOUCHED("touched"),

    /** 说过「我已经会了」的 —— {@code U3.6} 那份「我说会了的清单」。 */
    ASSERTED("asserted");

    private final String wireName;

    BlindspotFilter(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** 这个节点进不进这一档。 */
    public boolean accepts(NodeState state) {
        return switch (this) {
            case ALL -> state.inDenominator();
            case UNTOUCHED -> state == NodeState.UNTOUCHED;
            case TOUCHED -> state == NodeState.TOUCHED;
            case ASSERTED -> state == NodeState.ASSERTED;
        };
    }

    /**
     * {@code snake_case} → 枚举。
     *
     * @return 不在闭集里时返回 {@code null} —— 调用方翻成 {@code 400 INVALID_ARGUMENT}
     *         (🔴 <b>不新起码</b>:界面是四选一段控,用户选不出非法值,走到这里是端上的 bug,
     *         而 bug 不是一档)
     */
    public static BlindspotFilter of(String wireName) {
        for (BlindspotFilter f : values()) {
            if (f.wireName.equals(wireName)) {
                return f;
            }
        }
        return null;
    }
}
