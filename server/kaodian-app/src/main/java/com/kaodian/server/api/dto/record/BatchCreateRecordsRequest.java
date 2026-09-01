package com.kaodian.server.api.dto.record;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

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
 * @param records 这一批记录。<b>顺序即 {@link BatchCreateRecordsResponse.ItemResult#index()}</b>,
 *                客户端凭它把结果对回自己队列里的那一条
 */
public record BatchCreateRecordsRequest(

        @NotEmpty(message = "补传批次不能是空的")
        @Size(max = BatchCreateRecordsRequest.MAX_BATCH_SIZE,
                message = "单批最多 50 条 —— 超了请分批")
        List<CreateRecordRequest> records
) {

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
