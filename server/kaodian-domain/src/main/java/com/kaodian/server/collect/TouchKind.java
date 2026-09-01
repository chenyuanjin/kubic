package com.kaodian.server.collect;

/**
 * 一笔记录是怎么记下来的。
 *
 * <h2>这个枚举描述的是「怎么记的」,不是「记了什么」</h2>
 *
 * 五种方式产出的<b>结果完全一样</b> —— 都只是「某个考点 + 某个来源 + 某个时刻」。
 * 语音走 ASR、拍照走多模态闭集分类,但它们的产物都只是一个考点 code:
 * 转写文本用完即弃,原图送识别一次后即删(决策记录 §2.3 / docs/data/识别链路选型.md 坑二),
 * 两者都不会落进 {@link Touch} —— 那里根本没有能装下它们的字段。
 *
 * <h2>AI 与手动的唯一区别是【谁挑的考点】,不是能记什么</h2>
 *
 * {@link #VOICE} / {@link #PHOTO} 由模型从考点树里挑(闭集分类,docs/data/识别链路选型.md 坑一);
 * {@link #PASTE} / {@link #DRILL} / {@link #MANUAL} 由用户自己从树里挑。
 * <b>两条路都只能挑树里已有的节点,谁都不能新建考点。</b>
 * <p>
 * 这条等价关系是商业模型的地基:额度用尽时停掉的只是 AI 那两种,
 * 手动三种永远可用 —— 「额度用尽 ≠ 记不了」(docs/product/商业化与额度设计.md §二)。
 * 记录动作本身永不失败(docs/execution/INDEX.md §1.3.7)。
 */
public enum TouchKind {

    /** 语音记 —— ASR 转文字,再从考点树里闭集匹配。转写文本不留存。 */
    VOICE("语音记", true),

    /** 拍照记 —— 原图 base64 内联送多模态一次,直接出考点 code。原图不上云、识别完即删。 */
    PHOTO("拍照记", true),

    /** 粘一段 —— 用户贴一段文字,自己从树里挑考点。文字本身不留存。 */
    PASTE("粘一段", false),

    /** 记做题 —— 练了几道、对了几道,两个数字都是用户自己填的。 */
    DRILL("记做题", false),

    /** 手动挂载 —— 直接在树上点一个考点说「这个我碰过」。 */
    MANUAL("手动挂", false);

    private final String label;
    private final boolean consumesAiQuota;

    TouchKind(String label, boolean consumesAiQuota) {
        this.label = label;
        this.consumesAiQuota = consumesAiQuota;
    }

    /** 界面上显示的中文名。 */
    public String label() {
        return label;
    }

    /**
     * 是否消耗 AI 录入额度。
     *
     * <p>只有真正调用了外部模型的方式才消耗 —— 收费收的是替用户花出去的模型钱,
     * 不是产品价值本身(docs/product/商业化与额度设计.md §三)。手动记录永远不消耗。
     */
    public boolean consumesAiQuota() {
        return consumesAiQuota;
    }
}
