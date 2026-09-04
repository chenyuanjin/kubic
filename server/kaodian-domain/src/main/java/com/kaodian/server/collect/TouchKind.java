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
 *
 * <h2>🔴 这里刻意<b>没有</b>一个 {@code consumesAiQuota} 字段({@code M1-记录采集与离线补传} §2.5)</h2>
 *
 * 它曾经在,取值 {@code VOICE}/{@code PHOTO}=true、其余 false,零个消费者。删它的理由不是「没人用」,
 * 是<b>它说的话是错的</b>:它把「记录类型」与「有没有调外部模型」画上了等号,而这两件事是分开的 ——
 *
 * <ul>
 *   <li>一条 {@code PHOTO} 记录,用户自己从树里挑了考点、根本没走 {@code POST /records/{id}/image} ——
 *       它一次外部模型调用都没有,而那个字段说它有</li>
 *   <li>一条 {@code MANUAL} 记录,用户点了 {@code POST /records/{id}/tags/suggest} ——
 *       它有一次外部调用,而那个字段说它没有</li>
 * </ul>
 *
 * <b>「哪一次调用是外部模型调用」由调用点决定,不由记录类型决定。</b>
 * 留着它的真实代价是:下一个实现额度的人打开 {@code CaptureService},看到 {@code request.kind().consumesAiQuota()}
 * 触手可及,<b>扣额度就会被写进记录写入路径</b> —— 而「记录永不失败」当场失守,还是以一种
 * 「代码读起来很合理」的方式。额度的归属归 {@code M7},落在调用点上,不落在这个枚举上。
 */
public enum TouchKind {

    /** 语音记 —— ASR 转文字,再从考点树里闭集匹配。转写文本不留存。 */
    VOICE("语音记"),

    /** 拍照记 —— 原图 base64 内联送多模态一次,直接出考点 code。原图不上云、识别完即删。 */
    PHOTO("拍照记"),

    /** 粘一段 —— 用户贴一段文字,自己从树里挑考点。文字本身不留存。 */
    PASTE("粘一段"),

    /** 记做题 —— 练了几道、对了几道,两个数字都是用户自己填的。 */
    DRILL("记做题"),

    /** 手动挂载 —— 直接在树上点一个考点说「这个我碰过」。 */
    MANUAL("手动挂");

    private final String label;

    TouchKind(String label) {
        this.label = label;
    }

    /** 界面上显示的中文名。 */
    public String label() {
        return label;
    }
}
