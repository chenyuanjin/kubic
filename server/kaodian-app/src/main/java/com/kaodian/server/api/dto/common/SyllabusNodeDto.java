package com.kaodian.server.api.dto.common;

import com.kaodian.server.syllabus.Syllabus;

/**
 * 考点管理视角下的一个考点 —— <b>骨架侧的事实 + 一个 {@link #recordCount}</b>。
 *
 * <h2>为什么这里有 recordCount,而查询用的 {@link NodeDto} 没有</h2>
 *
 * 因为管理界面在按下「删除」之前必须先知道<b>上面挂着几条记录</b>。
 * 删除守则是:有记录就不许删,并且要说得出具体数字。让界面在弹框之前就能显示
 * 「这个考点上挂着 3 条记录」,比等到 409 回来再解释要诚实得多。
 *
 * <h2>🔴 这里同样没有讲解、没有难度、没有掌握度</h2>
 *
 * 管理界面能改的只有:名字、所属题型、近五年频次、顺序、归档与否。
 * <b>没有一个字段涉及「这题该怎么做」或者「你掌握得怎么样」</b>(决策记录 §2.2)。
 *
 * @param archived    归档的考点退出差集(分母分子同时少一个),但 code 与历史记录都还在
 * @param recordCount 这个考点上挂着几条行为层记录。删除守则的那个数
 */
public record SyllabusNodeDto(
        String code,
        String name,
        String groupCode,
        String groupName,
        int recent5yCount,
        boolean archived,
        int recordCount
) {
    public static SyllabusNodeDto of(Syllabus.Node node, Syllabus.Group group, int recordCount) {
        return new SyllabusNodeDto(
                node.code(), node.name(),
                group == null ? null : group.code(),
                group == null ? null : group.name(),
                node.recent5yCount(), node.archived(), recordCount);
    }
}
