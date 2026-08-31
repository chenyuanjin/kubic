package com.kaodian.server.api.dto.record;

import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.syllabus.Syllabus;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 一条标签的只读投影 —— docs/技术架构 §5.2 的 {@code record_tag} 出到接口上。
 *
 * <h2>🔴 这里没有一个能装下标签文字的位置</h2>
 *
 * {@code nodeName} / {@code groupName} 是<b>从骨架树里反查出来的</b>,不是标签自带的 ——
 * 标签本身只有一个 {@code nodeCode}。区别要紧:反查出来的名字改了树就跟着变,
 * 而标签自带的名字会在树改名之后<b>留在库里变成一份机构措辞的副本</b>(R-07)。
 *
 * <h2>{@code origin} 出的是机器值,没有配一个中文 label</h2>
 *
 * 理由在 {@link com.kaodian.server.collect.TagOrigin} 上:它是算准确率用的来源标记,
 * 不是给用户看的状态。配了中文名,下一步就是界面上出现「这条是自动的」,
 * 再下一步就是有人为了让提示好看去改它 —— 而它<b>写入后不可变</b>。
 *
 * @param countsInCoverage 这条算不算进覆盖度。判据只有 {@code discarded}(docs/技术架构 §6.4),
 *                         <b>不包括「确认过没有」</b> —— 见 {@link RecordTag#countsInCoverage}
 * @param primary          是不是采集那一刻挂上的那条。它不在库里存着,由记录推出来
 */
public record TagDto(

        @Size(max = 64)
        String id,

        @Size(max = 64)
        String recordId,

        @Size(max = 64)
        String nodeCode,

        // 名字来自骨架树,写入口是 FileSyllabusStore.MAX_NAME_LENGTH = 40。
        // 这里再写一遍 40 是形状声明(响应体不过 Validator),不是第二处校验。
        @Size(max = 40)
        String nodeName,

        @Size(max = 64)
        String groupCode,

        @Size(max = 40)
        String groupName,

        double confidence,

        @Size(max = 16)
        String origin,

        Instant confirmedAt,
        boolean discarded,
        boolean countsInCoverage,
        boolean primary
) {

    /**
     * @param syllabus 用来反查名字。<b>查含归档的那张表</b>
     *                 ({@link Syllabus#nodeIncludingArchived}) —— 归档不该让一条老标签变成无名氏,
     *                 与 {@code TimelineItemDto} 的处理一致
     */
    public static TagDto from(RecordTag tag, Syllabus syllabus) {
        Syllabus.Node node = syllabus.nodeIncludingArchived(tag.nodeCode());
        Syllabus.Group group = syllabus.groupOf(tag.nodeCode());
        return new TagDto(
                tag.id(),
                tag.recordId(),
                tag.nodeCode(),
                node == null ? null : node.name(),
                group == null ? null : group.code(),
                group == null ? null : group.name(),
                tag.confidence(),
                tag.origin().wireName(),
                tag.confirmedAt(),
                tag.discarded(),
                tag.countsInCoverage(),
                tag.primary());
    }
}
