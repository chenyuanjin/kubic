package com.kaodian.server.api.dto.common;

/**
 * 统一错误体 —— docs/技术架构 §六:{@code {code, message, traceId}}。
 *
 * <h2>三个字段,一个都不多</h2>
 *
 * 没有 {@code stackTrace}、没有 {@code exception}、没有 {@code path}。
 * 堆栈留在服务端日志里,前端拿到的只有一个可以报给我们的 {@link #traceId}。
 * 把异常类名吐给前端等于把内部结构当公开契约,而且它一旦被前端拿去做分支判断,
 * 重构后端就会打断前端 —— 这就是 {@link #code} 存在的理由:
 * <b>可被程序判断的是这个稳定的 code,不是异常类名,也不是中文文案。</b>
 *
 * @param code    稳定的机器可读错误码,前端按它分支
 * @param message 给人看的中文提示,可以改词不影响前端逻辑
 * @param traceId 服务端日志里的同一串 id,用户报障时唯一需要念出来的东西
 */
public record ApiError(String code, String message, String traceId) {
}
