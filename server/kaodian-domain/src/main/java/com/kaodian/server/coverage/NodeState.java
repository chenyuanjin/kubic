package com.kaodian.server.coverage;

/**
 * 一个考点<b>在覆盖度里的位置</b> —— 五个取值,<b>互斥且穷尽</b>
 * ({@code M3-骨架与覆盖度差集} §1.1)。
 *
 * <h2>🔴 它回答的不是「答得怎么样」</h2>
 *
 * 这不是一个屏幕状态机,也不是一把尺子。五个取值只由三件事推出来:
 * 这个节点<b>在不在本版骨架里</b>、<b>归没归档</b>、<b>有没有</b>计覆盖度的标签、
 * 用户<b>有没有</b>按过「我已经会了」。四个都是「有没有」,没有一个是「对不对」。
 *
 * <p>上一版的五态({@code EMPTY} / {@code TOUCHED_ONLY} / {@code RUSTY} / {@code WEAK} /
 * {@code STABLE})不是这五个的旧名字,两套是不同维度:{@code WEAK} / {@code STABLE}
 * 回答的是「答得怎么样」,正面撞红线一。<b>替换不是重命名,是把那三个态从状态机里拿掉</b> ——
 * 它们承载的事实改由字段承载:{@code lastTouchAt}(多久前,由端算天数)。
 * 而 {@code WEAK} / {@code STABLE} 背后的「练了几道 / 对了几道」<b>一并从响应里去掉</b>
 * (§7.4)。
 *
 * <h2>🔴 推导优先级写死,一行代码顺序定死一条产品不变量</h2>
 *
 * <pre>GONE  &gt;  ARCHIVED  &gt;  TOUCHED  &gt;  ASSERTED  &gt;  UNTOUCHED</pre>
 *
 * {@code TOUCHED} 必须排在 {@code ASSERTED} 前面,理由不是风格:
 * {@code U3.3} §2.4 把它写成集合关系 {@code 没碰过 ∪ 已经会了 = 没碰过},
 * 即 <b>{@code ASSERTED ⊆ 没碰过}</b>。反过来让 {@code ASSERTED} 优先的话,
 * 一个已经碰过的节点会从分子里掉出来 —— <b>点一下按钮就能让覆盖度下降</b>,
 * 而 {@code U3.6} §2.2 逐字写着断言之后三个数一个都不变。
 * <p>
 * 顺序写死之后,{@code ASSERTED ⊆ 没碰过} 从一条<b>要靠自觉的约定</b>变成一条
 * <b>结构上不可能被破坏的事实</b>:{@code ASSERTED} 这个取值的定义里就含着「不是 TOUCHED」。
 */
public enum NodeState {

    /**
     * 未触达 —— 未归档的骨架叶子节点,没有任何计覆盖度的标签,也没按过「我已经会了」。
     * <b>这就是盲区</b>,是 {@code 骨架层 − 行为层} 的差集元素。
     * <p>进分母 ✅ · 进分子 ❌ · 进榜 ✅ · 进 {@code assertedCount} ❌
     */
    UNTOUCHED,

    /**
     * 已触达 —— 该节点上有 ≥1 条计覆盖度的标签({@code TS-03} / {@code TS-07} / {@code TS-08},
     * 落在代码里是 {@code RecordTag#countsInCoverage()})。
     * <p>进分母 ✅ · 进分子 ✅ · 进榜 ❌ · 进 {@code assertedCount} ❌
     */
    TOUCHED,

    /**
     * 已断言 —— {@code UNTOUCHED} <b>且</b>用户声明过「我已经会了」。
     *
     * <p>🔴 它是 {@code 没碰过} 的<b>子集</b>,不是它的对立面:一个被断言的节点
     * 照样进分母、照样不进分子。断言唯一真正做的事是<b>让它不出现在「先补这几个」里</b>。
     * <p>进分母 ✅ · 进分子 ❌ · 进榜 ❌ · 进 {@code assertedCount} ✅
     */
    ASSERTED,

    /**
     * 已归档 —— 骨架归档。<b>同时退分子与分母</b>,单列计数。
     *
     * <p>🔴 归档是唯一一个<b>不用真学就能让覆盖度上升</b>的操作,所以它必须同时退两边:
     * 只退分子会让覆盖度下降,只退分母会让它上升,两种都是在编数。
     * <p>进分母 ❌ · 进分子 ❌ · 进榜 ❌ · 进 {@code assertedCount} ❌
     */
    ARCHIVED,

    /**
     * 不在本版骨架 —— 这个 {@code nodeCode} 在当前骨架版本里查不到。
     *
     * <p>骨架换版之后旧标签行指向的节点就是这一档。它<b>不进任何一个数</b>,
     * 也<b>不报错</b> —— 那是数据问题不是请求问题,报错会让一屏正常内容因为一条脏标签整个打不开。
     * <p>进分母 ❌ · 进分子 ❌ · 进榜 ❌ · 进 {@code assertedCount} ❌
     */
    GONE;

    /**
     * 五态推导的<b>唯一一处</b>。四个入参都是「有没有」,一个都不是「对不对」。
     *
     * <p>🔴 {@code if} 的顺序<b>就是</b>类注释里那条优先级链,别调换。
     * 把 {@code asserted} 提到 {@code touched} 前面,{@code ASSERTED ⊆ 没碰过}
     * 当场破掉,而且不会有任何一条断言变红 —— 只有覆盖度会在用户点按钮时下降。
     *
     * @param inSyllabus 这个 code 在当前骨架版本里查得到吗
     * @param archived   查得到的话,它归档了吗
     * @param touched    它上面有没有 ≥1 条<b>计覆盖度</b>的标签
     * @param asserted   用户有没有按过「我已经会了」
     */
    public static NodeState derive(boolean inSyllabus, boolean archived,
                                   boolean touched, boolean asserted) {
        if (!inSyllabus) {
            return GONE;
        }
        if (archived) {
            return ARCHIVED;
        }
        if (touched) {
            return TOUCHED;
        }
        return asserted ? ASSERTED : UNTOUCHED;
    }

    /** 进分母吗 —— {@code D = { n | level 3 ∧ ¬archived }}。 */
    public boolean inDenominator() {
        return this == UNTOUCHED || this == TOUCHED || this == ASSERTED;
    }

    /**
     * 进分子吗 —— {@code N = { n ∈ D | ∃ 计覆盖度的标签 }}。
     *
     * <p>🔴 只有 {@code TOUCHED} 一个。加一句 {@code || this == ASSERTED} 就是
     * 把补丁伪装成疗效:一个能靠点按钮刷高的覆盖度与没有覆盖度是一样的。
     */
    public boolean inNumerator() {
        return this == TOUCHED;
    }

    /**
     * 进「没碰过」吗 —— {@code B = D ∖ N},也就是差集本身。
     *
     * <p>🔴 {@code ASSERTED} 在这里面。这一行就是 {@code ASSERTED ⊆ 没碰过} 的落点。
     */
    public boolean inBlindSet() {
        return this == UNTOUCHED || this == ASSERTED;
    }
}
