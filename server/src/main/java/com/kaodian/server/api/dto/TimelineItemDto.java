package com.kaodian.server.api.dto;

import com.kaodian.server.collect.Touch;
import com.kaodian.server.syllabus.Syllabus;

import java.time.Instant;

/**
 * 时间线上的一条 —— 「你什么时候、从哪个来源、以什么方式碰过哪个考点」。
 *
 * <h2>🔴 这里没有内容字段,一个都没有</h2>
 *
 * 没有 {@code content}、{@code text}、{@code transcript}、{@code imageUrl}。
 * 上游的 {@link Touch} 结构上就没有这些字段(01 §2.2 不碰内容),这里也不会凭空长出来。
 * <b>语音的转写文本用完即弃,原图送识别一次即删</b>(01 §2.3 / docs/09 坑二),
 * 它们从来没有进过任何一条记录。
 * <p>
 * 一条时间线看上去很像「笔记」,而笔记正是最容易被要求「能不能把原文也存一下」的地方。
 * 这条 DTO 的字段表就是那个要求的答复。
 *
 * <h2>{@code practiced} / {@code correct} 为什么可以在这里</h2>
 *
 * 它们是<b>用户自己敲进来的两个整数</b>,属于「几次」,不属于「对不对」(01 §2.2)。
 * 产品既没有判过题,也不知道任何一道题的答案。非做题类记录这两个字段是 {@code null}。
 *
 * @param kind      枚举名(VOICE / PHOTO / PASTE / DRILL / MANUAL),前端按它选图标
 * @param kindLabel 中文名,服务端给,前端不硬编码
 * @param nodeName  考点名,来自骨架树。同一个 nodeCode 改名时前端不用跟着改
 */
public record TimelineItemDto(
        String id,
        Instant occurredAt,
        String kind,
        String kindLabel,
        String sourceName,
        String nodeCode,
        String nodeName,
        String groupCode,
        String groupName,
        Integer practiced,
        Integer correct
) {
    /**
     * @param syllabus 用来把 nodeCode 翻成考点名与题型名。翻不出来时保留 code、名字给 {@code null} ——
     *                 这种情况意味着骨架树删过节点而历史记录还在,<b>不该导致整条时间线 500</b>。
     *                 (删除守则已经把这条路堵得很窄了:有记录的考点删不掉。
     *                 但历史数据文件、以及将来换存储时的边角情况仍然可能触发它,所以这里照样兜住)
     */
    public static TimelineItemDto from(Touch t, Syllabus syllabus) {
        // 🔴 用 nodeIncludingArchived 而不是 node:归档不该让时间线上的老记录变成无名氏。
        // 归档的语义是「这个考点不再使用」,不是「这段历史不存在了」。
        Syllabus.Node node = syllabus.nodeIncludingArchived(t.nodeCode());
        Syllabus.Group group = syllabus.groupOf(t.nodeCode());
        return new TimelineItemDto(
                t.id(),
                t.occurredAt(),
                t.kind() == null ? null : t.kind().name(),
                t.kind() == null ? null : t.kind().label(),
                t.sourceName(),
                t.nodeCode(),
                node == null ? null : node.name(),
                group == null ? null : group.code(),
                group == null ? null : group.name(),
                t.drill() == null ? null : t.drill().practiced(),
                t.drill() == null ? null : t.drill().correct());
    }
}
