package com.kaodian.server.api.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 一次补标的答复 —— docs/10 §6.3:「响应是 {@code nodeId + confidence} 或 {@code NO_MATCH}」。
 *
 * <h2>🔴 全部结果都是 200,包括「模型挂了」</h2>
 *
 * {@code RecordController} 里「识别不可用」映射成 503,因为在那条路上<b>记录会落不下去</b>。
 * 这里不一样:<b>记录早就在库里了</b>,补标失败什么都没损坏,用户还可以手动挂载。
 * docs/13 §1.5 那句「降级方向是『少功能』,不是『少记录』」落到状态码上就是这条区别 ——
 * 回 503 会让前端把它当成一次失败去重试,而它没有失败,它只是这次没认出来。
 * <p>
 * 于是五种结局全部由 {@code outcome} 承担,{@code message} 是给界面直接用的那句话。
 * 合并成一句笼统的提示就没法说清下一步:「自己从树里挑一个」和「稍后重试」是两回事
 * ({@code RecognitionUnavailableException} 的类注释写的就是这件事)。
 *
 * @param outcome        {@code SUGGESTED} / {@code ALREADY_TAGGED} / {@code NOT_RECALLED} /
 *                       {@code NO_MATERIAL} / {@code NO_MATCH} / {@code UNAVAILABLE}
 * @param confidence     模型自报的分。<b>没匹配上也带着它</b> ——
 *                       「0.42 分被阈值丢掉」和「什么都没认出来」得能分开
 * @param candidateCount 这次召回出了几个候选。<b>0 表示压根没调模型</b>
 *                       (docs/13 §1.3:「召回不出来就不调模型,调了也只能瞎猜」)
 * @param tag            落下的那条标签;没落下时为 {@code null}
 */
public record SuggestTagResponse(

        @Size(max = 32)
        String outcome,

        @Size(max = 120)
        String message,

        double confidence,
        int candidateCount,

        TagDto tag,
        List<TagDto> tags,
        NodeDetailDto node,
        SummaryDto summary
) {
}
