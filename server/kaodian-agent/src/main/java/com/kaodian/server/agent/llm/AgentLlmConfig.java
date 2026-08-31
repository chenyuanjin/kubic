package com.kaodian.server.agent.llm;

import com.kaodian.server.agent.channel.RunChunkBus;
import com.kaodian.server.agent.storage.RunRepository;
import com.kaodian.server.agent.tool.spi.AgentTool;
import com.kaodian.server.agent.tool.spi.AtomicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * agent 的模型接入装配点。
 *
 * <h2>没配密钥 = 桩生效,而不是启动失败</h2>
 *
 * 与 {@code AuthBeans} 里那套供应商开关同一形态:默认配置是一个
 * <b>零成本、零外部依赖</b>的本机组合 —— 不配 {@code KAODIAN_MODEL_KEY} 就跑
 * {@link StubAgentLlm},SSE / 工具 / 落盘三条链路照样端到端通,只是没有模型在组织语言。
 * <p>
 * 为什么不像 {@code AuthBeans#checkVendorPairing} 那样做成「配错了就拒绝启动」:
 * 那一条防的是「真发短信 + 不校验滑块」这种<b>会花钱</b>的组合。这里没有对应的危险组合 ——
 * 配了 key 就问模型,没配就不问,两种都不会产生意外账单。
 */
@Configuration
public class AgentLlmConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentLlmConfig.class);

    /**
     * Act 阶段的执行点。
     *
     * <p><b>轮次上限有两道,一道是主的,一道是保险 —— 别把它们看反了。</b>
     * <ul>
     *   <li><b>主闸</b>在 {@code OpenAiAgentLlm.roundTrip}:ReAct 循环是我们自己跑的,
     *       撞上 {@code max-tool-rounds} 就停止续轮,并给用户发一帧说明。
     *       <b>不是抛异常</b> —— 已经产出的正文对用户仍然有效。</li>
     *   <li><b>保险</b>是这里的 {@code maxTotalToolCalls}:它拦的是<u>单次</u>
     *       {@code executeToolCalls} 里模型一口气要求调几十个工具的情况,
     *       与「轮数」是两个维度。</li>
     * </ul>
     *
     * <p>⚠ 这段注释原本写的是「撞上之后 spring-ai 停止继续调工具」——<b>那是错的</b>,
     * 建立在「spring-ai 自己驱动 ReAct 循环」这个后来被证伪的假设上
     * (见 {@code OpenAiAgentLlm} 类注释)。留在这里作为提醒:
     * <b>一段描述错机制的注释比没有注释更糟</b>,它会让下一个人去错误的地方调参数。
     */
    @Bean
    public ToolCallingManager agentToolCallingManager(AtomicToolRegistry registry,
                                                      RunRepository runRepo,
                                                      RunChunkBus bus,
                                                      @Value("${kaodian.agent.max-tool-rounds:4}") int maxToolRounds) {
        ToolCallingManager delegate = ToolCallingManager.builder()
                .maxTotalToolCalls(Math.max(1, maxToolRounds))
                .build();
        return new AgentToolBridge(delegate, registry, runRepo, bus);
    }

    /**
     * 🔴 <b>整个应用里 {@code ChatModel} 的唯一注入点。</b>
     *
     * <p>用 {@link ObjectProvider} 而不是直接注入:密钥没配时我们要能拿到「没有可用模型」
     * 这个事实,而不是让上下文在装配期就失败。docs/后端详设 §4.2 记过一次实测 ——
     * 没给 api-key 时上下文会炸在 {@code OpenAiAudioSpeechModel} 上;
     * 那个模型现在已经被 {@code spring.autoconfigure.exclude} 排掉了,
     * 但「不要把可选的外部依赖做成硬装配」这条教训照样适用。
     */
    @Bean
    public AgentLlm agentLlm(ObjectProvider<ChatModel> chatModels,
                             ToolCallingManager toolCallingManager,
                             AtomicToolRegistry registry,
                             RunChunkBus bus,
                             RunRepository runRepo,
                             List<AgentTool> tools,
                             @Value("${spring.ai.openai.api-key:}") String apiKey,
                             @Value("${spring.ai.openai.chat.options.model:unknown}") String modelId,
                             @Value("${spring.ai.openai.chat.options.temperature:0.0}") Double temperature,
                             @Value("${kaodian.agent.max-tool-rounds:4}") int maxToolRounds) {

        ChatModel chatModel = chatModels.getIfAvailable();
        boolean configured = apiKey != null && !apiKey.isBlank();

        if (!configured || chatModel == null) {
            log.warn("""
                    Agent 未接入模型,由本地桩接管({} 个工具仍然真实执行)。
                    原因:{}。配置 KAODIAN_MODEL_KEY 后自动切换到真实模型。""",
                    registry.size(),
                    !configured ? "没有配 spring.ai.openai.api-key" : "容器里没有 ChatModel bean");
            return new StubAgentLlm(tools, runRepo);
        }

        log.info("Agent 接入模型 {},工具池 {} 个。", modelId, registry.size());
        return new OpenAiAgentLlm(chatModel, toolCallingManager, registry, bus, modelId, temperature, maxToolRounds);
    }
}
