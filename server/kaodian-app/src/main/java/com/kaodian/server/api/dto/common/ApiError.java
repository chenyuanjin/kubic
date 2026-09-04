package com.kaodian.server.api.dto.common;

// 🔴 注解留在 com.fasterxml.jackson.annotation,不在 tools.jackson:
// Jackson 3 的 databind 是 tools.jackson.databind,但它自己的 pom 明写
// 「Annotations remain at Jackson 2.x group id」,依赖的仍是 com.fasterxml.jackson.core:jackson-annotations。
// 同包的 UnknownFieldException 用的 @JsonAnySetter 也是这个包。
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 统一错误体 —— {@code 接口契约-签名与错误码全集} §1.3:{@code {code, message, traceId, details?}}。
 *
 * <h2>四个字段,一个都不多</h2>
 *
 * 没有 {@code stackTrace}、没有 {@code exception}、没有 {@code path}。
 * 堆栈留在服务端日志里,前端拿到的只有一个可以报给我们的 {@link #traceId}。
 * 把异常类名吐给前端等于把内部结构当公开契约,而且它一旦被前端拿去做分支判断,
 * 重构后端就会打断前端 —— 这就是 {@link #code} 存在的理由:
 * <b>可被程序判断的是这个稳定的 code,不是异常类名,也不是中文文案。</b>
 *
 * <h2>🔴 {@code @JsonInclude(NON_NULL)} 不是风格选择</h2>
 *
 * 它是 {@code 接口契约} §1.1 空值规则的<b>执行装置</b>:「『没有这个字段』与『值是 0/空串』
 * 必须能被区分:没有就不出现这个 key」。返回 {@code "details": null} 就是把这条规则破掉 ——
 * 端会写出一句 {@code if ('details' in err)} 然后永远为真。
 *
 * <h2>{@code details} 什么时候才允许有</h2>
 *
 * 🔴 <b>只在 {@code 接口契约} 逐个端点明确写了形状时才有,没写形状就不许有。</b>
 * 今天登记在册的三处:{@code PHONE_LOCKED} 带准确解锁时点、{@code CODE_WRONG} 带剩余次数、
 * {@code QUOTA_EXHAUSTED} 带手动记录入口提示。形状由各自模块设计定,这里只留通道。
 *
 * @param code    稳定的机器可读错误码,取值域 = {@link ErrorCode} 全集
 * @param message 给人看的中文提示,可以改词不影响前端逻辑
 * @param traceId 服务端日志里的同一串 id,用户报障时唯一需要念出来的东西
 * @param details 端点契约写明形状时才有;{@code null} 时整个 key 不出现
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, String traceId, Map<String, Object> details) {

    /** 没有 {@code details} 的那一大半调用点 —— 老三参形状原样可用,不必一次改完。 */
    public ApiError(String code, String message, String traceId) {
        this(code, message, traceId, null);
    }
}
