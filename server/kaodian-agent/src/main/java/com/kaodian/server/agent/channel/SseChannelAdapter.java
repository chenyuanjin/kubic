package com.kaodian.server.agent.channel;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 把 {@link AgentChunk} 翻译成 SSE 事件。
 *
 * <h2>事件名就是前端的契约</h2>
 *
 * {@code run-meta} / {@code token} / {@code tool-call} / {@code tool-result} / {@code error} / {@code done}。
 * 六个,一个不多。前端 {@code EventSource.addEventListener} 挂的就是这几个名字,
 * <b>改名等于改接口</b> —— 与 REST 端点改路径是一回事,不要因为「只是个事件名」就随手改。
 *
 * <h2>🔴 逐字段写,不用自动序列化</h2>
 *
 * 与 {@code FileTouchStore#toNode}、{@code FileRunRepository} 同一条纪律:
 * 下发给前端的 JSON 里能出现哪些键,由下面每个 {@code put} 显式列出。
 * 自动序列化的话,哪天 {@link AgentChunk.ToolResult} 上多一个字段(比如顺手把工具的原始返回塞进去),
 * 它就<b>自动流到了前端</b>,而没有任何一行代码或断言会提到这件事。
 */
@Component
public class SseChannelAdapter {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 一帧内容 → 一条 SSE 事件。 */
    public AgentSseEvent toEvent(AgentChunk chunk) {
        return switch (chunk) {
            case AgentChunk.RunMeta m -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("agent", m.agentName());
                n.put("runId", m.runId());
                n.put("modelId", m.modelId());
                yield new AgentSseEvent("run-meta", json(n));
            }
            case AgentChunk.Token t -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("agent", t.agentName());
                n.put("delta", t.delta());
                yield new AgentSseEvent("token", json(n));
            }
            case AgentChunk.ToolCall c -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("agent", c.agentName());
                n.put("id", c.id());
                n.put("name", c.name());
                n.put("label", c.label());
                n.put("level", c.level());
                // 🔴 刻意【不】下发 arguments。模型给的入参原文我们落盘留证(ToolCall 实体里有),
                // 但没有理由让它出现在浏览器里:它是排查材料,不是界面内容,
                // 而下发过去之后就再也收不回来了。
                yield new AgentSseEvent("tool-call", json(n));
            }
            case AgentChunk.ToolResult r -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("agent", r.agentName());
                n.put("id", r.id());
                n.put("name", r.name());
                n.put("label", r.label());
                n.put("error", r.error());
                // 同上:工具返回的正文不下发。前端要展示的是「查了覆盖率」这件事,
                // 内容会由模型在随后的 token 帧里自己说出来。
                yield new AgentSseEvent("tool-result", json(n));
            }
            case AgentChunk.Error e -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("agent", e.agentName());
                n.put("code", e.code());
                n.put("message", e.message());
                yield new AgentSseEvent("error", json(n));
            }
        };
    }

    /**
     * 落幕帧。
     *
     * <p>无论成功失败都要发 —— 前端靠它关掉 loading。只在异常路径上漏发一次,
     * 界面就会永远转圈,而那种 bug 只在出错时出现,平时测不出来。
     */
    public AgentSseEvent doneEvent(AgentResult result) {
        ObjectNode n = mapper.createObjectNode();
        n.put("runId", result.runId());
        n.put("finishReason", result.finishReason().name());
        n.put("latencyMs", result.latencyMs());
        ObjectNode usage = n.putObject("usage");
        usage.put("promptTokens", result.usage().promptTokens());
        usage.put("completionTokens", result.usage().completionTokens());
        usage.put("totalTokens", result.usage().totalTokens());
        return new AgentSseEvent("done", json(n));
    }

    private String json(ObjectNode node) {
        return mapper.writeValueAsString(node);
    }
}
