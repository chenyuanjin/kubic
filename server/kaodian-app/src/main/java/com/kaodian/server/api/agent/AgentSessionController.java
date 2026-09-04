package com.kaodian.server.api.agent;

import com.kaodian.server.agent.entity.AgentMessage;
import com.kaodian.server.agent.entity.AgentRun;
import com.kaodian.server.agent.entity.MessagePart;
import com.kaodian.server.agent.session.AgentSession;
import com.kaodian.server.agent.session.SessionRepository;
import com.kaodian.server.agent.storage.RunRepository;
import com.kaodian.server.api.dto.common.Cursor;
import com.kaodian.server.api.dto.common.ErrorCode;
import com.kaodian.server.api.dto.common.Page;
import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.support.CurrentSession;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
@RequestMapping("/api/v1/agent/sessions")
public class AgentSessionController {

    private final SessionRepository sessions;
    private final RunRepository runs;

    public AgentSessionController(SessionRepository sessions, RunRepository runs) {
        this.sessions = sessions;
        this.runs = runs;
    }

    /**
     * 会话列表,最近用过的在前 —— 🔴 <b>本域唯一一个分页端点</b>({@code M3} §11.1),游标分页。
     *
     * <h2>为什么它分页而这一域别的都不分</h2>
     *
     * 覆盖度那几个端点的规模由<b>骨架</b>决定(一个模块几十到几百个考点,有尽头);
     * 会话列表的规模由<b>用户用了多久</b>决定 —— 它没有尽头。
     *
     * <h2>🔴 响应里没有 {@code total} / {@code pageCount} / {@code hasMore}</h2>
     *
     * 一个 {@code total} 会立刻长出页码条,而<b>页码条要求随机跳页,游标做不到</b>。
     * 形状由 {@code Page} 一处定死(B0 §7.1),这里不自己拼一个。
     *
     * @param cursor 上一页最后看到的那一条;不传 = 第一页。解不开 → {@code 400 INVALID_CURSOR}
     * @param limit  {@code 1..100},不传 = 20。越界 → {@code 400 INVALID_LIMIT}
     */
    @GetMapping
    public Page<SessionSummary> list(CurrentSession current,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        int size = Cursor.limit(limit);
        Cursor.Position from = Cursor.decode(cursor);

        // 🔴 findByUser 已经按 updatedAt 倒序,而排序键必须两级:同一毫秒里真的会有多条
        //    (一次导入、一次批量清理),只按时间戳翻页会把它们一起跳过或一起重复吐出来。
        List<AgentSession> page = new ArrayList<>();
        for (AgentSession s : sessions.findByUser(current.userId())) {
            if (from != null && !isStrictlyAfter(from, s)) {
                continue;
            }
            page.add(s);
            if (page.size() > size) {
                break;                       // 多取一条只为判断「还有没有下一页」
            }
        }

        String next = null;
        if (page.size() > size) {
            AgentSession last = page.get(size - 1);
            next = Cursor.encode(last.updatedAt().toEpochMilli(), last.sessionId());
            page = page.subList(0, size);
        }
        return new Page<>(page.stream()
                .map(s -> new SessionSummary(
                        s.sessionId(), s.title(), s.runCount(), s.createdAt(), s.updatedAt()))
                .toList(), next);
    }

    /**
     * 这条会话排在游标之后(更旧)吗。
     *
     * <p>严格小于,不是小于等于 —— 等于的那一条就是上一页的最后一条,再吐一次就是重复。
     * 与 {@code RecordCursor.Position#isStrictlyAfter} 同一条语义,只是排序键不同。
     */
    private static boolean isStrictlyAfter(Cursor.Position from, AgentSession s) {
        long at = s.updatedAt().toEpochMilli();
        if (at != from.sortKey()) {
            return at < from.sortKey();                  // 倒序:时间越小越靠后
        }
        return s.sessionId().compareTo(from.id()) < 0;   // 同一毫秒内按 id 降序,与排序键一致
    }

    /**
     * 一个会话的完整对话,按发生顺序。
     *
     * <p>不分页:一次会话几十条消息,分页带来的复杂度换不来任何东西
     * (与 {@code CoverageReader} 「18 个考点不需要分页」是同一判断)。
     */
    @GetMapping("/{sessionId}")
    public SessionDetail detail(CurrentSession current, @PathVariable String sessionId) {
        AgentSession session = mine(current, sessionId);

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
    public SessionSummary rename(CurrentSession current, @PathVariable String sessionId,
                                 @RequestBody RenameRequest body) {
        current.requireWrite();
        AgentSession session = mine(current, sessionId);
        String title = body.title() == null ? "" : body.title().strip();
        if (title.isEmpty()) {
            throw new ApiException(ErrorCode.TITLE_REQUIRED, "标题不能为空");
        }
        if (title.length() > AgentSession.MAX_TITLE_LENGTH) {
            // 上限只写在 AgentSession 一处,这里引用它而不是再写一个数字。
            throw new ApiException(ErrorCode.TITLE_TOO_LONG,
                    "标题最长 " + AgentSession.MAX_TITLE_LENGTH + " 字");
        }
        AgentSession renamed = session.withTitle(title);
        sessions.save(renamed);
        return new SessionSummary(renamed.sessionId(), renamed.title(),
                renamed.runCount(), renamed.createdAt(), renamed.updatedAt());
    }

    /** 删除会话及其全部 run —— 是真删,见 {@link SessionRepository#delete}。 */
    @DeleteMapping("/{sessionId}")
    public DeletedResponse delete(CurrentSession current, @PathVariable String sessionId) {
        current.requireWrite();
        mine(current, sessionId);            // 先确认它是我的,再删 —— 顺序反了就是替别人删
        if (!sessions.delete(sessionId)) {
            throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "会话不存在");
        }
        return new DeletedResponse(sessionId);
    }

    /**
     * 🔴 归属校验 —— <b>这一域每一个按 {@code sessionId} 取数的地方都要先过它</b>。
     *
     * <h2>不属于我的会话返 {@code 403},<b>不是</b> {@code 404}</h2>
     *
     * {@code 接口契约} §12.2.1。返 {@code 404} 等于把「这个 id 存不存在」告诉了不该知道的人 ——
     * 而 {@code sessionId} 是端上生成的 uuid,一个能区分「不存在」与「存在但不是你的」的接口
     * 就是一台会话枚举器。
     *
     * <h2>为什么校验落在 {@code app},不落在 {@code agent}</h2>
     *
     * {@code kaodian-agent} <b>不依赖 {@code kaodian-auth}</b>,它拿不到账号体系 ——
     * 这条边永不建({@code M3} §12)。<b>会话归属是账号问题,不是对话问题。</b>
     *
     * <p>⚠️ 上一版这三个端点(详情 / 改名 / 删除)<b>一次归属校验都没有</b>,
     * {@code userId} 恒为 {@code 0L},任何人可读、可改名、可删任意 {@code sessionId}。
     * 那是已经在主干上的越权(§十五 落差 3)。
     */
    private AgentSession mine(CurrentSession current, String sessionId) {
        AgentSession session = sessions.find(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND, "会话不存在"));
        if (session.userId() != current.userId()) {
            throw new ApiException(ErrorCode.NOT_YOUR_SESSION, "这条会话不属于你。");
        }
        return session;
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
