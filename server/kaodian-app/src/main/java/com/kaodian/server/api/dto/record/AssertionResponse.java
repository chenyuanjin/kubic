package com.kaodian.server.api.dto.record;

/**
 * {@code POST} / {@code DELETE /api/v1/assertions} 的响应体
 * ({@code M3-骨架与覆盖度差集} §9.5)。
 *
 * <h2>🔴 一个字段,而且它<b>恒为 {@code false}</b></h2>
 *
 * 按下「我已经会了」之后<b>三个数一个都不变</b>({@code U3.6} §2.2)。
 * 这个字段存在,是为了让「不变」被<b>显式说出来</b>,而不是让前端去猜:
 * <p>
 * 没有它的话,端在这个动作之后只有两条路 —— 要么重新拉一次
 * {@code GET /coverage/summary}(一次多余的往返,而且中间那一刻两屏不一致),
 * 要么<b>自己假设不变</b>。第二条路今天是对的,而它对的理由写在服务端的一行
 * {@code if} 里({@code NodeState} 的优先级链);端复制这个假设的那一天,
 * 服务端就再也改不动那一行了。
 * <p>
 * ⚠️ 它<b>不是</b>一个「有时 true」的字段。真出现了让覆盖度变化的断言语义,
 * 那是一次产品裁定,而不是把这里翻成 {@code true} 就完事。
 *
 * <h2>🔴 这里没有 {@code node} / {@code summary} / {@code assertedAt}</h2>
 *
 * 上一版把整份考点详情与整份概览一起捎回来。去掉的理由不是响应体大:
 * <b>捎回来的那一份概览是第二个来源</b>。端拿到它就会用它刷新首屏,
 * 于是同一个数有了「查出来的」和「写完顺手带回来的」两条路径 ——
 * 而这两条在并发写入下必然分叉。断言这个动作的全部产出就是一句
 * 「记下了,数没变」,它不需要第二份数据。
 */
public record AssertionResponse(boolean coverageChanged) {

    /**
     * 🔴 唯一的构造方式。断言之后覆盖度恒不变 ——
     * 把它做成一个静态工厂而不是让调用方写 {@code new AssertionResponse(false)},
     * 是为了让「哪天有人写了 {@code true}」这件事在 diff 里显眼:
     * 那不是一次实现改动,是一次产品裁定。
     */
    public static AssertionResponse unchanged() {
        return new AssertionResponse(false);
    }
}
