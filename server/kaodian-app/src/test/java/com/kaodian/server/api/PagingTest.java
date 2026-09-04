package com.kaodian.server.api;

import com.kaodian.server.api.dto.common.Cursor;
import com.kaodian.server.api.dto.common.Page;
import com.kaodian.server.api.support.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页的统一形状({@code B0-平台底座与横切契约} §7.1 / {@code 接口契约} §1.4)。
 *
 * <p>这里钉的是<b>形状与判据</b>,不是某个端点:{@code GET /api/records} 换成 {@link Page}
 * 归 {@code M1}(KUBI-90)执行,且要与前端换闸门同一次落地。
 */
class PagingTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("🔴 没有下一页时 nextCursor 这个 key 整个不出现 —— 不是 null,不是空串")
    void nextCursorKeyIsAbsentOnTheLastPage() {
        String body = json.writeValueAsString(new Page<>(List.of("a", "b"), null));

        assertFalse(body.contains("nextCursor"),
                "留一个 \"nextCursor\": null,端上就会写出 if ('nextCursor' in page) 然后永远为真:" + body);
        assertTrue(body.contains("items"), body);
    }

    @Test
    @DisplayName("有下一页时才出现这个 key")
    void nextCursorKeyIsPresentWhenThereIsANextPage() {
        String body = json.writeValueAsString(new Page<>(List.of("a"), "eyJpZCI6MX0"));

        assertTrue(body.contains("\"nextCursor\":\"eyJpZCI6MX0\""), body);
    }

    @Test
    @DisplayName("游标编解码往返 —— 两级排序键原样回来")
    void cursorRoundTrips() {
        String cursor = Cursor.encode(1_725_000_000_123L, "t-2f08835f-1846-4e1e-8d37-0d2c8feacb60");

        Cursor.Position at = Cursor.decode(cursor);

        assertEquals(1_725_000_000_123L, at.sortKey());
        assertEquals("t-2f08835f-1846-4e1e-8d37-0d2c8feacb60", at.id());
        assertTrue(cursor.length() <= Cursor.MAX_LENGTH, "我们自己签发的游标必须在上限之内:" + cursor.length());
    }

    @Test
    @DisplayName("不传游标 = 第一页")
    void noCursorMeansFirstPage() {
        assertNull(Cursor.decode(null));
        assertNull(Cursor.decode("   "));
    }

    @Test
    @DisplayName("🔴 解不开的游标 → 400 INVALID_CURSOR,而且不回显整段原文")
    void unreadableCursorIsRejectedWithoutEchoingTheWholeThing() {
        String pastedStem = "2023 年全国粮食总产量为 13908 亿斤,比上年增加 177 亿斤".repeat(40);

        ApiException tooLong = assertThrows(ApiException.class, () -> Cursor.decode(pastedStem));
        assertEquals(HttpStatus.BAD_REQUEST, tooLong.status());
        assertEquals("INVALID_CURSOR", tooLong.code());
        assertTrue(tooLong.getMessage().length() < pastedStem.length(),
                "游标是查询参数,没有 @Size 管得着它 —— 回声必须自己截断");

        // 长度够短但根本不是游标的,同样是 INVALID_CURSOR,不是 500
        assertEquals("INVALID_CURSOR", assertThrows(ApiException.class, () -> Cursor.decode("不是游标")).code());
        // Base64 解得开,但里面不是「排序键|id」
        assertEquals("INVALID_CURSOR", assertThrows(ApiException.class, () -> Cursor.decode("aWQ")).code());
    }

    @Test
    @DisplayName("🔴 limit 一处校验:默认 20,1..100,超界是 INVALID_LIMIT 不是 VALIDATION_FAILED")
    void limitIsValidatedInOnePlace() {
        assertEquals(20, Cursor.limit(null));
        assertEquals(1, Cursor.limit(1));
        assertEquals(100, Cursor.limit(100));

        for (int outOfRange : new int[]{0, -1, 101, 200}) {
            ApiException rejected = assertThrows(ApiException.class, () -> Cursor.limit(outOfRange),
                    "越界的 limit 必须被拒:" + outOfRange);
            assertEquals(HttpStatus.BAD_REQUEST, rejected.status());
            assertEquals("INVALID_LIMIT", rejected.code());
        }
    }

    // 「游标长度上限全库只有一个数」那条断言随 RecordPageResponse 一起消失了(KUBI-99):
    // 它守的是【两个副本别对不上】,而现在只剩 Cursor.MAX_LENGTH 一处,没有第二个数可对。
}
