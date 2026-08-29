package com.kaodian.server.agent.entity;

import java.time.Instant;
import java.util.List;

/**
 * run 下的一条消息。
 *
 * @param iteration 推理轮次。一次 run 内 user → assistant → tool → assistant 可能来回多轮,
 *                  这个数让「模型在第几轮上开始打转」在事后可数
 * @param role      user / assistant / tool
 * @param parts     有序的 {@link MessagePart}
 */
public record AgentMessage(
        String messageId,
        String runId,
        Role role,
        int iteration,
        List<MessagePart> parts,
        Instant createdAt
) {

    public enum Role { USER, ASSISTANT, TOOL }

    public AgentMessage {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
