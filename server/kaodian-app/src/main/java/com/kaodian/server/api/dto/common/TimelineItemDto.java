package com.kaodian.server.api.dto.common;

import com.kaodian.server.collect.Touch;
import com.kaodian.server.syllabus.Syllabus;

import java.time.Instant;

/**
 * 一条原始记录 —— 「你什么时候、从哪个来源、以什么方式碰过哪个考点」。
 *
 * <h2>⚠ 名字里的 Timeline 已经不指 {@code /api/timeline} 了</h2>
 *
 * 这条 DTO 现在只出现在<b>采集线</b>的响应里({@code GET /api/records}、
 * {@code POST /api/records} 及其批量版)。{@code /api/timeline} 改成 §6.4 的聚合视图之后
 * 一条 {@code items} 都不出了,见 {@link TimelineResponse}。
 * <p>
 * <b>没有跟着改名</b>,是因为改名要动 {@code RecordController} 与前端的类型定义,
 * 而那两处正被别的改动占着 —— 一次纯改名的提交混进去,得到的是一份没人看得清的 diff。
 * 记在这里,别让下一个人以为它还挂在那个端点上。
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
