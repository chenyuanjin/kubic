package com.kaodian.server.agent.llm;

import com.kaodian.server.agent.channel.AgentChunk;
import reactor.core.publisher.Flux;

/**
 * 🔴 <b>模型出口。整个 agent 模块唯一被允许接触大模型的地方。</b>
 *
 * <h2>这条接口就是 docs/13 §4.1 那条绿线在 agent 侧的形态</h2>
 *
 * §4.1 的原文规定 {@code ChatModel} / {@code ChatClient} 不得越过 {@code recognize} 的实现类,
 * 理由第三条是:「任何人都能在别处 {@code @Autowired ChatModel} 直接问模型,
 * <b>能力边界就没有物理形态了</b>」。
 * <p>
 * 接一个通用对话 agent 与那条规定直接冲突 —— agent 本来就是要问模型的。
 * 所以这里不是绕开它,是<b>把同一条纪律再执行一遍</b>:
 * <ul>
 *   <li>模型接入封在这个接口后面,实现类只有两个({@link OpenAiAgentLlm} / {@link StubAgentLlm});</li>
 *   <li>spring-ai 的类型<b>只出现在 {@code agent.llm} 这一个包内</b>,
 *       由 {@code AgentLlmBoundaryTest} 扫源码钉住;</li>
 *   <li>模型能做的事情由工具池封顶,而工具池是一张可以当场读完的白名单
 *       (见 {@code AtomicToolRegistry})。</li>
 * </ul>
 * 于是「除 recognize 外无 ChatModel 注入点」变成「注入点仍然可数,而且每一处都有名字」。
 *
 * <h2>为什么返回 {@code Flux<AgentChunk>} 而不是「一次调用的结果」</h2>
 *
 * 因为工具循环在实现内部。一轮对话可能是「模型说几个字 → 调两个工具 → 接着说」,
 * 这些都在同一条流上按发生顺序出来。让接口只做「单次调用」的话,
 * 循环就得挪到编排层,而编排层就得知道 tool_calls 长什么样 —— spring-ai 的类型立刻泄漏出去。
 */
public interface AgentLlm {

    /**
     * 跑一轮对话,把过程流式产出。
     *
     * <p>流里<b>不含</b> {@code RunMeta} 与 {@code done} —— 那两样属于 run 的生命周期,
     * 由编排层负责。这里只产出模型与工具这一段。
     *
     * <p>失败时发一帧 {@link AgentChunk.Error} 然后<b>正常结束</b>,不要让 Flux 以 error 终止:
     * 那会让 SSE 直接断开,前端只剩一句「network error」。
     */
    Flux<AgentChunk> converse(AgentTurn turn);

    /** 本实现实际使用的模型标识,落进 {@code AgentRun.modelId}。 */
    String modelId();
}
