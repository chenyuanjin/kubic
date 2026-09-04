package com.kaodian.server.api.record;

import com.kaodian.server.api.dto.common.Cursor;
import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.collect.Touch;

/**
 * {@code GET /api/v1/records} 的游标 —— 「上一页最后看到的是哪一条」。
 *
 * <h2>编解码不在这里,在 {@link Cursor}</h2>
 *
 * 形状(两级排序键、Base64URL、长度上限、解不开就 400 {@code INVALID_CURSOR} 且不回显原值)
 * 与理由全部写在 {@link Cursor} 上,那是全库唯一的一份
 * ({@code B0-平台底座与横切契约} §7.1)。<b>这里只剩「记录时间线的排序键是什么」</b>:
 * {@code (发生时间毫秒, 记录 id)},以及它在倒序列表上的比较。
 * <p>
 * 🔴 两级不能省成一级:离线队列补传({@code POST /api/v1/records/batch})一次落 50 条,
 * 它们的时间戳来自<b>同一次 {@code clock.instant()}</b>,只按时间戳翻页会把这 50 条
 * 要么一起跳过要么一起重复吐出来 —— 而它们恰恰是用户断网那天记的全部东西。
 */
final class RecordCursor {

    private RecordCursor() {
    }

    /** 游标解开之后的两级排序键。 */
    record Position(long occurredAtMillis, String id) {

        /**
         * {@code touch} 是不是排在这个位置<b>之后</b>(更旧)。
         *
         * <p>严格小于,不是小于等于 —— 等于的那条就是上一页的最后一条,再吐一次就是重复。
         */
        boolean isStrictlyAfter(Touch touch) {
            long at = touch.occurredAt().toEpochMilli();
            if (at != occurredAtMillis) {
                return at < occurredAtMillis;       // 倒序:时间越小越靠后
            }
            return touch.id().compareTo(id) < 0;    // 同一毫秒内按 id 降序,与排序键一致
        }
    }

    /** 把一条记录编成「从这条之后接着翻」的游标。 */
    static String encode(Touch touch) {
        return Cursor.encode(touch.occurredAt().toEpochMilli(), touch.id());
    }

    /**
     * 解开游标。
     *
     * @param cursor 用户送来的游标,可能是任何东西
     * @return 解开的位置;{@code cursor} 为 {@code null} 或空白时返回 {@code null}(第一页)
     * @throws ApiException 游标不合法 —— 400 {@code INVALID_CURSOR},见 {@link Cursor#decode}
     */
    static Position decode(String cursor) {
        Cursor.Position at = Cursor.decode(cursor);
        return at == null ? null : new Position(at.sortKey(), at.id());
    }
}
