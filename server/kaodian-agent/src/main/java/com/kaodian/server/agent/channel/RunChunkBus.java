package com.kaodian.server.agent.channel;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按 runId 索引的帧总线 —— 让<b>不在流上的代码</b>也能往流里塞一帧。
 *
 * <h2>为什么需要它</h2>
 *
 * 工具是被 spring-ai 在它自己的执行点上同步调起来的({@code ToolCallingManager.executeToolCalls}),
 * 那里拿不到「当前这条 Flux」。而 tool-call / tool-result 两帧偏偏必须<b>按发生顺序</b>
 * 混进正文流里 —— 否则前端只能等一轮结束才知道刚才查了什么,「正在查覆盖率」这种即时反馈就没有了。
 *
 * <p>所以:{@code OpenAiAgentLlm} 开流时登记一个 sink,{@code AgentToolBridge} 执行工具时
 * 按 runId 找到它塞帧,收尾时注销。runId 是一个普通 String,可以安全地放进 spring-ai 的 toolContext ——
 * 而 sink 本身<b>不放进去</b>(那是个带订阅状态的对象,不该出现在会被框架传递、可能被序列化的地方)。
 *
 * <h2>🔴 注销必须在 finally 里</h2>
 *
 * 漏注销一次就泄漏一个 sink,而且泄漏的是<b>长时间不释放的 reactor 对象</b>。
 * 这个 map 没有过期清理 —— 加一层过期清理会让「泄漏」变成「慢性泄漏」,更难发现。
 * 宁可让它在漏的时候一直涨,涨到能被看见。
 */
@Component
public class RunChunkBus {

    private final ConcurrentMap<String, Sinks.Many<AgentChunk>> sinks = new ConcurrentHashMap<>();

    public void register(String runId, Sinks.Many<AgentChunk> sink) {
        sinks.put(runId, sink);
    }

    public void unregister(String runId) {
        sinks.remove(runId);
    }

    /**
     * 往指定 run 的流里塞一帧。
     *
     * <p>找不到 sink 时<b>安静地丢弃</b>:那意味着流已经结束(用户关了页面、上游超时),
     * 这时候再抛异常只会把一个正常的收尾变成一条错误日志。
     * 工具的执行结果不依赖这一帧 —— 它照样会进 ToolCall 落盘。
     */
    public void emit(String runId, AgentChunk chunk) {
        Sinks.Many<AgentChunk> sink = sinks.get(runId);
        if (sink != null) {
            sink.tryEmitNext(chunk);
        }
    }

    /** 当前登记着多少条流。只给排查用 —— 它要是一直涨,就是上面那句「注销必须在 finally 里」被违反了。 */
    public int activeCount() {
        return sinks.size();
    }
}
