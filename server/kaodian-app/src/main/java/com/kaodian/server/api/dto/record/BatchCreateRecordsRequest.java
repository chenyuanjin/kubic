package com.kaodian.server.api.dto.record;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 离线队列补传的请求体 —— <b>一批「记一笔」,不是一件新东西</b>。
 *
 * <h2>为什么这个端点必须存在</h2>
 *
 * docs/execution/INDEX.md §四 {@code R-32}:<b>无网时记不了 → 「懒得记」虚高 → 阶段 0 误判。</b>
 * 阶段 0 的输入是「每天主动记了几笔」这两个数,而地铁上、教室里、自习室角落
 * 恰恰是最该记一笔的地方。没有补传通道,那些笔就成了「他懒得记」——
 * <b>产品会因为一个网络问题被判死刑,而判据本身是错的。</b>
 * 防线是「记录动作永不失败」:断网时进本地队列,回到线上把队列灌回来。
 *
 * <h2>🔴 每一条都必须带 {@code clientToken},这里它不是可选的</h2>
 *
 * {@link CreateRecordRequest#clientToken()} 在单条那条路上可空 —— 在线记一笔的成败当场就知道。
 * 但补传这条路<b>本身就是重发</b>:发一半断了、客户端不确定服务端收没收到、于是整批再发一次。
 * 没有去重键的补传是一次<b>注定重复</b>的写入,而重复的触达会把覆盖度的分子算错 ——
 * 那个百分比就是这个产品本身。
 * <p>
 * 所以缺 {@code clientToken} 的条目<b>逐条被拒</b>,不是整批 400:见
 * {@link BatchCreateRecordsResponse} 对「部分成功」的说明。
 *
 * <h2>🔴 这里没有图片,也不会有</h2>
 *
 * docs/technical/INDEX.md §6.2 原文:「<b>只补传文本与元数据,图片逐条走 {@code /image}</b>——
 * base64 内联下 50 条带图会把单次请求体推到百 MB 级」。
 * 这件事在这里是<b>结构上成立的</b>:{@link CreateRecordRequest} 里根本没有装图片的字段,
 * 所以不需要在这个类上再写一条「不许带图」的校验 —— 带不进来。
 *
 * <h2>🔴 每一条还必须带 {@code occurredAt},而单条那条路上没有这个字段</h2>
 *
 * 见 {@link Item#occurredAt()}。两条路的时间戳来源不同,这是<b>有意的分叉</b>,不是这里多了一个字段。
 *
 * @param records 这一批记录。<b>顺序即 {@link BatchCreateRecordsResponse.ItemResult#index()}</b>,
 *                客户端凭它把结果对回自己队列里的那一条
 */
public record BatchCreateRecordsRequest(

        @NotEmpty(message = "补传批次不能是空的")
        @Size(max = BatchCreateRecordsRequest.MAX_BATCH_SIZE,
                message = "单批最多 50 条 —— 超了请分批")
        List<Item> records
) {

    /**
     * 补传批次里的一条 = <b>{@link CreateRecordRequest} 的六个字段 + 必填的 {@code occurredAt}</b>。
     *
     * <h2>🔴 六个字段的校验规则一条都没有抄到这里来</h2>
     *
     * 它们仍然只写在 {@link CreateRecordRequest} 上一处 —— 这个 record 的六个分量是<b>裸的</b>,
     * 校验由 {@link #fields()} 转出来的那个对象承担。
     * 把 {@code @NotBlank}/{@code @Size}/{@code @Max} 在这里再敲一遍是最自然的写法,
     * 而它的代价是<b>两份规则</b>:哪天来源名上限从 60 改成 80,改一处、漏一处,
     * 于是同一条记录走单条能过、走补传过不了,而没有任何一条断言会红。
     *
     * @param occurredAt 🔴 <b>必填</b> —— 端在<b>落本地那一刻</b>记下的时间。
     *                   服务端在这条路上不打戳:补传时「落本地」与「服务端收到」相差可以是两周,
     *                   服务端打戳会把用户断网那几天记的东西全部落进补传当天的分组。
     *                   <p>
     *                   这<b>不是</b>「客户端自报时间」的例外 —— 那条规则挡的是【补记】
     *                   (界面上没有时间选择器),而这个值用户没有任何入口能改。
     *                   防伪造靠钳制不靠信任:落在未来的会被钳到当前时刻(见 {@code CaptureService})
     */
    public record Item(

            TouchKind kind,

            // 🔴 下面三个 @Size 是【长度声明】,不是第二份校验规则:
            //    max 全部引用 CreateRecordRequest / Touch 上的同一个常量,编译期只有一个数。
            //    它们必须在这里出现,是因为 NoStemFieldTest 逼着 api.dto 包里每个 String 字段
            //    说出自己的上限 —— 少一个,那条断言就退化成「凡是响应体一律放行」。
            //    真正的规则(@NotBlank / @Min / @Max / 两个 @AssertTrue)仍然只在
            //    CreateRecordRequest 上一处,由 fields() 转出来的那个对象承担。
            @Size(max = CreateRecordRequest.MAX_SOURCE_NAME_LENGTH)
            String sourceName,

            @Size(max = MAX_NODE_CODE_LENGTH)
            String nodeCode,

            Integer practiced,
            Integer correct,

            @Size(max = Touch.MAX_CLIENT_TOKEN_LENGTH)
            String clientToken,

            @NotNull(message = "补传的每一条都必须带 occurredAt —— 它是这条记录排序的唯一依据")
            Instant occurredAt
    ) {

        /** 六个共用字段 —— 校验与转换都走它,规则只在 {@link CreateRecordRequest} 上一处。 */
        public CreateRecordRequest fields() {
            return new CreateRecordRequest(kind, sourceName, nodeCode, practiced, correct, clientToken);
        }

        /** 🔴 与外层、与 {@link CreateRecordRequest#rejectUnknownField} 同一道锁,三层缺一层等于整条线松。 */
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new UnknownFieldException(name);
        }
    }

    /**
     * 单批上限,docs/technical/INDEX.md §6.2 定死的 50。
     *
     * <h2>超了当场 400,不是「截断到 50 条处理」</h2>
     *
     * 截断是这里最危险的写法:服务端收 60 条、存 50 条、回一个「成功」,
     * 客户端把整个队列清空 —— <b>用户丢了 10 笔,而且两边都以为一切正常。</b>
     * 拒绝是吵闹的,吵闹在这里是优点。
     */
    public static final int MAX_BATCH_SIZE = 50;

    /**
     * 考点 code 的长度上限。
     *
     * <p>⚠ {@link CreateRecordRequest} 上那个 {@code @Size(max = 64)} 今天还是个字面量,
     * 所以这里没有常量可引 —— 提上来一个,让两处至少是<b>同一个符号</b>。
     * 收敛到一处的正确落点是 {@code CreateRecordRequest},那是它的主场;
     * 本轮不去改它是因为改的是别人的字段声明,不是本模块的活。
     */
    static final int MAX_NODE_CODE_LENGTH = 64;

    /**
     * 🔴 与 {@link CreateRecordRequest#rejectUnknownField} 同一道锁,而且必须在这一层也有。
     *
     * <p>批量端点是绕过 R-07 最省事的一条路:如果外层对未定义字段宽容,
     * {@code {"records":[...], "tags":["我自己想的考点"]}} 就会安静地被忽略掉,
     * 调用方以为标签生效了。<b>内层每一条严、外层松,等于整条线松。</b>
     */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
