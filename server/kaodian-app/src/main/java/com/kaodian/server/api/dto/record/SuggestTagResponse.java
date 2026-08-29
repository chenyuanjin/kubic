package com.kaodian.server.api.dto;

import com.kaodian.server.collect.TaggingService;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 一次补标的答复 —— docs/10 §6.3:「响应是 {@code nodeId + confidence} 或 {@code NO_MATCH}」。
 *
 * <h2>两个端点共用这一个形状</h2>
 *
 * {@code POST /records/{id}/tags/suggest}(§6.3)与 {@code POST /records/{id}/image}(§6.2)
 * 走的是<b>同一条打标管线</b>({@code TaggingService.suggest} 的四段),区别只在于
 * <b>手里有没有可送进模型的素材</b>:前者没有(所以常态是 {@code NO_MATERIAL}),
 * 后者带着这次上传的原图。
 * <p>
 * 同一件事的答复形状不同的话,前端就得为它写两套渲染,而其中一套迟早跟不上另一套。
 * 所以这里也是那个端点的答复,{@link #messageFor} 那句话同样只写在这一处。
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

    /**
     * 六种结局各说各的话。
     *
     * <h2>为什么措辞在这里,而不在枚举上</h2>
     *
     * {@code TagOrigin} 那段说过为什么标签侧的枚举不带中文 label —— 一旦枚举带上给用户看的字,
     * 就会有人为了让提示好看去改枚举本身,而其中一个是不可变的。措辞属于接口层。
     *
     * <h2>为什么在这里而不是在某个控制器里</h2>
     *
     * 走这条管线的端点<b>有两个</b>({@code /tags/suggest} 与 {@code /image},见类注释)。
     * 措辞写在其中一个控制器里,另一个就只能抄一遍 —— 而抄出来的两份迟早会说两句不一样的话,
     * 用户看到的就是「同一个结果,换个入口说法变了」。
     */
    public static String messageFor(TaggingService.Outcome outcome) {
        return switch (outcome) {
            case SUGGESTED -> "识别挑了一个考点,请确认或丢弃。";
            case ALREADY_TAGGED -> "这个考点已经挂在这条记录上了,没有重复挂。";
            case NOT_RECALLED -> "来源名里没有可用线索,没有候选可送 —— 请自己从树里挑一个考点。";
            case NO_MATERIAL -> "这条记录没有可再次识别的素材(原图与转写都不留存),请自己从树里挑一个考点。";
            case NO_MATCH -> "没认出来 —— 请自己从树里挑一个考点。";
            case UNAVAILABLE -> "识别服务暂时不可用,可以稍后重试,也可以自己从树里挑一个考点。";
        };
    }
}
