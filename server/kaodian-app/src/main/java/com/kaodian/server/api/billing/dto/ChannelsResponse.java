package com.kaodian.server.api.billing.dto;

import com.kaodian.server.billing.Channel;

import java.util.List;

/**
 * {@code GET /api/v1/billing/channels} 的响应({@code M7-额度与订单} §5.2)。
 *
 * <h2>🔴 这个端点不返回任何其它能力位</h2>
 *
 * 那会长成 {@code U6.2} 禁止的<b>可下发端矩阵</b>。所以这里只有一个数组,数组里只有两个字段。
 *
 * <p><b>该端全部不可用时是 {@code channels: []}</b> —— 界面只显示免费兜底 + 一句
 * 「这个端上不能买」,🔴 <b>不给任何指向别处付款的出口</b>。
 * 拉不到时端不渲染那一格,不自己编、也不按端硬编码。
 */
public record ChannelsResponse(List<ChannelDto> channels) {

    public static ChannelsResponse of(List<Channel> channels) {
        return new ChannelsResponse(channels.stream()
                .map(c -> new ChannelDto(c.wireName(), c.displayName()))
                .toList());
    }

    public record ChannelDto(String code, String name) {
    }
}
