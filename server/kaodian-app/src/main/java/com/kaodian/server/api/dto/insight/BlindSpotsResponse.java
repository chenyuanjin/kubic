package com.kaodian.server.api.dto.insight;

import com.kaodian.server.api.dto.common.BlindSpotDto;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;

import java.util.ArrayList;
import java.util.List;

/**
 * 盲区 Top N —— <b>北极星指标的落点接口</b>。
 *
 * <p>北极星是「主动查看盲区的人数」,不是注册数也不是 DAU。这个端点被调用一次,
 * 就是那个指标的一次实际发生。所以它的形状要让「看了盲区」这件事在服务端可数、可解释:
 * {@code requestedTop} 与 {@code returned} 分开给,是为了能看出<b>盲区已经不足 N 个</b>
 * ({@code returned < requestedTop})—— 那是用户真的补完了,是产品最想知道的一件事。
 *
 * @param requestedTop 调用方要了几个
 * @param returned     实际给了几个。稳({@code STABLE})权重为 0 不入榜,所以可能少于要的数
 */
public record BlindSpotsResponse(
        int requestedTop,
        int returned,
        List<BlindSpotDto> items
) {
    public static BlindSpotsResponse of(int requestedTop, List<NodeCoverage> nodes) {
        List<BlindSpotDto> items = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            items.add(BlindSpotDto.of(i + 1, nodes.get(i)));
        }
        return new BlindSpotsResponse(requestedTop, items.size(), List.copyOf(items));
    }
}
