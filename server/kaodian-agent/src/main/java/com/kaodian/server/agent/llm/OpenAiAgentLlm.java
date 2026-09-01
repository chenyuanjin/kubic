package com.kaodian.server.agent.llm;

import com.kaodian.server.agent.channel.AgentChunk;
import com.kaodian.server.agent.channel.RunChunkBus;
import com.kaodian.server.agent.entity.AgentMessage;
import com.kaodian.server.agent.entity.MessagePart;
import com.kaodian.server.agent.tool.spi.AtomicToolRegistry;
import com.kaodian.server.recognize.ImageMime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真·模型实现。
 *
 * <p>🔴 <b>整个仓库里 {@code ChatModel} 只在这一个类的这一个字段上出现</b>
 * (另一处是 {@code recognize} 包将来的 {@code OpenAiVisionTagger},docs/technical/后端系统设计与组件接入.md §4.1)。
 * 这不是巧合,是 {@link AgentLlm} 那条接口存在的全部理由 —— 详见它的类注释。
 *
 * <h2>ReAct 循环在我们手里 —— 这一点起初判断错了</h2>
 *
 * 最初的设计假设是「{@code chatModel.stream()} 内部会自己跑 ReAct 循环,我们只要通过
 * 一个自定义 {@code ToolCallingManager} 接管执行的那一下」。<b>这个假设是错的。</b>
 * 反编译 {@code OpenAiChatModel} 可以看到:它只调 {@code resolveToolDefinitions}
 * (把工具描述发给模型),<b>从头到尾没有调用过 {@code executeToolCalls}</b> ——
 * spring-ai 2.x 的 ChatModel 层不再自己续轮,那是调用方的事(1.x 里是它自己做的)。
 * <p>
 * 症状很有迷惑性:纯聊天完全正常(「你好」能流式回 114 字),<b>只有需要查数据的问题会静静地回一句空</b>
 * —— 模型返回了 tool_calls,没人执行,流就结束了。既没有异常也没有日志。
 * <p>
 * 所以循环写在 {@link #roundTrip} 里。这反而回到了正确的位置:Act 阶段本来就该由编排侧掌控
 * (truman-ai §4.1 的原意),门控、发帧、落档也才有地方挂 —— 见 {@link AgentToolBridge}。
 */
public class OpenAiAgentLlm implements AgentLlm {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAgentLlm.class);

    private final ChatModel chatModel;
    /** Act 阶段的执行点(实际是 {@link AgentToolBridge})—— 门控、发帧、落档都在它里面。 */
    private final ToolCallingManager toolCallingManager;
    private final AtomicToolRegistry registry;
    private final RunChunkBus bus;
    private final String modelId;
    private final Double temperature;
    private final int maxToolRounds;

    public OpenAiAgentLlm(ChatModel chatModel, ToolCallingManager toolCallingManager,
                          AtomicToolRegistry registry, RunChunkBus bus,
                          String modelId, Double temperature, int maxToolRounds) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.registry = registry;
        this.bus = bus;
        this.modelId = modelId;
        this.temperature = temperature;
        this.maxToolRounds = maxToolRounds;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public Flux<AgentChunk> converse(AgentTurn turn) {
        // multicast + onBackpressureBuffer:工具帧是 AgentToolBridge 从执行点塞进来的(见 RunChunkBus),
        // unicast 在这种用法下会因为「订阅前就有人 emit」而丢帧。
        Sinks.Many<AgentChunk> toolFrames = Sinks.many().multicast().onBackpressureBuffer();
        bus.register(turn.runId(), toolFrames);

        Flux<AgentChunk> body = roundTrip(turn, messagesOf(turn), 1)
                // 🔴 正文流一结束,就【立刻】关掉工具帧流。
                //
                // 这一行是防死锁的:merge 要等【两个】上游都 complete。工具帧是一个 Sink,
                // 自己永远不会结束 —— 必须有人替它按下结束键。原先这行写在 merge 之后的
                // doFinally 里,于是形成闭环:merge 等 toolFrames → toolFrames 等 doFinally
                // → doFinally 等 merge。表现是【SSE 只发出 run-meta 然后永久挂起】,
                // 没有异常、没有日志、没有超时(2026-08-28 实测)。
                .doFinally(signal -> toolFrames.tryEmitComplete());

        // 正文与工具帧合流。用 merge 而不是 concat:两者是【交错】发生的
        // (说几个字 → 查一下 → 接着说),concat 会把工具帧全压到最后,
        // 那样「正在查覆盖率」就失去了意义。
        return Flux.merge(body, toolFrames.asFlux())
                // 🔴 注销必须在 finally 里 —— 见 RunChunkBus 的类注释。
                .doFinally(signal -> bus.unregister(turn.runId()))
                .onErrorResume(error -> Flux.just(translate(error, turn.agentName())));
    }

    /**
     * 一轮「问模型 → 若要工具就执行 → 带着结果再问」。递归直到模型不再要工具。
     *
     * <p><b>为什么用 concatWith + defer 而不是先收集再判断</b>:正文要<u>边出边发</u>。
     * 等一轮结束再决定下一步,正文早就流给用户了;而 {@code defer} 保证「要不要续轮」
     * 这个判断发生在<b>本轮流真正结束之后</b>(那时 pendingToolCalls 才填好)。
     *
     * @param round 从 1 开始。撞上 {@link #maxToolRounds} 时停止续轮,但<b>不报错</b> ——
     *              已经产出的正文对用户仍然有效(见 {@code RunState.TOOL_LOOP_LIMIT} 的注释)
     */
    private Flux<AgentChunk> roundTrip(AgentTurn turn, List<Message> messages, int round) {
        Prompt prompt = new Prompt(messages, optionsFor(turn));
        // 模型要调工具时,spring-ai 会把分片聚合好,在【最后一帧】给出完整的 toolCalls
        // (finishReason=TOOL_CALLS)。这里存住那一帧的整个 ChatResponse ——
        // executeToolCalls 要的就是它,自己再拼一个是多余且容易拼错的。
        AtomicReference<ChatResponse> pendingToolCalls = new AtomicReference<>();

        Flux<AgentChunk> tokens = chatModel.stream(prompt)
                .doOnNext(response -> {
                    if (hasToolCalls(response)) {
                        pendingToolCalls.set(response);
                    }
                })
                .mapNotNull(response -> textDelta(response, turn.agentName()));

        return tokens.concatWith(Flux.defer(() -> {
            ChatResponse toolCallResponse = pendingToolCalls.get();
            if (toolCallResponse == null) {
                return Flux.empty();        // 模型没要工具 —— 这一轮就是最终答案
            }
            if (round >= maxToolRounds) {
                // 撞上上限。不静默停下:模型正在原地打转这件事,用户和日志都该看得见。
                log.warn("[agent] runId={} 工具轮次撞上上限 {},停止续轮", turn.runId(), maxToolRounds);
                return Flux.just(new AgentChunk.Error(turn.agentName(), "tool_loop_limit",
                        "查询绕了太多轮,先给到这里。可以把问题问得更具体一些。"));
            }
            // 门控 / 发帧 / 落档都在 AgentToolBridge 里。它拿 prompt 里的 toolContext 认 runId。
            ToolExecutionResult executed = toolCallingManager.executeToolCalls(prompt, toolCallResponse);
            // conversationHistory = 原消息 + assistant(tool_calls) + tool 响应,直接拿去问下一轮。
            return roundTrip(turn, new ArrayList<>(executed.conversationHistory()), round + 1);
        }));
    }

    private static boolean hasToolCalls(ChatResponse response) {
        return response != null && response.getResult() != null
                && response.getResult().getOutput() != null
                && response.getResult().getOutput().hasToolCalls();
    }

    /** 本轮请求的 options。每一轮都要重建 —— toolContext 里带着 runId,工具执行点靠它找回这条流。 */
    private OpenAiChatOptions optionsFor(AgentTurn turn) {
        ToolCallback[] callbacks = ToolCallbacks.from(registry.instances().toArray());
        // 🔴 必须是 OpenAiChatOptions,不能用通用的 ToolCallingChatOptions.builder():
        // OpenAiChatModel 会把 prompt 的 options 强制转型成 OpenAiChatOptions,
        // 传通用实现会在第一次真实调用时抛 ClassCastException ——
        // 而桩实现根本不走这条路,所以它一路绿到了第一次拿真 key 发请求的那一刻。
        //
        // toolCallbacks / toolContext 看 javap 会以为 Builder 上没有 ——
        // 它们是从 DefaultToolCallingChatOptions.Builder 继承来的,javap 不列继承方法。
        return OpenAiChatOptions.builder()
                // 🔴 model / temperature 必须在这里显式设一遍,哪怕 application.properties 里配过。
                //
                // Prompt 带的 options 对 ChatModel 的默认 options 是【整体替换】而非逐字段合并 ——
                // 不设 model 的后果不是「回退到配置值」,而是回退到 spring-ai 的内置默认(实测 gpt-5-mini)。
                // 这个 bug 的形态值得记住:日志打印的 modelId 来自 @Value,【是对的】,
                // 而真正发出去的是另一个;表象则是一句完全误导人的
                // 403「This model is not available in your region」——
                // 模型确实不可用,只不过那个模型压根不是我们配的那个。
                // 戳穿它的唯一办法是把请求打到本地服务器上看 body(2026-08-28 就是这么找到的)。
                .model(modelId)
                .temperature(temperature)
                .toolCallbacks(List.of(callbacks))
                // 只放 String —— 带状态的对象(比如 sink 本身)不该进这个会被框架传来传去的 Map。
                .toolContext(Map.of(
                        AgentToolBridge.CONTEXT_RUN_ID, turn.runId(),
                        AgentToolBridge.CONTEXT_AGENT_NAME, turn.agentName()))
                .build();
    }

    /** 一帧流式响应里的正文增量;没有正文(比如这一帧只带 tool_calls)时返回 null 被 mapNotNull 丢掉。 */
    private AgentChunk textDelta(ChatResponse response, String agentName) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        AssistantMessage output = response.getResult().getOutput();
        if (output == null) {
            return null;
        }
        String text = output.getText();
        if (text == null || text.isEmpty()) {
            return null;
        }
        return new AgentChunk.Token(agentName, text);
    }

    /**
     * 把我们的历史翻译成 spring-ai 的消息列表。
     *
     * <p>这个方法是绿线的<b>另一半</b>:进来的是 {@link AgentMessage},出去的才是
     * {@code org.springframework.ai.chat.messages.Message}。翻译只在这里发生,
     * 于是 spring-ai 的类型一步也走不出这个包。
     */
    private List<Message> messagesOf(AgentTurn turn) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(turn.systemPrompt()));

        for (AgentMessage m : turn.history()) {
            switch (m.role()) {
                case USER -> textOf(m).ifPresent(t -> messages.add(new UserMessage(t)));
                case ASSISTANT -> {
                    // 历史里的 assistant 只回放【正文】,不回放它当时的 tool_calls。
                    // 回放 tool_calls 就必须把配对的 tool 响应一起回放,否则 OpenAI 兼容端点会因为
                    // 「有 tool_calls 却没有对应的 tool 消息」直接 400 —— 而那些工具结果
                    // 是上一轮的数据,这一轮重新查一次才是对的(覆盖率随时在变)。
                    textOf(m).ifPresent(t -> messages.add(new AssistantMessage(t)));
                }
                case TOOL -> {
                    // 同上:上一轮的工具结果不回放。
                }
            }
        }

        messages.add(currentUserMessage(turn));
        return messages;
    }

    /**
     * 本轮的用户消息 —— 带图时附上 {@link Media}。
     *
     * <h2>🔴 图片在这里是<b>内联字节</b>,不是 URL</h2>
     *
     * {@code ByteArrayResource} 包一层直接交给 spring-ai,由它编成 base64 随请求体发出。
     * 全程<b>没有任何一步把字节写到磁盘或对象存储</b>,也没有先传给厂商换一个 file_id ——
     * 那条路(DeepSeek Files API / 百炼 oss://dashscope-instant)是 R-04 / R-52 明令禁止的,
     * 而且它看起来像一个白送的优化,所以更要在这里写清楚:<b>不是不用,是不写</b>。
     *
     * <p>⚠ 同样禁止的还有把 {@code turn.images()} 打进日志 —— 一次 {@code log.debug(prompt)}
     * 就等于把原图落了盘。{@code ImageRetentionTest} 扫的就是这类痕迹。
     */
    private UserMessage currentUserMessage(AgentTurn turn) {
        if (turn.images().isEmpty()) {
            return new UserMessage(turn.userMessage());
        }
        List<Media> media = new ArrayList<>();
        for (byte[] image : turn.images()) {
            String mime = ImageMime.of(image);
            if (mime == null) {
                // 认不出格式就【不发】。宁可少发一张,也不要把一段来路不明的字节转给厂商。
                log.warn("[agent] runId={} 跳过一张认不出格式的图片", turn.runId());
                continue;
            }
            media.add(new Media(MimeTypeUtils.parseMimeType(mime), new ByteArrayResource(image)));
        }
        if (media.isEmpty()) {
            return new UserMessage(turn.userMessage());
        }
        return UserMessage.builder()
                .text(turn.userMessage() == null || turn.userMessage().isBlank()
                        ? "（用户发了图片,没有配文字）" : turn.userMessage())
                .media(media)
                .build();
    }

    private static java.util.Optional<String> textOf(AgentMessage m) {
        String text = m.parts().stream()
                .filter(p -> p instanceof MessagePart.TextPart)
                .map(p -> ((MessagePart.TextPart) p).text())
                .reduce("", String::concat);
        return text.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(text);
    }

    /**
     * 把上游异常翻译成一帧 {@link AgentChunk.Error}。
     *
     * <p>最常见的那个是 429(上游限流),它一句话就能说清楚,而不翻译的话
     * 用户看到的是 SSE 断流 + 「network error」。
     *
     * <h2>🔴 分类可以粗,但<b>不能替上游把话说死</b></h2>
     *
     * 这个方法一开始把 401 和 403 合并成一句「模型密钥无效或已过期」。
     * 第一次接真实端点时上游回的是
     * {@code 403: This model is not available in your region} —— <b>密钥是好的,是模型不可用</b>,
     * 而我们的提示会让人径直去换密钥,查半天查不出所以然。
     * <p>
     * 所以现在的规矩是:<b>分类只做路由,原文一律带上</b>。我们对上游状态码的理解总是不完整的
     * (401/403/404 在每家兼容端点上的含义都不太一样),而<u>猜错的代价是把排查引向错误方向</u>,
     * 那比不分类更糟。
     */
    private AgentChunk translate(Throwable error, String agentName) {
        String message = String.valueOf(error.getMessage());
        if (message.contains("429") || message.toLowerCase().contains("rate limit")) {
            return new AgentChunk.Error(agentName, "llm_rate_limited", "模型调用被限流了,稍后再试。");
        }
        if (message.contains("401")) {
            // 401 的含义在各家兼容端点上足够一致:认证没通过。仍然把原文缀上。
            return new AgentChunk.Error(agentName, "llm_unauthorized", "模型密钥未通过认证:" + message);
        }
        if (message.contains("403")) {
            // 🔴 403 【不是】密钥无效。实测见过的至少有:模型在当前区域不可用、
            //    账号没有该模型权限、额度用尽。原文里通常已经写清楚了,原样透出。
            return new AgentChunk.Error(agentName, "llm_forbidden", "模型不可用(上游 403):" + message);
        }
        return new AgentChunk.Error(agentName, "llm_error", "模型调用失败:" + message);
    }

    /** 单轮最多允许的工具调用总数,由 {@code AgentLlmConfig} 配到 ToolCallingManager 上。 */
    int maxToolRounds() {
        return maxToolRounds;
    }
}
