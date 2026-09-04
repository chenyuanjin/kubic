package com.kaodian.server.api.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * {@code GET /api/v1/tokens} 的一页 —— <b>游标分页</b>({@code M5-账号与登录通道} §9.7)。
 *
 * <h2>🔴 没有 {@code total},没有 {@code hasMore}</h2>
 *
 * {@code B0} §7.1 与 {@code U5.6} §三 的同一条:「分页用游标,<b>前端不猜总数</b>」。
 * 这两个字在 {@code M1} 的 {@code RecordPageResponse} 上还留着,那是一笔已登记的旧账;
 * <b>本模块是新写的,不继承它</b>。
 * <p>
 * {@code nextCursor} 缺席<b>就是</b>「没有下一页」—— 那已经是 {@code hasMore} 的全部信息量,
 * 而两个字段并存意味着它们可以不一致,于是端要决定信哪一个。
 *
 * <h2>没有下一页时整个 key 不出现</h2>
 *
 * {@code 接口契约} §一 的空值规则:<b>没有这个字段就不出现这个 key,不用 {@code null} 也不用 {@code ""}</b>。
 * 这里用 {@link JsonInclude} 在<b>本记录上</b>落地,而不是去改全局 Jackson 配置 ——
 * 后者是 {@code B0} 的响应包络,六个模块各改一遍就是六套语义
 * (本议题「共同约束」第 2 条)。
 *
 * @param nextCursor 下一页的游标,不透明。<b>端原样回传,不解析、不构造</b>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenPageResponse(List<TokenDto> items, String nextCursor) {
}
