package com.kaodian.server.api.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;

import java.time.Instant;

/**
 * 「先补这几个」里的一行 —— {@code GET /api/v1/coverage/blindspots}
 * ({@code M3-骨架与覆盖度差集} §9.3)。
 *
 * <h2>🔴 这里没有 {@code blindScore},也没有 {@code rank}</h2>
 *
 * 上一版每行带一个 {@code blindScore = 近五年频次 × 状态权重} 的小数。
 * 它有两个问题,任意一个都足以让它出局:
 * <ul>
 *   <li><b>它是一个浮点数</b> —— 这一域一个都不许有(§7.2)。而且它<b>会上屏</b>,
 *       上屏之后用户读到的是产品给这个考点打的一个分</li>
 *   <li><b>「状态权重」里的 {@code WEAK} 那一档由正确率推出</b> ——
 *       那是「答得怎么样」,正面撞红线一</li>
 * </ul>
 * 排序仍然存在,只是<b>口径从一个分变成一个明确的排序键</b>({@code orderBy} 四选一),
 * 而排序键本身已经作为字段在这一行上({@code recent5yCount} / {@code touchCount} /
 * {@code lastTouchAt})。<b>顺序就是答案,不需要再给一个分数解释顺序。</b>
 *
 * <p>{@code rank} 同样去掉:数组下标就是名次,给它第二个来源,两者早晚对不上。
 *
 * <h2>🔴 缺失的 key 就是分组线</h2>
 *
 * {@code recent5yCount} / {@code lastTouchAt} 没有值时<b>整个 key 不出现</b>,不返回 0。
 * 服务端已经把它们排在该在的一端({@code BlindspotOrder#missingKeyFirst}),
 * 端只在 key 状态变化处画一条分隔线 —— <b>所以这里没有 {@code group} / {@code section} 字段</b>,
 * 加一个就是给同一个事实造第二个来源(§9.3)。
 *
 * @param path 「资料分析 / 增长率 / 增长率计算」这样的一条路径,给端做面包屑。
 *             ⚠️ 骨架今天是<b>两层</b>({@code Group → Node}),所以这里是两段;
 *             骨架长出第三层时它自然变三段,端不需要改
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlindSpotDto(
        String nodeId,
        String name,
        String path,
        Integer recent5yCount,
        int touchCount,
        Instant lastTouchAt
) {

    public static BlindSpotDto of(NodeCoverage n) {
        return new BlindSpotDto(n.code(), n.name(), path(n), n.recent5yCount(),
                n.touchCount(), n.lastTouchAt());
    }

    static String path(NodeCoverage n) {
        return n.groupName() == null ? n.name() : n.groupName() + " / " + n.name();
    }
}
