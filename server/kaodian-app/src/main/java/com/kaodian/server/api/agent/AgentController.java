package com.kaodian.server.api.agent;

import com.kaodian.server.agent.channel.AgentChunk;
import com.kaodian.server.agent.channel.AgentRequest;
import com.kaodian.server.agent.channel.AgentResult;
import com.kaodian.server.agent.channel.AgentSseEvent;
import com.kaodian.server.agent.channel.SseChannelAdapter;
import com.kaodian.server.agent.orchestrator.Orchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * Agent 的 SSE 端点 —— 七阶段里的第 ⑦ 步 EMIT。
 *
 * <h2>为什么是 SseEmitter 而不是返回 {@code Flux}</h2>
 *
 * 返回 Flux 需要 webflux 在类路径上,而这个应用是 webmvc。为了一个端点引入第二套
 * web 栈,换来的是两套线程模型在同一个进程里并存 —— 那是比手写一个 emitter 循环
 * 大得多的代价。{@code SseEmitter} 是 webmvc 原生的,占一个请求线程,
 * 在这个量级(一个人用)上完全够。
 *
 * <h2>🔴 done 帧一定要发出去</h2>
 *
 * 成功、失败、上游断流,三条路径都必须走到 {@code done} ——
 * 前端靠它关掉 loading。漏发只在出错时表现出来(界面永远转圈),
 * 而出错路径恰恰是平时测不到的那条。所以 {@code onComplete} 的调用放在
 * {@code doFinally} 里,不放在 {@code doOnComplete} 里。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /**
     * SSE 连接的超时。
     *
     * <p>5 分钟:一次带工具调用的对话最长也就一分钟量级,给到 5 分钟是为了容忍上游慢,
     * 而不是为了支持长连接。设成 0(永不超时)会让每一个被用户关掉的页面都留下一个
     * 挂着的请求线程。
     */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final Orchestrator orchestrator;
    private final SseChannelAdapter adapter;

    public AgentController(Orchestrator orchestrator, SseChannelAdapter adapter) {
        this.orchestrator = orchestrator;
        this.adapter = adapter;
    }

    /**
     * 问一句话,流式拿回答。
     *
     * <p>事件名:{@code run-meta} / {@code token} / {@code tool-call} / {@code tool-result} /
     * {@code error} / {@code done}。前端 {@code EventSource} 挂的就是这几个,改名等于改接口。
     *
     * <p>手工验证(不需要任何密钥,桩会真的去查覆盖率):
     * <pre>
     * curl -N -X POST http://127.0.0.1:8080/api/v1/agent/chat \
     *   -H 'Content-Type: application/json' \
     *   -d '{"message":"我的覆盖率怎么样"}'
     * </pre>
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest body) {
        // userId 暂时恒为 0:agent 这一层还没有接鉴权。
        // 🔴 不是忘了 —— kaodian-agent 的 pom 里【没有】kaodian-auth,它拿不到账号体系。
        // 接的时候要走 CurrentSession(与其它端点同一条路),而不是让 agent 自己去认账号。
        AgentRequest request = new AgentRequest(0L, body.sessionId(), body.message(), body.images());
        Orchestrator.Stream stream = orchestrator.run(request);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        stream.chunks()
                .doFinally(signal -> {
                    // 内容帧发完了,才轮到 done。顺序是 SSE 契约的一部分。
                    AgentResult result = stream.onComplete().get();
                    send(emitter, adapter.doneEvent(result));
                    emitter.complete();
                })
                .subscribe(
                        chunk -> send(emitter, adapter.toEvent(chunk)),
                        error -> {
                            // 走到这里说明连 AgentLlm 的 onErrorResume 都没兜住 —— 属于我们自己的 bug。
                            // 仍然要发一帧给前端,否则界面转圈到超时。
                            log.error("[agent] runId={} 流异常", stream.runId(), error);
                            send(emitter, adapter.toEvent(new AgentChunk.Error(
                                    "kaodian", "unknown", "服务内部错误,请把这串 id 报给我们:" + stream.runId())));
                        });
        return emitter;
    }

    /**
     * 写不进去就说明连接已经断了(用户关了页面),这在 SSE 上是<b>正常结束方式之一</b>。
     * 记 debug 而不是 error —— 用 error 的话,日志里会被用户关页面这件事刷满。
     */
    private void send(SseEmitter emitter, AgentSseEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
        } catch (IOException | IllegalStateException e) {
            log.debug("[agent] SSE 连接已断开,丢弃一帧 event={}", event.event());
        }
    }

    /**
     * @param message   用户这一轮说的话
     * @param sessionId 会话 id;不传则按单轮处理(不读历史)
     * @param images    附带的图片。
     *                  🔴 <b>JSON body + base64 内联,不是 multipart</b> —— 与
     *                  {@code POST /records/{id}/image} 同一形态(docs/technical/INDEX.md §6.2)。
     *                  理由是形态本身:multipart 的 {@code file-size-threshold} 默认会把每个 part
     *                  先写成临时文件,那就等于原图落盘(R-04),而那一条不会报错、也不会
     *                  出现在任何 code review 里。base64 走 JSON 则全程在内存。
     *                  <p>Jackson 会把 base64 字符串直接解成 {@code byte[]},不需要我们手工解码
     */
    public record ChatRequest(String message, String sessionId, List<byte[]> images) {
    }
}
