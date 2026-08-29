package com.kaodian.server.agent.orchestrator;

import com.kaodian.server.agent.channel.AgentChunk;
import com.kaodian.server.agent.channel.AgentRequest;
import com.kaodian.server.agent.channel.AgentResult;
import com.kaodian.server.agent.entity.AgentMessage;
import com.kaodian.server.agent.entity.AgentRun;
import com.kaodian.server.agent.entity.MessagePart;
import com.kaodian.server.agent.entity.RunState;
import com.kaodian.server.agent.entity.TraceEvent;
import com.kaodian.server.agent.llm.AgentLlm;
import com.kaodian.server.agent.llm.AgentTurn;
import com.kaodian.server.agent.prompt.AgentPrompt;
import com.kaodian.server.agent.session.AgentSession;
import com.kaodian.server.agent.session.SessionRepository;
import com.kaodian.server.agent.storage.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 七阶段编排 —— agent 的主控。
 *
 * <h2>它有多小,以及为什么</h2>
 *
 * truman-ai 的同名类是 3584 行。这里是三百来行,少掉的那些是:意图识别与 policy 路由、
 * 模型降级与故障转移、上下文压缩、澄清提问、敏感词门、灰度、运营指标上报、dossier……
 * <p>
 * 那些东西每一件都解决了一个它那边<b>真实发生过</b>的问题。而这个 agent 到今天为止
 * <b>一个真实用户都没有服务过</b> —— 提前把它们搬进来,搬进来的是形状不是判断力。
 * 03 §盲区二 记着这个项目自己的失败模式:注意力流向能做的部分,不是最不确定的部分。
 * 一个能编排七个阶段却没人用的 agent,正是那种「能做的部分」。
 * <p>
 * 所以这里只保留骨架:七个阶段的位置都在,每个阶段该落的轨迹都落,
 * 但每个阶段里面只做今天真正需要做的事。要加东西时,位置是现成的。
 */
@Component
public class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private static final String AGENT_NAME = "kaodian";

    /**
     * 回放最近几轮历史。
     *
     * <p>3 不是调出来的,是<b>压根还没有数据可调</b>。写死一个小数字而不是做成配置项:
     * 配置项会让人以为这个数被想过。等真有人抱怨「它记不住上文」的那天,
     * 那时候才有了调它的依据 —— 而那时候多半该上的是上下文压缩,不是把 3 改成 10。
     */
    private static final int HISTORY_RUNS = 3;

    private final AgentLlm llm;
    private final AgentPrompt prompt;
    private final RunRepository runRepo;
    private final SessionRepository sessionRepo;
    private final Clock clock;

    public Orchestrator(AgentLlm llm, AgentPrompt prompt, RunRepository runRepo,
                        SessionRepository sessionRepo, Clock clock) {
        this.llm = llm;
        this.prompt = prompt;
        this.runRepo = runRepo;
        this.sessionRepo = sessionRepo;
        this.clock = clock;
    }

    /**
     * 跑一轮。
     *
     * <p>返回的流里第一帧一定是 {@link AgentChunk.RunMeta},最后由调用方补一个 {@code done}
     * (落幕信号要带最终状态,而最终状态只有流结束时才知道 —— 见 {@link #finish})。
     */
    public Stream run(AgentRequest request) {
        Instant startedAt = clock.instant();

        // ——————— ① RECEIVE / ② ADMIT ———————
        // 入站与准入。今天的准入判据全在 AgentRequest 的紧凑构造器里(非空、长度上限),
        // 所以这里没有代码 —— 但阶段是真实存在的:第一个要加的门(频控、敏感词)落在这儿。

        // ——————— ③ BOOT ———————
        String runId = "r-" + UUID.randomUUID();
        AgentRun run = AgentRun.starting(runId, request.userId(), request.sessionId(),
                AGENT_NAME, llm.modelId(), startedAt);
        runRepo.saveRun(run);

        List<AgentMessage> history = loadHistory(request.sessionId());
        upsertSession(request, startedAt);
        // 🔴 R-04:落盘的是【文字 + 带了几张图这个事实】,不是图本身。
        //
        // 图片字节到这里为止只在内存里,交给模型之后就随请求结束一起没了 ——
        // 不写 messages.ndjson、不写任何缓存、不打日志。MessagePart 里根本没有能装图的类型
        // (见它的类注释:「没有、也不会有装内容的 part」),所以这条不是靠自觉,
        // 是靠这一层没有可用的容器。
        //
        // 代价是诚实的:下一轮回放历史时,模型看到的是「用户当时发过 2 张图」这句话,
        // 而不是那两张图。它无法再看一眼。这个断裂记在 docs/08 R-89,
        // 不要为了「体验连贯」把图存下来 —— 那正是 R-04 第一天定死、后面改不回来的那条。
        runRepo.appendMessage(new AgentMessage(
                "m-" + UUID.randomUUID(), runId, AgentMessage.Role.USER, 0,
                List.of(new MessagePart.TextPart(userTextFor(request))), startedAt));

        trace(runId, OrchestratorPhase.BOOT, "boot.done", TraceEvent.STATUS_SUCCESS,
                Duration.between(startedAt, clock.instant()).toMillis(),
                "历史 " + history.size() + " 条,模型 " + llm.modelId());

        AgentTurn turn = new AgentTurn(runId, AGENT_NAME, prompt.system(), history,
                request.message(), request.images());

        // ——————— ④ PLAN / ⑤ ACT ———————
        // 两个阶段在同一条流上交织发生(模型说几句 → 调工具 → 接着说),
        // 由 AgentLlm 内部驱动,执行点是 AgentToolBridge。
        trace(runId, OrchestratorPhase.PLAN, "plan.start", TraceEvent.STATUS_INFO, 0,
                "user=" + request.message().length() + " 字");

        StringBuilder answer = new StringBuilder();
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        Flux<AgentChunk> body = llm.converse(turn)
                .doOnNext(chunk -> {
                    switch (chunk) {
                        case AgentChunk.Token t -> answer.append(t.delta());
                        case AgentChunk.ToolCall ignored -> toolCalls.incrementAndGet();
                        case AgentChunk.Error e -> {
                            errors.incrementAndGet();
                            log.warn("[agent] runId={} 上游报错 code={} msg={}", runId, e.code(), e.message());
                        }
                        default -> {
                        }
                    }
                });

        Flux<AgentChunk> stream = Flux.<AgentChunk>just(
                        new AgentChunk.RunMeta(AGENT_NAME, runId, llm.modelId()))
                .concatWith(body);

        return new Stream(runId, stream, () -> finish(run, startedAt, answer, toolCalls.get(), errors.get()));
    }

    /**
     * ⑥ CONCLUDE —— 收尾。
     *
     * <p>由调用方在流<b>结束之后</b>调用(成功或失败都要调),因为最终状态取决于流里发生了什么。
     * 做成一个 {@link Stream#onComplete} 回调而不是让编排层自己订阅一次:
     * 订阅两次会让上游被调用两遍(冷流),那意味着一次提问付两次模型费用。
     */
    private AgentResult finish(AgentRun run, Instant startedAt,
                               StringBuilder answer, int toolCalls, int errors) {
        Instant now = clock.instant();
        long latencyMs = Duration.between(startedAt, now).toMillis();

        RunState state = errors > 0 ? RunState.FAILED : RunState.SUCCEEDED;
        AgentResult.FinishReason reason = errors > 0
                ? AgentResult.FinishReason.ERROR
                : AgentResult.FinishReason.SUCCESS;

        if (!answer.isEmpty()) {
            runRepo.appendMessage(new AgentMessage(
                    "m-" + UUID.randomUUID(), run.runId(), AgentMessage.Role.ASSISTANT, 1,
                    List.of(new MessagePart.TextPart(answer.toString())), now));
        }
        runRepo.saveRun(run.completed(state, now));

        trace(run.runId(), OrchestratorPhase.CONCLUDE, "conclude.done",
                errors > 0 ? TraceEvent.STATUS_FAILED : TraceEvent.STATUS_SUCCESS, latencyMs,
                "正文 " + answer.length() + " 字,工具 " + toolCalls + " 次,错误 " + errors + " 次");

        // ⑦ EMIT 由通道层(app 的 AgentSseController)完成 —— 它才知道怎么写进一个 HTTP 响应。
        // usage 恒为 EMPTY:上游 OpenAI 兼容端点在流式模式下的 usage 透出还没接,
        // 而报一个自己数出来的假 token 数,比不报更糟。
        return new AgentResult(run.runId(), reason, AgentResult.Usage.EMPTY, latencyMs);
    }

    /**
     * 会话建档 / 续期。
     *
     * <p>标题只在<b>建档那一次</b>定下来(取首条消息前 40 字),之后不再自动改 ——
     * 每轮都重算标题会让列表在用户眼皮底下不停变样,而列表项是他用来找回某次对话的锚点。
     * 改名是显式操作,走 {@code PATCH}。
     */
    private void upsertSession(AgentRequest request, Instant now) {
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;     // 单轮提问不建会话
        }
        AgentSession existing = sessionRepo.find(sessionId).orElse(null);
        if (existing == null) {
            // 🔴 标题取【用户原话】request.message(),不是 userTextFor()。
            // 后者会缀上「本轮附了 N 张图片；图片不留存」那句系统占位 —— 那是给模型看的上下文,
            // 不是给人看的标题。带图提问时会话列表里会出现
            // 「这张图是什么考点?我碰过吗 （本轮附了 1 张图片；…）」这种一半是系统话的条目,
            // 而且那句占位还会挤掉 40 字上限里本就不多的额度。
            // 纯图片轮次(message 为空)由 titleFrom 兜成「图片提问」。
            sessionRepo.save(new AgentSession(sessionId, request.userId(),
                    AgentSession.titleFrom(request.message()), 1, now, now));
        } else {
            sessionRepo.save(existing.touched(now));
        }
    }

    /**
     * 落盘用的用户文本 —— 带图时把「有几张图」写进去。
     *
     * <p>为什么要写这一句:不写的话,历史回放时上一轮会变成一句没头没脑的
     * 「这个考点我碰过吗」,而模型完全不知道当时还有一张图。写上之后它至少知道
     * 「那一轮有图但我现在看不到」,可以据此追问,而不是凭空猜。
     */
    private static String userTextFor(AgentRequest request) {
        int count = request.images().size();
        if (count == 0) {
            return request.message();
        }
        String suffix = "（本轮附了 " + count + " 张图片；图片不留存，回放时不可见）";
        return request.message().isBlank() ? suffix : request.message() + " " + suffix;
    }

    /**
     * 读最近几轮的对话作为上下文。
     *
     * <p>sessionId 为空时返回空 —— 单轮提问不读历史,也就不会把别人的会话串进来。
     */
    private List<AgentMessage> loadHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<AgentRun> runs = runRepo.findRunsBySession(sessionId);
        List<AgentRun> recent = runs.size() <= HISTORY_RUNS
                ? runs
                : runs.subList(runs.size() - HISTORY_RUNS, runs.size());

        List<AgentMessage> history = new ArrayList<>();
        for (AgentRun r : recent) {
            history.addAll(runRepo.listMessages(r.runId()));
        }
        return history;
    }

    private void trace(String runId, OrchestratorPhase phase, String name,
                       String status, long durationMs, String detail) {
        runRepo.appendEvent(new TraceEvent(
                runId, phase.prefix(), name, status, durationMs, detail, clock.instant()));
    }

    /**
     * 一次编排的产物:帧流 + 一个收尾回调。
     *
     * <p>为什么把收尾做成回调交出去,而不是在编排层内部 {@code doFinally}:
     * {@code done} 帧必须<b>在所有内容帧之后</b>发出,而它的内容(最终状态)只有收尾时才算得出来。
     * 交给通道层调,通道层就能在同一个地方决定「先发完内容,再发 done,再关闭连接」的顺序 ——
     * 那个顺序是 SSE 契约的一部分,不该由编排层隔空决定。
     */
    public record Stream(String runId, Flux<AgentChunk> chunks, java.util.function.Supplier<AgentResult> onComplete) {
    }
}
