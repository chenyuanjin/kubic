package com.kaodian.server.tagging;

import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.TagOrigin;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.syllabus.Syllabus;

import java.util.List;

/**
 * 一条记录的打标状态 —— <b>全库唯一的状态推导入口</b>({@code M2-打标管线与模型接入} §4.3)。
 *
 * <h2>🔴 推出来的,不落库</h2>
 *
 * 存一份就会与标签集合分叉:标签被丢弃了而状态字段还停在「已确认」,
 * 而两者之中<b>标签才是覆盖度真正读的那一份</b>。所以这里只有一个纯函数。
 *
 * <h2>🔴 「什么算未分类」全库只许有 {@link #isUnclassified} 一处</h2>
 *
 * {@code GET /records/unclassified/count} 的口径由这里算,{@code M3} 的 controller
 * <b>调这一个方法,不自己写谓词</b>。两边各算一次,迟早只有一边跟着状态表更新。
 * 判据是一行 grep({@code M2} §4.3)。
 *
 * <h2>{@code TS-08} 不在这个枚举里</h2>
 *
 * 它是<b>端上本地队列的态</b>(离线时点了确认,还没补传成功),服务端不存在这个态,
 * 也不进未分类计数。服务端凭空造一个 {@code TS-08} 只可能造错 ——
 * 它唯一的依据在端上那条队列里。
 */
public enum TagState {

    /** 待打标:记录已落地,分类还没触发(离线记的、批量补录的)。 */
    TS_00,

    /** 正在认:已触发,结果未回。 */
    TS_01,

    /** 待确认:有候选标签,还没人点过确认。<b>管线的出口就是这一格</b>。 */
    TS_02,

    /** 已确认:用户点了确认,标签是模型挑的。计覆盖度。 */
    TS_03,

    /** 已丢弃:标签全被丢掉了,<b>但记录仍然可见</b>。 */
    TS_04,

    /** 没对上:管线走完了,没能落下任何标签。 */
    TS_05,

    /** 待补:链路不通或许可拿不到。 */
    TS_06,

    /** 手动挂载:用户自己从树里选的。计覆盖度。 */
    TS_07,

    /** 挂载失效:已确认/手动挂载的考点被归档了(节点已退出分母,{@code R-49})。 */
    TS_09;

    /**
     * 记录级状态 —— 由<b>标签集合 + 最近一次尝试</b>推出来。
     *
     * <p>判断顺序照抄 {@code 打标与未分类} §三那张表,顺序本身是契约的一部分:
     * 「已确认的标签指向的节点已归档」要排在「至少一个已确认标签」<b>之前</b>,
     * 否则一个归档考点上的确认标签会显示成「已对上」,而它已经不在分母里了。
     *
     * @param tags     这条记录当前的全部<b>有效</b>标签({@code RecordTag.effectiveTagsOf} 的产物)
     * @param attempt  最近一次打标尝试;{@code null} = 还没触发过
     * @param syllabus 用来判「这个考点还在不在树里」;{@code null} 时跳过 {@link #TS_09} 那一档
     */
    public static TagState of(Touch touch, List<RecordTag> tags, TagAttempt attempt, Syllabus syllabus) {
        if (touch == null) {
            throw new IllegalArgumentException("状态是某一条记录的状态");
        }
        List<RecordTag> all = tags == null ? List.of() : tags;

        if (attempt != null && attempt.outcome() == TagAttempt.Outcome.RUNNING) {
            return TS_01;
        }

        RecordTag confirmed = null;
        boolean hasCandidate = false;
        for (RecordTag tag : all) {
            if (tag.discarded()) {
                continue;
            }
            if (tag.confirmedAt() != null) {
                // 归档的那一条优先:节点退出分母之后,「已对上」是一句错话。
                if (syllabus != null && syllabus.node(tag.nodeCode()) == null) {
                    return TS_09;
                }
                if (confirmed == null) {
                    confirmed = tag;
                }
            } else {
                hasCandidate = true;
            }
        }
        if (confirmed != null) {
            // 🔴 两格的区别只在显示,不在行为(§三 那张表逐字)。按 origin 分,不按谁调的接口分。
            return confirmed.origin() == TagOrigin.MANUAL ? TS_07 : TS_03;
        }
        if (hasCandidate) {
            return TS_02;
        }

        // 一条有效标签都没有了 —— 这一段才轮得到「为什么没对上」。
        if (attempt != null) {
            switch (attempt.outcome()) {
                case UNAVAILABLE, QUOTA_EXHAUSTED -> {
                    return TS_06;
                }
                case NO_MATCH, NOT_RECALLED, NO_MATERIAL, SYLLABUS_EMPTY -> {
                    return TS_05;
                }
                default -> {
                    // SUGGESTED / ALREADY_TAGGED 落到这里,说明那条标签事后被丢弃了。
                }
            }
        }
        return all.isEmpty() ? TS_00 : TS_04;
    }

    /**
     * 未分类计数的唯一口径 —— <b>{@code TS-05} ∪ {@code TS-06}</b>。
     *
     * <p>🔴 {@link #TS_00} / {@link #TS_01} 不算:它们还没走完,算进去这个数会随后台重试
     * 自己跳动,<b>而用户什么都没做</b>。
     *
     * <p>平面是<b>记录平面,单位「条」</b>;覆盖度那三个数是「个(节点)」。
     * 两者物理隔在两个端点上,不合进同一响应 ——「放进同一个响应,早晚有人把它们加起来」。
     */
    public static boolean isUnclassified(TagState state) {
        return state == TS_05 || state == TS_06;
    }
}
