package com.kaodian.server.agent.channel;

/**
 * 流式产物的一帧。编排层产出 {@code AgentChunk},通道层把它翻译成各自的原生格式
 * (今天只有 {@link AgentSseEvent})。
 *
 * <p>sealed:通道层用穷尽 switch 翻译,新增一种帧时编译器会点出所有该改的地方。
 * truman-ai 那边是 10 种帧(含 a2ui / ui_part / ask_user / citation 等),
 * 这里只留 5 种 —— 其余那些对应的是它那边的 UI 协议与澄清提问机制,我们一个都还没有。
 */
public sealed interface AgentChunk {

    /** 本帧属于哪个 agent。多 agent 并存时前端据此分组;今天恒为 "kaodian"。 */
    String agentName();

    /**
     * 流的第一帧,携带 runId。
     *
     * <p>放在最前面是为了让前端在<b>任何内容出现之前</b>就拿到 runId ——
     * 出问题时用户能报出的那串东西,不该等到流结束才下发。
     */
    record RunMeta(String agentName, String runId, String modelId) implements AgentChunk {}

    /** 正文增量。 */
    record Token(String agentName, String delta) implements AgentChunk {}

    /**
     * 模型开始调一个工具。
     *
     * @param label 工具中文名({@code @AtomicTool.displayName}),让前端显示「正在查覆盖率」
     *              而不是「正在使用 coverage_summary」
     */
    record ToolCall(String agentName, String id, String name, String label, String level) implements AgentChunk {}

    /** 工具返回。{@code error} 为真时前端应显示为失败而不是把错误文本当答案渲染。 */
    record ToolResult(String agentName, String id, String name, String label, boolean error) implements AgentChunk {}

    /**
     * 这一轮出错了。
     *
     * <p>🔴 有这一帧的意义在于:错误要<b>作为一帧正常发出去然后正常结束流</b>,
     * 而不是让 SSE 直接断开。断开的话前端只能显示「network error」,
     * 用户和排查的人都不知道发生了什么 —— 而最常见的那个错误(上游 429 限流)
     * 恰恰是一句话就能说清楚的。
     *
     * @param code llm_unavailable / llm_error / tool_failure / unknown
     */
    record Error(String agentName, String code, String message) implements AgentChunk {}
}
