package com.kaodian.server.api.dto.insight;

import com.kaodian.server.api.dto.common.BlindSpotDto;
import com.kaodian.server.coverage.BlindspotOrder;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;

import java.util.List;

/**
 * 「先补这几个」的响应体 —— {@code GET /api/v1/coverage/blindspots}
 * ({@code M3-骨架与覆盖度差集} §9.3)。
 *
 * <h2>🔴 三个字段,一个都不多 —— 尤其没有分页那三个</h2>
 *
 * 响应里<b>没有 {@code nextCursor} / {@code total} / {@code hasMore}</b>:
 * 这个端点<b>不分页</b>,超出 N 一律走树。一个 {@code total} 会立刻长出页码条,
 * 而这份清单的产品语义是「先补这几个」——<b>它有一个尽头,不是一条流</b>。
 *
 * <h2>🔴 {@code orderBy} 与 {@code top} 都是<b>回显</b>,不是回声</h2>
 *
 * 两者的默认值都由服务端定({@code GET /config/effective}),端不硬编码。
 * 回显它们是为了让端<b>知道这一屏是按什么排的</b>(界面上那句口径说明),
 * 不是为了让端把它存下来下次自己传 —— {@code top} 已经不是查询参数了。
 */
public record BlindSpotsResponse(
        String orderBy,
        int top,
        List<BlindSpotDto> items
) {

    public static BlindSpotsResponse of(BlindspotOrder orderBy, int top, List<NodeCoverage> nodes) {
        return new BlindSpotsResponse(orderBy.wireName(), top,
                nodes.stream().map(BlindSpotDto::of).toList());
    }
}
