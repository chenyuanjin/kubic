package com.kaodian.server.api.agent;

import com.kaodian.server.agent.entity.AgentMessage;
import com.kaodian.server.agent.entity.AgentRun;
import com.kaodian.server.agent.entity.MessagePart;
import com.kaodian.server.agent.session.AgentSession;
import com.kaodian.server.agent.session.SessionRepository;
import com.kaodian.server.agent.storage.RunRepository;
import com.kaodian.server.api.support.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话历史管理 —— 列表 / 详情 / 改名 / 删除。
 *
 * <h2>为什么与 {@link AgentController} 分开</h2>
 *
 * 那个是<b>流式</b>端点(SSE、长连接、一个请求线程挂几十秒),这几个是普通的 CRUD。
 * 混在一个控制器里,读代码的人得先分辨哪个方法会挂住线程 —— 而那正是排查线上「线程池满了」
 * 时最想一眼看清的事。
 *
 * <h2>🔴 详情接口返回的是<b>文字</b>,不含图片</h2>
 *
 * 图片字节从来没有落过盘(R-04,见 {@code Orchestrator} 里那段注释),所以历史里也没有图可还。
 * 回放时看到的是「本轮附了 2 张图片；图片不留存，回放时不可见」这句占位。
 * <b>这是设计,不是缺陷</b> —— 要让它「更好用」的唯一办法是把图存下来,而那条线第一天就定死了。
 */
@RestController
@RequestMapping("/api/agent/sessions")
public class AgentSessionController {

    /** 与 {@code AgentController} 同一个占位 userId —— agent 这一层还没接鉴权。 */
    private static final long CURRENT_USER = 0L;

    private final SessionRepository sessions;
    private final RunRepository runs;

    public AgentSessionController(SessionRepository sessions, RunRepository runs) {
        this.sessions = sessions;
        this.runs = runs;
    }

    /** 会话列表,最近用过的在前。 */
    @GetMapping
    public List<SessionSummary> list() {
        return sessions.findByUser(CURRENT_USER).stream()
                .map(s -> new SessionSummary(
                        s.sessionId(), s.title(), s.runCount(), s.createdAt(), s.updatedAt()))
                .toList();
    }

    /**
     * 一个会话的完整对话,按发生顺序。
     *
     * <p>不分页:一次会话几十条消息,分页带来的复杂度换不来任何东西
     * (与 {@code CoverageReader} 「18 个考点不需要分页」是同一判断)。
     */
    @GetMapping("/{sessionId}")
    public SessionDetail detail(@PathVariable String sessionId) {
        AgentSession session = sessions.find(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在"));

        List<Turn> turns = new ArrayList<>();
        for (AgentRun run : runs.findRunsBySession(sessionId)) {
            for (AgentMessage m : runs.listMessages(run.runId())) {
                String text = m.parts().stream()
                        .filter(p -> p instanceof MessagePart.TextPart)
                        .map(p -> ((MessagePart.TextPart) p).text())
                        .reduce("", String::concat);
                if (!text.isBlank()) {
                    turns.add(new Turn(m.role().name().toLowerCase(), text, m.createdAt()));
                }
            }
        }
        return new SessionDetail(session.sessionId(), session.title(),
                session.createdAt(), session.updatedAt(), turns);
    }

    /** 改名。 */
    @PatchMapping("/{sessionId}")
    public SessionSummary rename(@PathVariable String sessionId, @RequestBody RenameRequest body) {
        AgentSession session = sessions.find(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在"));
        String title = body.title() == null ? "" : body.title().strip();
        if (title.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TITLE_REQUIRED", "标题不能为空");
        }
        if (title.length() > AgentSession.MAX_TITLE_LENGTH) {
            // 上限只写在 AgentSession 一处,这里引用它而不是再写一个数字。
            throw new ApiException(HttpStatus.BAD_REQUEST, "TITLE_TOO_LONG",
                    "标题最长 " + AgentSession.MAX_TITLE_LENGTH + " 字");
        }
        AgentSession renamed = session.withTitle(title);
        sessions.save(renamed);
        return new SessionSummary(renamed.sessionId(), renamed.title(),
                renamed.runCount(), renamed.createdAt(), renamed.updatedAt());
    }

    /** 删除会话及其全部 run —— 是真删,见 {@link SessionRepository#delete}。 */
    @DeleteMapping("/{sessionId}")
    public DeletedResponse delete(@PathVariable String sessionId) {
        if (!sessions.delete(sessionId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在");
        }
        return new DeletedResponse(sessionId);
    }

    public record SessionSummary(String sessionId, String title, int runCount,
                                 Instant createdAt, Instant updatedAt) {
    }

    /** @param role user / assistant */
    public record Turn(String role, String text, Instant at) {
    }

    public record SessionDetail(String sessionId, String title,
                                Instant createdAt, Instant updatedAt, List<Turn> turns) {
    }

    public record RenameRequest(String title) {
    }

    public record DeletedResponse(String sessionId) {
    }
}
