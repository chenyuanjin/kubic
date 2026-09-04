package com.kaodian.server.api.dto.record;

import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.Syllabus;
import jakarta.validation.constraints.Size;

/**
 * 一次召回里的一个候选 —— {@code M2-打标管线与模型接入} §9.1。
 *
 * <h2>🔴 只有这两个字段,没有第三个</h2>
 *
 * 没有 {@code confidence}(阈值裁决在服务端做完了,端上没有任何理由拿到它 ——
 * 拿到了就会有人在端上「稍微放宽一点」),没有 {@code reason}、没有 {@code matchedKeyword}、
 * 没有「最接近的一个」。<b>加任何一个能装下自由文本的字段,R-07 的类型层保护当场绕过</b>。
 *
 * <h2>{@code nodeId} 是 wire 名,实体里那个字段叫 {@code nodeCode}</h2>
 *
 * 同一个值的两个名字,映射写在这一处({@code M2} §十二 冲突 1)。
 * 改实体字段名要动六个类,而 wire 名已在两份产品文档里被逐字引用 —— <b>改代价小的那一侧</b>。
 * ⚠️ 值域那一格没有收:{@code 接口契约} §1.1 说「标识一律 int64」,而 {@code nodeCode}
 * 今天是任意 ≤64 字符串。登记为 {@code M2-G2},归骨架侧裁。
 */
public record CandidateDto(

        @Size(max = 64)
        String nodeId,

        @Size(max = 120)
        String path
) {

    /**
     * @param syllabus 用来拼完整章节路径。<b>查含归档的那张表</b> ——
     *                 归档不该让一个候选变成无名氏(与 {@code TagDto} 的处理一致)
     */
    public static CandidateDto from(VisionTagger.Candidate candidate, Syllabus syllabus) {
        Syllabus.Group group = syllabus.groupOf(candidate.code());
        String subject = syllabus.subject() == null ? null : syllabus.subject().display();
        StringBuilder path = new StringBuilder();
        if (subject != null && !subject.isBlank()) {
            path.append(subject).append(" / ");
        }
        if (group != null) {
            path.append(group.name()).append(" / ");
        }
        path.append(candidate.name());
        return new CandidateDto(candidate.code(), path.toString());
    }
}
