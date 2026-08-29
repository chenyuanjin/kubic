package com.kaodian.server.agent.session;

import java.time.Instant;

/**
 * 一次会话的元信息。
 *
 * <p><b>它不装对话内容。</b>消息仍然只存在 run 那一侧({@code messages.ndjson}),
 * 这里只有「这个会话叫什么、什么时候开的、有几轮」。
 * 两处各存一份消息是最容易想到、也最容易分叉的做法 ——
 * 与 {@code CoverageReader} 那句「两处算同一个数就一定会算出两个数」是同一条。
 *
 * @param sessionId 会话 id,由客户端生成或服务端补发({@code s-<uuid>})
 * @param userId    归属用户
 * @param title     会话标题。**默认取首条用户消息的前若干字**,用户可改名。
 *                  取首句而不是让模型总结:总结要多花一次模型调用,而这是个列表项,不值当
 * @param runCount  已发生的轮次
 * @param createdAt 首轮时间
 * @param updatedAt 最后一轮时间(列表按它倒序)
 */
public record AgentSession(
        String sessionId,
        long userId,
        String title,
        int runCount,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 标题长度上限。
     *
     * <p>🔴 <b>这个上限是必须的,不是为了好看。</b>标题取自用户输入,而用户输入最长 2000 字
     * ({@code AgentRequest.MAX_MESSAGE_LENGTH})。不截断的话,一条会话记录就能装下
     * 一整段材料 —— 那正是 {@code NoStemFieldTest} 在防的事:<b>把「放个名字」和「放段内容」分在两边</b>。
     * 40 与 {@code LoginFieldLimits} 的 deviceLabel 同档,都是「给人看的一个名字」。
     */
    public static final int MAX_TITLE_LENGTH = 40;

    public AgentSession {
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "会话标题最长 " + MAX_TITLE_LENGTH + " 字 —— 它是个名字,不是放内容的地方");
        }
    }

    /** 从首条用户消息派生一个标题。空消息(纯图片轮次)给一个中性占位。 */
    public static String titleFrom(String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            return "图片提问";
        }
        String t = firstMessage.strip().replaceAll("\\s+", " ");
        return t.length() <= MAX_TITLE_LENGTH ? t : t.substring(0, MAX_TITLE_LENGTH - 1) + "…";
    }

    public AgentSession withTitle(String newTitle) {
        return new AgentSession(sessionId, userId, newTitle, runCount, createdAt, updatedAt);
    }

    public AgentSession touched(Instant now) {
        return new AgentSession(sessionId, userId, title, runCount + 1, createdAt, now);
    }
}
