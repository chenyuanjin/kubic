package com.kaodian.server.config;

import com.kaodian.server.coverage.BlindspotOrder;

/**
 * 「先补这几个」的默认口径 —— <b>排序口径 + N,两个数,全进程只有这一份</b>
 * ({@code M3-骨架与覆盖度差集} §三 3.1)。
 *
 * <h2>🔴 为什么是一个常量,而不是一份可配置项</h2>
 *
 * {@code GET /api/v1/config/effective} 存在的全部理由是<b>「同一个数只许有一个来源」</b>:
 * 端不许自己揣一份默认值,拿不到就退让并留痕。那么服务端这一侧就<b>不许再多一个开关</b> ——
 * 一旦这两个数能从 {@code application.properties} / {@code @ConfigurationProperties} 读,
 * 「来源」当场变成两个(代码里的值 + 配置里的值)。它们不一致的那天,
 * 端拿到的口径与盲区榜实际用的口径会悄悄岔开:<b>接口全绿、榜单排错序、没有任何东西报错</b>。
 *
 * <h2>🔴 两个字段恒在,没有「服务端也没配」这一档</h2>
 *
 * {@link #DEFAULT} 是常量而不是 {@code Optional}:这个端点正是给「口径拿不到」兜底的那一个,
 * <b>它自己缺值就等于没有来源</b>。所以缺值这一档在类型上就不存在。
 *
 * <h2>取值域不在这里重抄一遍</h2>
 *
 * 排序口径是 {@link BlindspotOrder} 那个闭集枚举(四个,没有第五个),这里只挑其中一个当默认 ——
 * 在这里再写一份 {@code String} 常量,就是给同一个闭集造第二个来源。
 *
 * @param orderBy 默认排序口径,<b>非空</b>
 * @param top     「先补这几个」的 N,<b>1..100</b>
 */
public record BlindspotCaliber(BlindspotOrder orderBy, int top) {

    /**
     * 🔴 <b>全进程唯一那份默认口径。</b>
     *
     * <p>{@code GET /config/effective} 下发它,{@code GET /coverage/blindspots}
     * 不带参数时用它 —— <b>两处读同一个常量</b>。各自写一个字面量 {@code 20} 的那一版,
     * 只要有人改了其中一处,「下发的口径」与「实际用的口径」就不再是同一个数,
     * 而端没有任何办法发现这件事。
     */
    public static final BlindspotCaliber DEFAULT =
            new BlindspotCaliber(BlindspotOrder.RECENT5Y_COUNT, 20);

    /**
     * 值域钉在构造器上,而不是钉在某个控制器的参数校验上。
     *
     * <p>它是一份「先补这几个」的清单,不是导出接口:N 越界的那一版会把整棵树倒给端,
     * 而端会照样把它画成一张榜。校验写在类型里,{@link #DEFAULT} 被人改坏时是<b>类加载即炸</b>,
     * 不是等到某个请求打进来才发现。
     */
    public BlindspotCaliber {
        if (orderBy == null) {
            throw new IllegalArgumentException("默认排序口径不能为空 —— 恒在的字段没有「没配」这一档");
        }
        if (top < 1 || top > 100) {
            throw new IllegalArgumentException("blindspotTop 只能是 1..100,收到:" + top);
        }
    }
}
