package com.kaodian.server.agent.llm;

import com.kaodian.server.agent.channel.AgentChunk;
import com.kaodian.server.agent.entity.ToolCall;
import com.kaodian.server.agent.storage.RunRepository;
import com.kaodian.server.agent.tool.spi.AgentTool;
import com.kaodian.server.agent.tool.spi.AtomicTool;
import com.kaodian.server.agent.tool.spi.ToolMetadata;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 没配模型密钥时生效的实现。
 *
 * <h2>🔴 它不假装自己是模型</h2>
 *
 * {@code StubAsrClient} 那一层的纪律是「没配密钥 = 诚实失败,不是静默降级成假数据」。
 * 这里稍有不同,而这点不同是想清楚的:
 * <p>
 * ASR 的 stub 只能失败 —— 它没有别的东西可给,编一段转写文本就是<b>凭空造用户数据</b>。
 * 而 agent 这一层不一样:用户问的那些数<b>本来就都在本地</b>(覆盖率、盲区、最近记录),
 * 少的只是把它们组织成一段话的那个模型。所以这个 stub 做的是:
 * <b>真的去调工具,把查到的真数据原样报出来,并且明说自己不是模型。</b>
 * <p>
 * 它没有编造任何一个数字 —— 每个数都来自真实的 {@code CoverageReader}。
 * 它也没有假装有语言能力:输出是一份工具返回的直排,不是一段话。
 *
 * <h2>为什么这件事值得做</h2>
 *
 * 因为它让「SSE 链路、工具链路、落盘链路」在<b>一分钱都不花、一个密钥都不配</b>的情况下
 * 端到端可验证。docs/13 §6 记着默认配置下整个鉴权层不花一分钱;agent 这一层照此办理。
 * 没有它的话,想验证一次 SSE 就得先去开一个模型账号 —— 而那正是 04 反对的那种
 * 「在验证之前先花钱」。
 */
public class StubAgentLlm implements AgentLlm {

    private final List<AgentTool> tools;
    private final RunRepository runRepo;

    /**
     * 🔴 <b>不注入 {@code RunChunkBus}</b> —— 这一点与 {@link OpenAiAgentLlm} 不同,是想清楚的。
     *
     * <p>总线的存在理由是「工具被 spring-ai 从<b>别的执行点</b>同步调起,那里拿不到当前这条 Flux」。
     * 桩这边工具就是自己按顺序调的,帧直接按序放进流里即可。
     * <p>写这个类时一开始照抄了真实实现的 {@code bus.emit(...)},结果是
     * <b>tool-call / tool-result 两帧全部被静默丢弃</b> —— 因为桩从来没有 register 过 sink,
     * 而 {@code RunChunkBus#emit} 找不到 sink 时的设计行为正是安静丢弃。
     * 第一次端到端跑 SSE 时才发现:流里只有 token,没有工具帧。
     * 留个记录:<b>一个「找不到就安静丢弃」的通道,配上「忘了注册」,失败是完全无声的。</b>
     */
    public StubAgentLlm(List<AgentTool> tools, RunRepository runRepo) {
        this.tools = tools;
        this.runRepo = runRepo;
    }

    @Override
    public String modelId() {
        // 🔴 落进 AgentRun.modelId 的就是这个字符串。事后翻记录时,
        // 「这一轮到底有没有真的问过模型」必须一眼可辨。
        return "stub-no-model-key";
    }

    @Override
    public Flux<AgentChunk> converse(AgentTurn turn) {
        return Flux.defer(() -> {
            String agent = turn.agentName();
            List<AgentChunk> chunks = new java.util.ArrayList<>();

            chunks.add(new AgentChunk.Token(agent, """
                    【未配置模型密钥,当前由本地桩接管】
                    没有模型可以组织语言,所以下面直接把工具查到的原始结果列出来。
                    数据是真的,措辞不是模型写的。

                    """));

            // 真的调一次覆盖率工具。选它是因为它无参、且正好是这个产品的那个数。
            invoke("coverage_summary", turn, agent, chunks);

            chunks.add(new AgentChunk.Token(agent, """

                    ——
                    配置 KAODIAN_MODEL_KEY 之后,这里会变成模型根据同一批工具结果给出的回答。
                    """));
            return Flux.fromIterable(chunks)
                    // 一点点延迟,让 SSE 的分帧在手工验证时真的看得出来是「流」而不是一次性吐完。
                    .delayElements(Duration.ofMillis(30));
        });
    }

    /** 反射调一个无参工具,顺便把 tool-call / tool-result 两帧和落档都走一遍真实路径。 */
    private void invoke(String toolName, AgentTurn turn, String agent, List<AgentChunk> chunks) {
        for (AgentTool tool : tools) {
            for (Method m : tool.getClass().getMethods()) {
                AtomicTool atomic = m.getAnnotation(AtomicTool.class);
                var springAi = m.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                if (atomic == null || springAi == null || m.getParameterCount() != 0) {
                    continue;
                }
                String name = springAi.name().isBlank() ? m.getName() : springAi.name();
                if (!toolName.equals(name)) {
                    continue;
                }
                ToolMetadata meta = new ToolMetadata(name, atomic.level(), atomic.displayName(),
                        atomic.noun(), atomic.verb(), springAi.description());
                String callId = "call_stub_" + UUID.randomUUID();
                Instant startedAt = Instant.now();
                chunks.add(new AgentChunk.ToolCall(agent, callId, name, meta.label(), meta.level().name()));
                String result;
                boolean error = false;
                try {
                    result = String.valueOf(m.invoke(tool));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    result = "工具执行失败:" + e.getMessage();
                    error = true;
                }
                chunks.add(new AgentChunk.ToolResult(agent, callId, name, meta.label(), error));
                runRepo.appendToolCall(new ToolCall("c-" + UUID.randomUUID(), turn.runId(), callId,
                        name, meta.level(), "{}", result, error, startedAt, Instant.now()));
                chunks.add(new AgentChunk.Token(agent, result));
                return;
            }
        }
        chunks.add(new AgentChunk.Token(agent, "(工具 " + toolName + " 不在池子里)"));
    }
}
