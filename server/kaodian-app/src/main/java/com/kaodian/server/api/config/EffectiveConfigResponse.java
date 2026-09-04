package com.kaodian.server.api.config;

import com.kaodian.server.config.BlindspotCaliber;

/**
 * {@code GET /api/v1/config/effective} 的响应 —— <b>两个字段,恒在,没有第三个</b>
 * ({@code M3-骨架与覆盖度差集} §3.1)。
 *
 * <h2>🔴 为什么没有第三个字段</h2>
 *
 * 这个端点是「同一个数只许有一个来源」的兜底端点。多一个字段就是多一个数,
 * 而多出来的那个数在端上一定会被读、被缓存、被当默认值 —— 于是<b>下一次它拿不到时,
 * 端要退让的东西就从两个变成了三个</b>,而偏离登记的闭集只认两个名字。
 * 字段要长,先在 §3.1 那张表上长一行,同时在偏离登记的闭集里长一行。
 *
 * <h2>为什么 {@code blindspotOrderBy} 出去的是 {@code String} 而不是枚举</h2>
 *
 * 出去的是 {@code snake_case} 线上名({@code BlindspotOrder#wireName}),
 * 与 {@code GET /coverage/blindspots} 的 {@code orderBy} 参数<b>逐字相同</b> ——
 * 端拿到这个值之后要原样送回来,两边不同名就等于端得自己做一次映射,
 * 而那次映射就是「同一个数的第二个来源」。
 *
 * @param blindspotOrderBy 默认排序口径的线上名,取值域四个({@code BlindspotOrder})
 * @param blindspotTop     「先补这几个」的 N,{@code 1..100}
 */
public record EffectiveConfigResponse(String blindspotOrderBy, int blindspotTop) {

    /** 唯一的构造入口 —— 两个字段都从 {@link BlindspotCaliber} 那一份常量来,这里不做任何兜底。 */
    public static EffectiveConfigResponse of(BlindspotCaliber caliber) {
        return new EffectiveConfigResponse(caliber.orderBy().wireName(), caliber.top());
    }
}
