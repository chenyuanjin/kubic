package com.kaodian.server.api.events;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.kaodian.server.api.dto.common.UnknownFieldException;

/**
 * {@code POST /api/v1/events/blindspot-opened} 的请求体 —— <b>四个分量,一个都不多</b>
 * ({@code M3-骨架与覆盖度差集} §6.1)。
 *
 * <h2>🔴 不带的属性,一个都不加</h2>
 *
 * 科目、排序口径、筛选、停留时长、滚动深度、点了几个考点、设备指纹、{@code identity_kind} ——
 * 一个都不在这里。带上任何一个,这个事件就从「<b>一个人来看了</b>」变成<b>一份行为画像</b>,
 * 而这个产品不做行为分析(§6.1 / `看盲区` §13.6)。
 * <p>
 * 失败方式是无声的:多一个字段不会让任何东西报错,只会让下一个人觉得「既然有 dwellMs
 * 那再加个 scrollDepth 也不过分」。所以拦它的不是纪律,是
 * {@code BlindspotEventShapeTest} 那条「分量名集合<b>恰好等于</b>这四个」的断言。
 * <p>
 * {@link #rejectUnknownField} 是同一件事的另一半:<b>请求体里多送一个键就是 400</b>,
 * 而且与 {@code FAIL_ON_UNKNOWN_PROPERTIES} 那行配置无关(理由逐字见
 * {@link UnknownFieldException})。少了它,{@code {"deviceId":"..."}} 会被静默忽略然后返回 200 ——
 * <b>端以为自己在采集设备指纹,服务端以为自己没在收</b>,两边都不会发现。
 *
 * <h2>四个分量全是 {@code String},包括 {@code localDate}</h2>
 *
 * 不写成 {@code LocalDate}:那样 {@code "2026-9-3"} 会在 Jackson 绑定期就炸,出去的是
 * {@code MALFORMED_BODY},而契约给这一档定的码是 {@code 400 INVALID_ARGUMENT}(§6.3 末)。
 * <b>码错了端的队列纪律不会错(4xx 一律删),但排查的人会去查 JSON 语法,而问题在日期取值。</b>
 * 收成 String、由 {@code BlindspotEventController} 统一判,四种非法输入才会走同一个出口。
 *
 * @param localDate 端上产生这次动作时的<b>本地自然日</b>,不是上报时刻;补传时原样带上。
 *                  合法窗口见 {@code BlindspotEventController#requireLocalDateInWindow}
 * @param surface   这一次「主动查看」发生在哪一屏。闭集 {@code S-BLIND} | {@code S-ASK}
 * @param entry     怎么到达这一屏的。闭集 {@code home} | {@code deeplink} ——
 *                  🔴 {@code restore}(冷启动恢复)<b>恒不上报</b>,所以它不在取值域里:
 *                  那不是这一次的主动选择,是上一次的残留
 * @param outcome   上屏时有没有数据。闭集 {@code data} | {@code empty} ——
 *                  空态<b>也打</b>(它也是一次查看),但必须可区分
 */
public record BlindspotOpenedRequest(

        String localDate,

        String surface,

        String entry,

        String outcome
) {

    /**
     * 🔴 未定义字段一律拒绝。
     *
     * <p><b>{@code value} 收下就丢</b>:它是端送来的原文。异常里只带字段名
     * (决策记录 §2.2 不碰内容)。
     */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
