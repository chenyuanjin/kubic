package com.kaodian.server.agent.entity;

import java.time.Instant;

/**
 * 一次 Agent 调用的执行记录 —— 贯穿七个阶段的那条主线。
 *
 * <p><b>id 是 String,形如 {@code r-<uuid>}。</b>没有跟着 truman-ai 用 long 主键:
 * 那边的 long 里编码着分库 shard 信息,而我们这一层<b>没有库</b>(docs/技术架构 §零:阶段 0/1 是本地文件)。
 * 一个不承载 shard 语义的 long 只是一个更难读的字符串。
 * 前缀跟 {@code CaptureService} 的 {@code "t-" + UUID} 对齐,肉眼一看就知道是哪一类 id。
 *
 * @param runId       本次 run 的 id
 * @param userId      发起人。agent 这一层只认这一个 long,<b>不认识账号、手机号、令牌</b> ——
 *                    kaodian-agent 的 pom 里没有 kaodian-auth,这句话是被 Maven 保证的
 * @param sessionId   会话 id。多轮对话靠它把历史串起来;单轮请求可以传 null
 * @param agentName   本轮 agent 的名字,发到前端用于分帧归属
 * @param modelId     实际使用的模型。<b>可能是 {@code stub}</b> —— 没配密钥时就是它,见 StubAgentLlm
 * @param state       见 {@link RunState}
 * @param startedAt   开始时刻
 * @param completedAt 结束时刻;还在跑时为 null
 */
public record AgentRun(
        String runId,
        long userId,
        String sessionId,
        String agentName,
        String modelId,
        RunState state,
        Instant startedAt,
        Instant completedAt
) {

    /** 起一条新的 RUNNING 记录。 */
    public static AgentRun starting(String runId, long userId, String sessionId,
                                    String agentName, String modelId, Instant now) {
        return new AgentRun(runId, userId, sessionId, agentName, modelId, RunState.RUNNING, now, null);
    }

    /** 收尾:换一个终态 + 盖上结束时刻。record 是不可变的,这里返回新实例。 */
    public AgentRun completed(RunState finalState, Instant now) {
        return new AgentRun(runId, userId, sessionId, agentName, modelId, finalState, startedAt, now);
    }
}
