package com.kaodian.server.agent.channel;

/**
 * 一条 SSE 事件的<b>中立形态</b>:一个事件名 + 一段已经序列化好的 JSON。
 *
 * <h2>为什么不直接用 Spring 的 ServerSentEvent / SseEmitter</h2>
 *
 * 那样 kaodian-agent 就得依赖 spring-web,而它现在<b>一个 web 依赖都没有</b>
 * (pom 里只有 starter / json / spring-ai)。这不是洁癖:agent 的编排、工具、存储
 * 与「这一轮是通过 HTTP 还是别的什么进来的」无关,让它认识 HttpServletResponse
 * 就等于把将来接别的通道(定时任务里跑一次、CLI 里跑一次)的路提前堵上一半。
 * <p>
 * 所以翻译在这里结束:agent 产出事件名与 JSON,<b>怎么把它写进一个 HTTP 响应,是 app 层的事</b>。
 */
public record AgentSseEvent(String event, String data) {
}
