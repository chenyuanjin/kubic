package com.kaodian.server.api.dto.common;

// 🔴 注解留在 com.fasterxml.jackson.annotation,不在 tools.jackson:
// Jackson 3 的 databind 是 tools.jackson.databind,但注解仍在 Jackson 2.x 的 group id 下
// (与 ApiError 同一条,那里写了原文出处)。
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 全库唯一的分页响应形状 —— {@code {items, nextCursor?}}({@code B0-平台底座与横切契约} §7.1,
 * {@code 接口契约-签名与错误码全集} §1.4)。
 *
 * <h2>只有两个字段,而且第二个可以整个不出现</h2>
 *
 * 🔴 <b>没有下一页时,响应里根本没有 {@code nextCursor} 这个 key</b> —— 不是 {@code null},
 * 也不是空串。{@code @JsonInclude(NON_NULL)} 是 {@code 接口契约} §1.1 空值规则在这里的
 * <b>执行装置</b>(与 {@link ApiError} 同一条):留一个 {@code "nextCursor": null},
 * 端上就会写出一句 {@code if ('nextCursor' in page)} 然后永远为真。
 *
 * <h2>🔴 为什么只有这两个字段</h2>
 *
 * §1.4 明令不返回条数统计与「还有没有更多」的布尔:一个条数字段会立刻长出页码条,
 * 而页码条要求随机跳页 —— 游标做不到。{@code U5.6} 逐字要求「前端不猜总数」,
 * {@code U7.6} 要求「不做『加载更多』按钮」。
 * <p>
 * 「手上这批是不是全部」这个问题不需要额外字段就能回答:<b>响应里没有 {@code nextCursor} 这个 key</b>。
 *
 * <p>✅ {@code GET /api/v1/records} 已经收敛到本类({@code KUBI-99}):{@code RecordPageResponse}
 * 连同它多出的 {@code total} / {@code returned} / {@code hasMore} 一起删掉了,
 * 前端的截断闸门在<b>同一次改动</b>里换成了「响应里有没有 {@code nextCursor} 这个 key」——
 * 拆成两次落地,中间那一刻 {@code buildDrillIndex} 就是断的。
 *
 * @param items      本页的条目,顺序由端点自己定(记录时间线是倒序)
 * @param nextCursor 下一页从哪儿接着翻;<b>没有下一页时传 {@code null},这个 key 就不会出现</b>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Page<T>(

        List<T> items,

        /*
         * 游标的长度上限与 Cursor 是同一个数(R-01:自由文本必须有上限)——
         * 它是服务端自己签发的串,不是用户送来的东西,但「装得下一整道题的 String」这条纪律不分方向。
         */
        @Size(max = Cursor.MAX_LENGTH)
        String nextCursor
) {
}
