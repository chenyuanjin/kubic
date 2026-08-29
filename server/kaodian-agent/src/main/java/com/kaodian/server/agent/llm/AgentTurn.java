package com.kaodian.server.agent.llm;

import com.kaodian.server.agent.entity.AgentMessage;

import java.util.List;

/**
 * 交给模型跑一轮所需要的全部东西 —— {@link AgentLlm} 的入参。
 *
 * <p>刻意<b>不含</b> spring-ai 的任何类型:{@code history} 是我们自己的 {@link AgentMessage},
 * 不是 {@code org.springframework.ai.chat.messages.Message}。翻译发生在 {@link OpenAiAgentLlm}
 * 内部,那是这个模块里唯一允许认识 spring-ai 的地方(见 {@link AgentLlm} 的类注释)。
 *
 * @param runId        本轮 run id。工具执行时要靠它把帧推回正确的那条流
 * @param systemPrompt 见 {@code AgentPrompt}
 * @param history      之前几轮的对话,按时间升序。单轮对话时为空列表
 * @param userMessage  用户这一轮说的话
 * @param images       本轮附的图片字节。
 *                     🔴 <b>只有本轮有图</b> —— {@code history} 里【永远没有图】,
 *                     因为图不落盘(R-04),历史里只留「那一轮带了几张图」的文字痕迹。
 *                     这是多模态选型的已知代价,记在 docs/08 R-89
 */
public record AgentTurn(
        String runId,
        String agentName,
        String systemPrompt,
        List<AgentMessage> history,
        String userMessage,
        List<byte[]> images
) {
    /** 无图轮次。 */
    public AgentTurn(String runId, String agentName, String systemPrompt,
                     List<AgentMessage> history, String userMessage) {
        this(runId, agentName, systemPrompt, history, userMessage, List.of());
    }

    public AgentTurn {
        history = history == null ? List.of() : List.copyOf(history);
        images = images == null ? List.of() : List.copyOf(images);
    }
}
