package com.kaodian.server.api;

import com.kaodian.server.api.dto.RecordPageResponse;
import com.kaodian.server.collect.Touch;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * {@code GET /api/records} 的游标 —— 「上一页最后看到的是哪一条」。
 *
 * <h2>游标里装的是<b>排序键</b>,不是页码</h2>
 *
 * 内容是 {@code 发生时间毫秒 | 记录 id},Base64URL 编码。
 * 装页码(等价于 offset)会让翻页在「边翻边记」时漏条:新记的一笔插到最前面,
 * 整个列表往后推一格,第二页的第一条就是第一页看过的最后一条,而中间那条永远不会出现。
 * 锚在一条具体记录上就没有这个问题。
 *
 * <h2>🔴 为什么必须带 id,只有时间戳不够</h2>
 *
 * 因为<b>同一毫秒里真的会有多条记录</b>,而且不是理论上的:
 * 离线队列补传({@code POST /api/records/batch})一次落 50 条,它们的时间戳全部来自
 * <b>同一次 {@code clock.instant()}</b> —— 服务端收到的时刻。
 * 只按时间戳当游标,这 50 条要么一起被跳过,要么一起被重复吐出来;
 * 而它们恰恰是用户断网那天记的全部东西。
 * <p>
 * 所以排序是 {@code (occurredAt, id)} 两级,游标也带两级。id 是 UUID,同一毫秒内的先后
 * 因此是<b>任意但稳定</b>的顺序 —— 任意可以接受(那 50 条本来就没有真实先后),
 * 稳定不能少(不稳定就会漏条)。
 *
 * <h2>不加密、不签名,但也不是「可以随便构造」</h2>
 *
 * 游标里没有任何秘密:一个毫秒数和一个 UUID,用户本来就能从上一页的响应里读到。
 * 加签名要多一把密钥、多一处轮换,换来的只是「别人不能自己拼一个游标」——
 * 而自己拼一个游标能做到的事,等价于换一个 {@code limit} 再翻一页。
 * <p>
 * 🔴 但<b>解不开的游标必须当场拒</b>,而且拒的时候不能把它原样吐回去:
 * 查询参数没有 {@code @Size} 管得着,它能塞满整个请求行。见 {@link #decode}。
 */
final class RecordCursor {

    private static final String SEPARATOR = "|";

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
        String raw = touch.occurredAt().toEpochMilli() + SEPARATOR + touch.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解开游标。
     *
     * <p>🔴 任何解不开的情况都走同一个出口:{@link ApiException#unknownValue},
     * 它会把回声截断。这里刻意<b>不自己写一段截断</b> —— 那会在仓库里造出第二个长度上限,
     * 而两个上限迟早对不上,到那时真正生效的是小的那个,没人说得清是哪个。
     *
     * @param cursor 用户送来的游标,可能是任何东西
     * @return 解开的位置;{@code cursor} 为 {@code null} 或空白时返回 {@code null}(第一页)
     * @throws ApiException 游标不合法 —— 400 {@code INVALID_CURSOR}
     */
    static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        // 先看长度再解码:一个几 KB 的「游标」不值得走一遍 Base64,
        // 而且它十有八九是被粘错了地方的东西,不该有机会进后面任何一行。
        if (cursor.length() > RecordPageResponse.MAX_CURSOR_LENGTH) {
            throw invalid(cursor);
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid(cursor);
        }
        int cut = raw.indexOf(SEPARATOR);
        if (cut <= 0 || cut == raw.length() - 1) {
            throw invalid(cursor);
        }
        try {
            return new Position(Long.parseLong(raw.substring(0, cut)), raw.substring(cut + 1));
        } catch (NumberFormatException e) {
            throw invalid(cursor);
        }
    }

    private static ApiException invalid(String cursor) {
        return ApiException.unknownValue("INVALID_CURSOR", "游标", cursor);
    }
}
