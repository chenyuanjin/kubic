package com.kaodian.server.api.dto;

import com.kaodian.server.syllabus.Syllabus;

/**
 * 考点管理视角下的一个题型。
 *
 * <p>{@link #nodeCount} 与 {@link #archivedNodeCount} 分开给,是因为
 * <b>「能不能删这个题型」看的是两者之和</b>:归档的考点上照样挂着记录,
 * 连带删除会一次性造出一批孤儿。界面要能解释清楚「明明看着是空的,为什么删不掉」。
 *
 * @param nodeCount         未归档的考点数(参与差集的那些)
 * @param archivedNodeCount 已归档的考点数
 */
public record SyllabusGroupDto(
        String code,
        String name,
        int nodeCount,
        int archivedNodeCount
) {
    public static SyllabusGroupDto from(Syllabus.Group g) {
        return new SyllabusGroupDto(g.code(), g.name(),
                g.activeNodes().size(), g.archivedNodes().size());
    }
}
