package com.kaodian.server.collect;

import java.time.Instant;

/**
 * 行为层的一条触达记录 —— 「你碰过这个考点」。
 *
 * <h2>🔴 这个类里没有、也永远不会有存放学习内容的字段</h2>
 *
 * 没有 {@code content}、没有 {@code text}、没有 {@code question}、没有 {@code transcript}、
 * 没有 {@code imageUrl}。这不是「暂时不填」,是<b>结构上没有这个位置</b>。
 * <p>
 * 依据 01 §2.2「不碰内容」:机构的课程内容一概不存,只记<b>来源名与时间戳</b>。
 * 只要这条记录的形状里没有能装下内容的字段,即便以后有人想存也无处可放 —— 这是
 * docs/10 §5.1「不是不填,是不建这个列」在代码层的形态。
 * <p>
 * 语音转写的原文与拍照的原图都<b>不进入</b>这条记录:转写文本只用于识别考点,
 * 用完即弃;原图 base64 内联送识别一次后即删,不做云端存储(01 §2.3 / docs/09 坑二)。
 * 识别的产物只有一个 —— {@link #nodeCode()},即考点树里的一个节点。
 *
 * @param id         记录 id
 * @param nodeCode   挂到哪个考点。🔴 只接受考点树里已存在的 code,不接受自由文本标签(R-07)
 * @param sourceName 来源名,如「粉笔 · 资料分析系统班 L12」。只是个名字,不含该来源的任何内容
 * @param kind       这一笔是怎么记的
 * @param occurredAt 发生时间 —— 「多久前」的唯一依据
 * @param drill      做题记录;非做题类记录为 null
 */
public record Touch(
        String id,
        String nodeCode,
        String sourceName,
        TouchKind kind,
        Instant occurredAt,
        Drill drill
) {

    /**
     * 做题记录 —— 练了几道、对了几道。
     *
     * <h2>这两个数是用户自己填的,不是产品判的</h2>
     *
     * 01 §2.2 的能力边界是「只说有没有、几次、多久前,不判断对不对」。
     * 记录一个<b>用户自己输入的数字</b>属于「几次」;
     * 而去判断某道题答得对不对,属于「对不对」—— 后者本产品永不做。
     * <p>
     * 所以这里既没有判题逻辑,也没有标准答案,更没有任何模型参与。
     * 就是把用户敲进来的两个整数存下来。
     *
     * @param practiced 练了几道
     * @param correct   用户自己说对了几道
     */
    public record Drill(int practiced, int correct) {
        public Drill {
            if (practiced < 0 || correct < 0) {
                throw new IllegalArgumentException("题数不能为负");
            }
            if (correct > practiced) {
                throw new IllegalArgumentException("对的题数不能多于练的题数:" + correct + " > " + practiced);
            }
        }
    }

    public Touch {
        if (nodeCode == null || nodeCode.isBlank()) {
            throw new IllegalArgumentException("必须挂到一个考点上");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("必须有时间戳 —— 「多久前」全靠它");
        }
    }

    /** 这一笔是否包含做题。仅接触(听课/看讲义)没有做题数据。 */
    public boolean hasDrill() {
        return drill != null && drill.practiced() > 0;
    }
}
