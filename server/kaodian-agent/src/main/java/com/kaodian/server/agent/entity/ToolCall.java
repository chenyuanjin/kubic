package com.kaodian.server.agent.entity;

import com.kaodian.server.agent.tool.spi.ToolLevel;

import java.time.Instant;

/**
 * 一次工具调用的完整生命周期 —— agent 这一层的基本可观测单元。
 *
 * <p>为什么单独落一份(消息里已经有 ToolCallPart / ToolResultPart 了):
 * 那两个 part 是<b>给模型看的对话记录</b>,这一条是<b>给我们看的执行记录</b>。
 * 只有这里才有耗时、失败原因和 {@link ToolLevel} —— 而「哪个工具最慢 / 最常失败」
 * 是提示词写坏了之后唯一能查的东西。
 *
 * @param llmCallId LLM 协议侧的 id({@code call_abc123}),用来和模型的 tool_calls 配对
 * @param level     R / C / E。落进存储是为了让「这一轮有没有动过写操作」可以事后审计,
 *                  即使今天 E 类工具一个都还没有
 */
public record ToolCall(
        String callId,
        String runId,
        String llmCallId,
        String toolName,
        ToolLevel level,
        String arguments,
        String result,
        boolean error,
        Instant startedAt,
        Instant completedAt
) {

    public long durationMs() {
        if (startedAt == null || completedAt == null) {
            return 0L;
        }
        return java.time.Duration.between(startedAt, completedAt).toMillis();
    }
}
