package com.kaodian.server.api.dto.common;

import com.kaodian.server.api.support.ApiException;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 全库唯一的游标编解码与 {@code limit} 校验({@code B0-平台底座与横切契约} §7.1)。
 *
 * <h2>游标里装的是<b>排序键</b>,不是第几页</h2>
 *
 * 内容是 {@code 排序键 | id} 两级,Base64URL 编码。装「第几页」会让翻页在「边翻边记」时漏条:
 * 新记的一笔插到最前面,整个列表往后推一格,第二页的第一条就是第一页看过的最后一条,
 * 而中间那条永远不会出现。锚在一条具体条目上就没有这个问题。
 *
 * <h2>🔴 为什么必须带 id,只有排序键不够</h2>
 *
 * 因为<b>同一毫秒里真的会有多条记录</b>,而且不是理论上的:离线队列补传一次落 50 条,
 * 它们的时间戳全部来自<b>同一次 {@code clock.instant()}</b> —— 服务端收到的时刻。
 * 只按时间戳当游标,这 50 条要么一起被跳过,要么一起被重复吐出来;
 * 而它们恰恰是用户断网那天记的全部东西。
 * 所以排序是两级,游标也带两级;同一毫秒内的先后因此是<b>任意但稳定</b>的顺序 ——
 * 任意可以接受(那 50 条本来就没有真实先后),稳定不能少(不稳定就会漏条)。
 *
 * <h2>不加密、不签名,但也不是「可以随便构造」</h2>
 *
 * 游标里没有任何秘密:一个毫秒数和一个 id,用户本来就能从上一页的响应里读到。
 * 加签名要多一把密钥、多一处轮换,换来的只是「别人不能自己拼一个游标」——
 * 而自己拼一个游标能做到的事,等价于换一个 {@code limit} 再翻一页。
 * <p>
 * 🔴 但<b>解不开的游标必须当场拒</b>,而且拒的时候不能把它原样吐回去:
 * 查询参数没有 {@code @Size} 管得着,它能塞满整个请求行。见 {@link #decode}。
 */
public final class Cursor {

    /**
     * 游标字符串的长度上限 —— 同时是「我们发出去的最长游标」和「我们肯收的最长游标」。
     *
     * <p>游标是服务端签发的 {@code Base64URL(排序键 | id)},id 是 {@code t-} + UUID(38 字符),
     * 算下来七十出头,120 给了余量。
     * <p>
     * 🔴 更要紧的是<b>收</b>的那一侧:游标是查询参数,而查询参数没有任何长度上限。
     * 一个「解不开的游标」的报错会带着它进服务端日志,于是「翻页」这条最无害的路径
     * 就成了往日志里写一整段题干的通道。超过这个长度的游标<b>连解都不解</b>,直接拒。
     *
     * <p>✅ 全库<b>只剩这一个</b>游标长度上限({@code KUBI-99}):
     * {@code RecordPageResponse.MAX_CURSOR_LENGTH} 那份副本连同那个类一起删掉了。
     */
    public static final int MAX_LENGTH = 120;

    /**
     * {@code limit} 的默认值与上下界({@code 接口契约} §1.4:{@code 1..100},默认 {@code 20})。
     *
     * <p>✅ {@code GET /api/v1/records} 已改用这三个数与 {@link #limit}({@code KUBI-99})。
     * 它上一版是 {@code 1..200}、默认 {@code 50}、超界回 {@code VALIDATION_FAILED},三处都与本行不一致;
     * 那一版的 {@code @Min}/{@code @Max} 注解已连同摘掉 —— 留着注解就还有第二个错误码。
     */
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final String SEPARATOR = "|";

    private Cursor() {
    }

    /**
     * 游标解开之后的两级排序键。
     *
     * @param sortKey 主排序键(记录时间线上是发生时间毫秒)
     * @param id      同一个 {@code sortKey} 内定序用的条目 id。上限跟着整个游标走
     *                ——它是从一个已经过了 {@link #MAX_LENGTH} 那道闸的串里切出来的
     */
    public record Position(long sortKey, @Size(max = MAX_LENGTH) String id) {
    }

    /** 把「上一页最后看到的那条」编成游标。 */
    public static String encode(long sortKey, String id) {
        String raw = sortKey + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解开游标。
     *
     * <p>🔴 任何解不开的情况都走同一个出口:{@link ApiException#unknownValue},它会把回声截断。
     * 这里刻意<b>不自己写一段截断</b> —— 那会在仓库里造出第二个长度上限,而两个上限迟早对不上,
     * 到那时真正生效的是小的那个,没人说得清是哪个。
     *
     * @param cursor 用户送来的游标,可能是任何东西
     * @return 解开的位置;{@code cursor} 为 {@code null} 或空白时返回 {@code null}(第一页)
     * @throws ApiException 游标不合法 —— 400 {@code INVALID_CURSOR}
     */
    public static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        // 先看长度再解码:一个几 KB 的「游标」不值得走一遍 Base64,
        // 而且它十有八九是被粘错了地方的东西,不该有机会进后面任何一行。
        if (cursor.length() > MAX_LENGTH) {
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

    /**
     * {@code limit} 的唯一一处校验 —— <b>不是每个 controller 各写一遍</b>。
     *
     * <p>做成方法而不是一对 {@code @Min}/{@code @Max} 注解,是因为注解那一版的超界错误码是
     * {@code VALIDATION_FAILED},而 §1.4 要的是 {@code INVALID_LIMIT}:
     * 「这个数超界了」和「请求体不合法」在端上是两条不同的分支。
     *
     * @param requested 用户传的 {@code limit};{@code null}(没传)→ {@link #DEFAULT_LIMIT}
     * @throws ApiException 超出 {@code 1..100} —— 400 {@code INVALID_LIMIT}
     */
    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT",
                    "limit 只能是 1 到 " + MAX_LIMIT + " 之间的整数。");
        }
        return requested;
    }

    private static ApiException invalid(String cursor) {
        return ApiException.unknownValue("INVALID_CURSOR", "游标", cursor);
    }
}
