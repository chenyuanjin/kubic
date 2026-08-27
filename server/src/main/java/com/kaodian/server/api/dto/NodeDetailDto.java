package com.kaodian.server.api.dto;

import com.kaodian.server.coverage.CoverageService.NodeCoverage;

import java.time.Instant;
import java.util.List;

/**
 * 考点详情 —— 骨架侧的统计 + 我的触达情况。
 *
 * <h2>🔴 没有讲解字段(R-05)</h2>
 *
 * 点开一个考点,这个产品能告诉你的全部是:它近五年考过几次、你碰过几次、最近一次是哪天、
 * 是从哪几个来源碰的。<b>它不会告诉你这个考点怎么做题</b> —— 那是教研,01 §2.2 划在边界外,
 * 而这条边界被描述为其他所有优势的来源。
 *
 * <h2>{@code sources} 里只有来源的名字</h2>
 *
 * 「粉笔 · 资料分析系统班 L12」是一个字符串,不是那节课的任何内容。
 * 01 §2.2 不碰内容:机构的课程内容一概不存,只记来源名与时间戳。
 *
 * <h2>关于「四统计字段」</h2>
 *
 * docs/10 §6.4 要的是四个纯统计字段({@code recent5y_count / province_codes /
 * last_seen_year / avg_per_paper},见 §5.2 的 {@code syllabus_stat} 表)。
 * <b>现在的骨架种子只产出了其中一个</b>,另外三个属于离线加工区尚未产出的数据。
 * 这里只暴露已经存在的那个,不给不存在的数据造字段 —— 造出来就是三个恒为 null 的坑,
 * 前端会照着它写渲染逻辑,然后在真数据到位那天全部返工。
 *
 * @param accuracy   用户自填正确率;没练过是 {@code null},界面显示「—」<b>不是 0%</b>
 * @param practiced  练了几道 —— <b>用户自己敲进来的数</b>
 * @param correct    对了几道 —— 同上。产品从不判题(01 §2.2)
 * @param sources    碰过这个考点的来源名集合,按首次出现顺序
 */
public record NodeDetailDto(
        String code,
        String name,
        String groupCode,
        String groupName,
        int recent5yCount,
        String state,
        String stateLabel,
        int touchCount,
        int practiced,
        int correct,
        Double accuracy,
        Instant latestAt,
        List<String> sources
) {
    public static NodeDetailDto from(NodeCoverage n) {
        return new NodeDetailDto(
                n.code(), n.name(), n.groupCode(), n.groupName(), n.recent5yCount(),
                n.state().name(), n.state().label(),
                n.touchCount(), n.practiced(), n.correct(), n.accuracy(), n.latestAt(),
                n.sources());
    }
}
