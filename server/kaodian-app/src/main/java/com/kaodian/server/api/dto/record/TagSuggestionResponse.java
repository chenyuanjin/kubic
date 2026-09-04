package com.kaodian.server.api.dto.record;

import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.tagging.TaggingService.Suggestion;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/v1/records/{recordId}/tags/suggest} 的成功响应 —— {@code M2} §9.1。
 *
 * <h2>🔴 两种形态,二选一,不存在第三种</h2>
 *
 * {@code state} 只会是 {@code MATCHED} 或 {@code NO_MATCH}。
 * 「无匹配」是<b>一个明确取值</b>,不是空数组、不是 {@code null}、不是 200 空体 ——
 * 那三种写法都要求端去猜「是没对上,还是接口坏了」,而两者的下一步完全不同。
 *
 * <h2>🔴 这个 record 里没有一个字段能装下自由文本</h2>
 *
 * 没有 {@code label}、{@code name}、{@code tagName}、{@code reason}、{@code matchedKeyword},
 * 也没有兜底的「最接近的一个」。这是 {@code R-07} 闭集打标在接口层的形态:
 * <b>无论模型输出什么,自由生成的标签没有地方放</b>(判据是编译期的 —— 字段不存在)。
 *
 * <h2>🔴 也没有 {@code confidence}</h2>
 *
 * 阈值裁决({@code 0.75})在服务端做完了。端上拿到分数唯一能做的事就是自己再判一次,
 * 而那一判必然比服务端松 —— 没有人会在端上把阈值调高。
 *
 * @param state          {@code "MATCHED"} | {@code "NO_MATCH"}
 * @param candidates     🔴 <b>本次候选集全集,两种形态下都必须返回</b> —— 端靠它自行判定
 *                       {@code selectedNodeId} 在不在集内({@code I-3} 在客户端的第二道校验)。
 *                       长度 > {@link #CONTRACT_CANDIDATE_CAP} 即契约违规
 * @param selectedNodeId {@code MATCHED} 时必填,且必须是 {@code candidates} 里某个 {@code nodeId}
 * @param tagId          {@code MATCHED} 时必填,供 confirm / discard / restore 使用
 * @param remaining      就地更新余量。<b>内部形状归 {@code M7}</b>,本模块只透传 ——
 *                       ⚪ {@code M7} 落地之前它恒为空对象(登记为 {@code M2-G3},见交付说明)
 */
public record TagSuggestionResponse(

        @Size(max = 16)
        String state,

        List<CandidateDto> candidates,

        @Size(max = 64)
        String selectedNodeId,

        @Size(max = 64)
        String tagId,

        Map<String, Object> remaining
) {

    /** 有候选并落了标签。 */
    public static final String MATCHED = "MATCHED";

    /**
     * 没对上 —— 🔴 <b>召回为空、没有素材、阈值不过、出口自检降级四种都合进这一个值</b>。
     *
     * <p>wire 上合并是产品裁定({@code U2.3} §2.5):区分了也没有一个用户动作不同。
     * 库里分开是为了 {@code C-1} 那次抽样人工复核 —— 见 {@code TagAttempt.Outcome}。
     */
    public static final String NO_MATCH = "NO_MATCH";

    /**
     * 契约违规阈 —— <b>12,不是 20</b>({@code 接口契约} §4.1,同时关闭 {@code T-10})。
     *
     * <p>🔴 服务端召回上限是 10,所以 {@code 10 ≤ 12} 恒成立,这道闸在服务端<b>永远不触发</b>
     * —— 这不是冗余,它是给端上用的:端不知道服务端今天是 10,
     * 它要防的是「服务端某天被换成了别的东西」。<b>一道锁失效不该导致整条线失守。</b>
     */
    public static final int CONTRACT_CANDIDATE_CAP = 12;

    public static TagSuggestionResponse from(Suggestion suggestion, Syllabus syllabus) {
        List<CandidateDto> candidates = suggestion.candidates().stream()
                .map(c -> CandidateDto.from(c, syllabus))
                .toList();
        RecordTag tag = suggestion.tag();
        return new TagSuggestionResponse(
                tag == null ? NO_MATCH : MATCHED,
                candidates,
                tag == null ? null : tag.nodeCode(),
                tag == null ? null : tag.id(),
                // ⚪ M7 未落地:形状归它,本模块一个键都不自己发明。
                Map.of());
    }

    /** 候选集是不是超了契约那道闸。<b>给测试用</b> —— 服务端超了就是服务端错了。 */
    public static boolean violatesContractCap(List<VisionTagger.Candidate> candidates) {
        return candidates.size() > CONTRACT_CANDIDATE_CAP;
    }
}
