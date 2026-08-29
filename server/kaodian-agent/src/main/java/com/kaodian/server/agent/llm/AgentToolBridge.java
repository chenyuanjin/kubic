package com.kaodian.server.agent.llm;

import com.kaodian.server.agent.channel.AgentChunk;
import com.kaodian.server.agent.channel.RunChunkBus;
import com.kaodian.server.agent.entity.ToolCall;
import com.kaodian.server.agent.storage.RunRepository;
import com.kaodian.server.agent.tool.spi.AtomicToolRegistry;
import com.kaodian.server.agent.tool.spi.ToolMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <b>Act 阶段的执行点。</b>工具真正被调起来的那一下,发生在这里。
 *
 * <h2>为什么是包一层而不是自己写一遍</h2>
 *
 * 工具执行本身(解析入参 JSON、反射调方法、异常翻译成给模型看的文本)是一堆
 * 又琐碎又容易写错的活,spring-ai 的 {@code DefaultToolCallingManager} 已经做好了。
 * 重写一遍换不来任何东西。
 * <p>
 * 但我们需要在它<b>前后</b>插进三件事,而这三件事是 agent 架构里最值钱的部分:
 * <ol>
 *   <li><b>门控</b> —— 不在工具池白名单里的,拒绝执行(见 {@link AtomicToolRegistry});
 *       EFFECT 级的,今天一律拒绝</li>
 *   <li><b>发帧</b> —— tool-call / tool-result 按发生顺序混进正文流,前端才能显示「正在查覆盖率」</li>
 *   <li><b>落档</b> —— 每次调用连同耗时写进 {@code toolCalls.ndjson},
 *       「哪个工具最慢、最常失败」事后可查</li>
 * </ol>
 *
 * <h2>🔴 门控在执行之前,不在之后</h2>
 *
 * 顺序是有意义的:先查白名单再委托执行。反过来写(先执行、发现不该执行再报错)
 * 在今天没有区别(工具都是只读的),在有了第一个 EFFECT 工具的那天就是数据事故 ——
 * 副作用已经发生了,再拒绝也收不回来。
 */
public class AgentToolBridge implements ToolCallingManager {

    /** run id 在 spring-ai toolContext 里的键。只传这一个 String,不传任何带状态的对象。 */
    static final String CONTEXT_RUN_ID = "kaodian.runId";
    static final String CONTEXT_AGENT_NAME = "kaodian.agentName";

    private final ToolCallingManager delegate;
    private final AtomicToolRegistry registry;
    private final RunRepository runRepo;
    private final RunChunkBus bus;

    public AgentToolBridge(ToolCallingManager delegate, AtomicToolRegistry registry,
                           RunRepository runRepo, RunChunkBus bus) {
        this.delegate = delegate;
        this.registry = registry;
        this.runRepo = runRepo;
        this.bus = bus;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        String runId = contextValue(prompt, CONTEXT_RUN_ID);
        String agentName = contextValue(prompt, CONTEXT_AGENT_NAME);
        List<AssistantMessage.ToolCall> requested = requestedCalls(response);

        // —— ① 门控:先看白名单,再决定要不要执行 ——
        List<AssistantMessage.ToolCall> refused = requested.stream()
                .filter(tc -> !allowed(tc.name()))
                .toList();
        if (!refused.isEmpty()) {
            return refuse(prompt, response, refused, runId, agentName);
        }

        // —— ② 发 tool-call 帧 + 记开始时刻 ——
        Instant startedAt = Instant.now();
        for (AssistantMessage.ToolCall tc : requested) {
            ToolMetadata meta = registry.find(tc.name());
            bus.emit(runId, new AgentChunk.ToolCall(
                    agentName, tc.id(), tc.name(), meta.label(), meta.level().name()));
        }

        // —— ③ 委托给 spring-ai 真正执行 ——
        ToolExecutionResult result = delegate.executeToolCalls(prompt, response);

        // —— ④ 发 tool-result 帧 + 落档 ——
        record(result, requested, runId, agentName, startedAt);
        return result;
    }

    /**
     * 白名单判据。
     *
     * <p>注册表里没有 = 不执行。这条在今天几乎不可能触发(工具 schema 就是从注册表生成的),
     * 留着是因为「模型编造了一个工具名」这件事在别处真实发生过,
     * 而它的表现是一次<b>看起来正常的执行</b> —— 如果没有这道判断的话。
     */
    private boolean allowed(String toolName) {
        ToolMetadata meta = registry.find(toolName);
        if (meta == null) {
            return false;
        }
        // EFFECT 今天一律拒绝。这道门必须在第一个 E 类工具出现【之前】就存在 ——
        // 等到有了再补,补的人正是想加它的那个人(见 ToolLevel.EFFECT 的注释)。
        return !meta.isEffect();
    }

    /**
     * 拒绝执行:把拒绝理由当成工具的返回值交回给模型,让它自己换个说法。
     *
     * <p>不是抛异常 —— 抛异常会中断整条流,而这件事对用户来说完全可以恢复:
     * 模型收到「没有这个工具」之后通常会改用一个真的存在的工具。
     */
    private ToolExecutionResult refuse(Prompt prompt, ChatResponse response,
                                       List<AssistantMessage.ToolCall> refused,
                                       String runId, String agentName) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : refused) {
            ToolMetadata meta = registry.find(tc.name());
            String reason = meta == null
                    ? "没有名为「" + tc.name() + "」的工具。请只使用系统提示里列出的那些。"
                    : "工具「" + tc.name() + "」是写操作,当前不允许调用。";
            responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), reason));
            bus.emit(runId, new AgentChunk.ToolResult(
                    agentName, tc.id(), tc.name(), meta == null ? tc.name() : meta.label(), true));
            appendToolCall(runId, tc, reason, true, Instant.now(), Instant.now(), meta);
        }

        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(response.getResult().getOutput());
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder().conversationHistory(history).build();
    }

    /** 从执行结果里把 tool response 捞出来发帧 + 落档。 */
    private void record(ToolExecutionResult result, List<AssistantMessage.ToolCall> requested,
                        String runId, String agentName, Instant startedAt) {
        Instant completedAt = Instant.now();
        List<Message> history = result.conversationHistory();
        if (history.isEmpty()) {
            return;
        }
        Message last = history.get(history.size() - 1);
        if (!(last instanceof ToolResponseMessage trm)) {
            return;
        }
        for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
            ToolMetadata meta = registry.find(r.name());
            // spring-ai 把工具内部抛出的异常翻译成一段以错误信息开头的文本交回模型。
            // 我们没法从这里区分「工具正常返回的一段话」和「异常被翻译成的一段话」,
            // 所以 error 一律记 false —— 宁可少标一个错,也不要把正常返回误标成失败,
            // 那会让「哪个工具最常失败」这个数从第一天起就不能看。
            bus.emit(runId, new AgentChunk.ToolResult(
                    agentName, r.id(), r.name(), meta == null ? r.name() : meta.label(), false));
            AssistantMessage.ToolCall origin = requested.stream()
                    .filter(tc -> tc.id().equals(r.id()))
                    .findFirst().orElse(null);
            appendToolCall(runId, origin, r.responseData(), false, startedAt, completedAt, meta);
        }
    }

    private void appendToolCall(String runId, AssistantMessage.ToolCall origin, String result,
                                boolean error, Instant startedAt, Instant completedAt, ToolMetadata meta) {
        if (runId == null) {
            return;
        }
        runRepo.appendToolCall(new ToolCall(
                "c-" + UUID.randomUUID(),
                runId,
                origin == null ? null : origin.id(),
                origin == null ? (meta == null ? null : meta.name()) : origin.name(),
                meta == null ? null : meta.level(),
                origin == null ? null : origin.arguments(),
                result,
                error,
                startedAt,
                completedAt));
    }

    private static List<AssistantMessage.ToolCall> requestedCalls(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> calls = response.getResult().getOutput().getToolCalls();
        return calls == null ? List.of() : calls;
    }

    private static String contextValue(Prompt prompt, String key) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolContext() != null) {
            Object value = options.getToolContext().get(key);
            return value == null ? null : value.toString();
        }
        return null;
    }
}
