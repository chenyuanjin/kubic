package com.kaodian.server.agent.storage;

import com.kaodian.server.agent.entity.AgentMessage;
import com.kaodian.server.agent.entity.AgentRun;
import com.kaodian.server.agent.entity.ToolCall;
import com.kaodian.server.agent.entity.TraceEvent;

import java.util.List;
import java.util.Optional;

/**
 * Run 的落档接口。
 *
 * <p>只有一个实现({@code FileRunRepository}),接口仍然要有 —— 理由与 {@code TouchStore} 一样:
 * 换 JDBC 实现的那天,编排层一行都不用改。而那一天到来之前,
 * 接口的存在让「编排层不认识文件系统」成为一件被编译器保证的事。
 *
 * <h2>为什么写入路径是四个 append 而不是一个 save(run)</h2>
 *
 * 一次 run 是<b>流式</b>产生的:token 一个个来、工具一个个调。等到结束再整体写一次,
 * 意味着中途崩掉的那些 run 在磁盘上什么都不会留下 —— 而中途崩掉的正是最需要排查的那些。
 */
public interface RunRepository {

    /* ——————————————— 写 ——————————————— */

    /** 建档或更新状态。同一个 runId 重复调用是覆盖写(run.json 整体重写)。 */
    void saveRun(AgentRun run);

    void appendMessage(AgentMessage message);

    void appendToolCall(ToolCall call);

    void appendEvent(TraceEvent event);

    /* ——————————————— 读 ——————————————— */

    Optional<AgentRun> findRun(String runId);

    /** 某会话下的全部 run,按开始时间<b>升序</b>(多轮历史要按发生顺序回放)。 */
    List<AgentRun> findRunsBySession(String sessionId);

    List<AgentMessage> listMessages(String runId);

    List<ToolCall> listToolCalls(String runId);

    List<TraceEvent> listEvents(String runId);
}
