package com.kaodian.server.agent.orchestrator;

/**
 * 七阶段控制流的标记 —— 照搬 truman-ai §4.1,因为这个划分本身是对的。
 *
 * <p>每个阶段在 {@code events.ndjson} 里对应一组 {@code {phase}.{动作}} 事件,
 * 于是「这一轮卡在哪一步」是一个可以从磁盘上读出来的问题。
 *
 * <ul>
 *   <li>{@link #RECEIVE} —— 通道入站,归一成 AgentRequest</li>
 *   <li>{@link #ADMIT} —— 准入:参数校验。<b>今天很薄</b>(没有 policy、没有敏感词门),
 *       但阶段留着,因为将来第一个要加的东西一定落在这里</li>
 *   <li>{@link #BOOT} —— 建 run 档、读历史、装配 system prompt</li>
 *   <li>{@link #PLAN} —— 模型决策</li>
 *   <li>{@link #ACT} —— 工具调度(实际执行点在 AgentToolBridge)</li>
 *   <li>{@link #CONCLUDE} —— 落档收尾</li>
 *   <li>{@link #EMIT} —— 通过通道把帧发回去</li>
 * </ul>
 *
 * <p>PLAN 与 ACT 在我们这里<b>交织发生</b>(都在 spring-ai 的流里),不是两个前后相继的区间。
 * 保留两个名字是因为它们在<b>轨迹事件</b>上仍然分得开,而分得开就有排查价值。
 */
public enum OrchestratorPhase {
    RECEIVE,
    ADMIT,
    BOOT,
    PLAN,
    ACT,
    CONCLUDE,
    EMIT;

    /** 事件名前缀,小写。{@code boot.done} / {@code act.tool.coverage_summary.end} 就是这么拼的。 */
    public String prefix() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
