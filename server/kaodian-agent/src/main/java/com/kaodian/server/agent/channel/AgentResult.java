package com.kaodian.server.agent.channel;

/**
 * 一次 run 的落幕信号,由通道层翻译成 {@code event: done}。
 *
 * @param finishReason 见 {@link FinishReason}
 * @param usage        token 用量。上游没报时为 {@link Usage#EMPTY}(不是 null ——
 *                     「没拿到用量」和「用了 0 个 token」在排查时是两回事,但 null 会让两者
 *                     在下游合并成同一个 NPE)
 */
public record AgentResult(String runId, FinishReason finishReason, Usage usage, long latencyMs) {

    public enum FinishReason {
        SUCCESS,
        /** 工具轮次撞上上限。已产出的正文仍然有效。 */
        TOOL_LOOP_LIMIT,
        ERROR
    }

    public record Usage(long promptTokens, long completionTokens, long totalTokens) {
        public static final Usage EMPTY = new Usage(0, 0, 0);
    }
}
