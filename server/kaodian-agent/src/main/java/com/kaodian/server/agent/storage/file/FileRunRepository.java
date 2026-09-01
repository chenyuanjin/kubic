package com.kaodian.server.agent.storage.file;

import com.kaodian.server.agent.entity.AgentMessage;
import com.kaodian.server.agent.entity.AgentRun;
import com.kaodian.server.agent.entity.MessagePart;
import com.kaodian.server.agent.entity.RunState;
import com.kaodian.server.agent.entity.ToolCall;
import com.kaodian.server.agent.entity.TraceEvent;
import com.kaodian.server.agent.storage.RunRepository;
import com.kaodian.server.agent.tool.spi.ToolLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 文件版 Run 仓库 —— <b>一个目录一次 run,没有数据库。</b>
 *
 * <h2>为什么是文件</h2>
 *
 * docs/technical/INDEX.md §零:数据层落库最早到阶段 1 的 {@code 1.2.4},阶段 0 是本地文件夹。
 * agent 比那条线还要靠后 —— 它现在连一个真实用户都还没服务过。
 * 现在给它上一套库,买到的只是一个要运维的进程。理由与 {@code FileTouchStore} 逐字相同。
 *
 * <h2>目录布局</h2>
 * <pre>
 * &lt;root&gt;/runs/{runId}/
 *     ├── run.json           AgentRun 主体(状态变化时整体覆写)
 *     ├── messages.ndjson    每行一条 AgentMessage,只追加
 *     ├── toolCalls.ndjson   每行一条 ToolCall,只追加
 *     └── events.ndjson      每行一条 TraceEvent,只追加
 * &lt;root&gt;/index/session-runs.tsv   "{sessionId}\t{runId}\t{startedAtEpochMilli}"
 * </pre>
 *
 * <h2>🔴 逐字段列举,不用自动序列化</h2>
 *
 * 与 {@code FileTouchStore} 同一条纪律,理由在 agent 这一层<b>更重</b>:
 * 对话历史是整个仓库里最容易长出「内容」的地方。今天 {@link MessagePart} 只有三种,
 * 但只要有人加一个 {@code ImagePart},自动序列化就会让每一轮对话都在磁盘上留一份图 ——
 * 而 R-04 说的是原图只在内存里活到抽取完成。
 * <p>
 * 所以:写,由 {@code toNode} 显式列出能出现的键;读,由 {@code parse*} 只认那几个。
 * 文件里出现别的键一律进不了内存。
 *
 * <h2>为什么 run.json 是全量重写 + 原子 rename</h2>
 *
 * 直接截断重写的话,写到一半断电就是一个半截 JSON,那条 run 整个读不回来。
 * 三个 ndjson 则是纯追加 —— 追加写最坏是最后一行不完整,读的时候跳过就行,
 * <b>已经写下的历史不会因为后面一次失败而丢</b>。两种写法各用在合适的地方。
 */
@Repository
public class FileRunRepository implements RunRepository {

    private static final String RUN_JSON = "run.json";
    private static final String MESSAGES = "messages.ndjson";
    private static final String TOOL_CALLS = "toolCalls.ndjson";
    private static final String EVENTS = "events.ndjson";
    private static final String TMP_SUFFIX = ".tmp";

    private final ObjectMapper mapper = new ObjectMapper();

    private final Path root;
    private final Path runsDir;
    private final Path sessionIndex;

    /** 一个 runId 一把锁。同一条 run 的追加写互斥,不同 run 之间不互相等。 */
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public FileRunRepository(@Value("${kaodian.agent.storage.root}") String rootDir) {
        this.root = Path.of(rootDir.replaceFirst("^~", System.getProperty("user.home")));
        this.runsDir = root.resolve("runs");
        this.sessionIndex = root.resolve("index").resolve("session-runs.tsv");
    }

    /* ============================== 写 ============================== */

    @Override
    public void saveRun(AgentRun run) {
        Path dir = runDir(run.runId());
        synchronized (lock(run.runId())) {
            mkdirs(dir);
            ObjectNode node = mapper.createObjectNode();
            node.put("runId", run.runId());
            node.put("userId", run.userId());
            node.put("sessionId", run.sessionId());
            node.put("agentName", run.agentName());
            node.put("modelId", run.modelId());
            node.put("state", run.state().name());
            node.put("startedAt", run.startedAt().toString());
            node.put("completedAt", run.completedAt() == null ? null : run.completedAt().toString());
            writeAtomically(dir.resolve(RUN_JSON), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));

            if (run.sessionId() != null && !run.sessionId().isBlank()) {
                indexSession(run);
            }
        }
    }

    @Override
    public void appendMessage(AgentMessage message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("messageId", message.messageId());
        node.put("runId", message.runId());
        node.put("role", message.role().name());
        node.put("iteration", message.iteration());
        node.put("createdAt", message.createdAt().toString());
        ArrayNode parts = node.putArray("parts");
        for (MessagePart part : message.parts()) {
            parts.add(toNode(part));
        }
        appendLine(message.runId(), MESSAGES, node);
    }

    /** 一个 part 能落到磁盘上的字段,全部在这里。sealed 接口保证漏一种就编译不过。 */
    private ObjectNode toNode(MessagePart part) {
        ObjectNode n = mapper.createObjectNode();
        switch (part) {
            case MessagePart.TextPart t -> {
                n.put("kind", "text");
                n.put("text", t.text());
            }
            case MessagePart.ToolCallPart c -> {
                n.put("kind", "tool_call");
                n.put("id", c.id());
                n.put("name", c.name());
                n.put("arguments", c.arguments());
            }
            case MessagePart.ToolResultPart r -> {
                n.put("kind", "tool_result");
                n.put("id", r.id());
                n.put("name", r.name());
                n.put("result", r.result());
                n.put("error", r.error());
            }
        }
        return n;
    }

    @Override
    public void appendToolCall(ToolCall call) {
        ObjectNode n = mapper.createObjectNode();
        n.put("callId", call.callId());
        n.put("runId", call.runId());
        n.put("llmCallId", call.llmCallId());
        n.put("toolName", call.toolName());
        n.put("level", call.level() == null ? null : call.level().name());
        n.put("arguments", call.arguments());
        n.put("result", call.result());
        n.put("error", call.error());
        n.put("startedAt", call.startedAt() == null ? null : call.startedAt().toString());
        n.put("completedAt", call.completedAt() == null ? null : call.completedAt().toString());
        n.put("durationMs", call.durationMs());
        appendLine(call.runId(), TOOL_CALLS, n);
    }

    @Override
    public void appendEvent(TraceEvent event) {
        ObjectNode n = mapper.createObjectNode();
        n.put("runId", event.runId());
        n.put("phase", event.phase());
        n.put("name", event.name());
        n.put("status", event.status());
        n.put("durationMs", event.durationMs());
        n.put("detail", event.detail());
        n.put("timestamp", event.timestamp().toString());
        appendLine(event.runId(), EVENTS, n);
    }

    /* ============================== 读 ============================== */

    @Override
    public Optional<AgentRun> findRun(String runId) {
        Path file = runDir(runId).resolve(RUN_JSON);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonNode n = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return Optional.of(new AgentRun(
                    text(n, "runId"),
                    n.path("userId").asLong(0L),
                    text(n, "sessionId"),
                    text(n, "agentName"),
                    text(n, "modelId"),
                    RunState.valueOf(n.path("state").asString(RunState.PENDING.name())),
                    instant(n, "startedAt"),
                    instant(n, "completedAt")));
        } catch (IOException e) {
            throw new UncheckedIOException("读不到 run:" + file, e);
        } catch (RuntimeException e) {
            // 认不出来就吵着失败,绝不当成「这条 run 不存在」。
            // 与 AuthJsonFile#read 同一条:坏文件被当成空,下一次写入就把它盖掉了。
            throw new IllegalStateException(
                    "run 文件内容不合法:" + file + " —— 宁可在这里失败,也不能当成空数据", e);
        }
    }

    @Override
    public List<AgentRun> findRunsBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || !Files.exists(sessionIndex)) {
            return List.of();
        }
        List<AgentRun> runs = new ArrayList<>();
        for (String line : readLines(sessionIndex)) {
            String[] cols = line.split("\t", -1);
            if (cols.length >= 2 && sessionId.equals(cols[0])) {
                findRun(cols[1]).ifPresent(runs::add);
            }
        }
        runs.sort(Comparator.comparing(AgentRun::startedAt));
        return runs;
    }

    @Override
    public List<AgentMessage> listMessages(String runId) {
        List<AgentMessage> out = new ArrayList<>();
        for (JsonNode n : readNdjson(runId, MESSAGES)) {
            List<MessagePart> parts = new ArrayList<>();
            for (JsonNode p : n.path("parts")) {
                MessagePart part = parsePart(p);
                if (part != null) {
                    parts.add(part);
                }
            }
            out.add(new AgentMessage(
                    text(n, "messageId"), text(n, "runId"),
                    AgentMessage.Role.valueOf(n.path("role").asString(AgentMessage.Role.USER.name())),
                    n.path("iteration").asInt(0), parts, instant(n, "createdAt")));
        }
        return out;
    }

    /** 只认这三种 kind。文件里出现别的一律返回 null,进不了内存。 */
    private MessagePart parsePart(JsonNode p) {
        return switch (p.path("kind").asString("")) {
            case "text" -> new MessagePart.TextPart(text(p, "text"));
            case "tool_call" -> new MessagePart.ToolCallPart(
                    text(p, "id"), text(p, "name"), text(p, "arguments"));
            case "tool_result" -> new MessagePart.ToolResultPart(
                    text(p, "id"), text(p, "name"), text(p, "result"), p.path("error").asBoolean(false));
            default -> null;
        };
    }

    @Override
    public List<ToolCall> listToolCalls(String runId) {
        List<ToolCall> out = new ArrayList<>();
        for (JsonNode n : readNdjson(runId, TOOL_CALLS)) {
            String level = text(n, "level");
            out.add(new ToolCall(
                    text(n, "callId"), text(n, "runId"), text(n, "llmCallId"), text(n, "toolName"),
                    level == null ? null : ToolLevel.valueOf(level),
                    text(n, "arguments"), text(n, "result"), n.path("error").asBoolean(false),
                    instant(n, "startedAt"), instant(n, "completedAt")));
        }
        return out;
    }

    @Override
    public List<TraceEvent> listEvents(String runId) {
        List<TraceEvent> out = new ArrayList<>();
        for (JsonNode n : readNdjson(runId, EVENTS)) {
            out.add(new TraceEvent(
                    text(n, "runId"), text(n, "phase"), text(n, "name"),
                    text(n, "status"), n.path("durationMs").asLong(0L), text(n, "detail"),
                    instant(n, "timestamp")));
        }
        return out;
    }

    /* ============================== 底层 ============================== */

    private Path runDir(String runId) {
        // runId 由我们自己发号(r-<uuid>),不含分隔符;仍然挡一道 —— 它将来可能从请求里来。
        if (runId == null || runId.isBlank() || runId.contains("/") || runId.contains("..")) {
            throw new IllegalArgumentException("非法 runId:" + runId);
        }
        return runsDir.resolve(runId);
    }

    private Object lock(String runId) {
        return locks.computeIfAbsent(runId, k -> new Object());
    }

    private void appendLine(String runId, String fileName, ObjectNode node) {
        Path dir = runDir(runId);
        synchronized (lock(runId)) {
            mkdirs(dir);
            try {
                Files.writeString(dir.resolve(fileName),
                        mapper.writeValueAsString(node) + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("追加写失败:" + dir.resolve(fileName), e);
            }
        }
    }

    private List<JsonNode> readNdjson(String runId, String fileName) {
        Path file = runDir(runId).resolve(fileName);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        for (String line : readLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                out.add(mapper.readTree(line));
            } catch (RuntimeException e) {
                // 追加写的最后一行可能因为断电而不完整。跳过它,而不是让整条 run 读不回来 ——
                // 这正是 ndjson 相对单个大 JSON 的意义所在。
                break;
            }
        }
        return out;
    }

    private void indexSession(AgentRun run) {
        try {
            Files.createDirectories(sessionIndex.getParent());
            // 同一条 run 多次 saveRun(建档一次、收尾一次)会写两行相同 runId。
            // 读取侧按 runId 去 findRun,重复行只是多读一次同一个文件,不会产生两条 run。
            // 为这点冗余去做「先读全量再判重写」不划算:那会把追加写变成全量重写。
            if (alreadyIndexed(run.runId())) {
                return;
            }
            Files.writeString(sessionIndex,
                    run.sessionId() + "\t" + run.runId() + "\t" + run.startedAt().toEpochMilli() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("写会话索引失败:" + sessionIndex, e);
        }
    }

    private boolean alreadyIndexed(String runId) {
        if (!Files.exists(sessionIndex)) {
            return false;
        }
        for (String line : readLines(sessionIndex)) {
            String[] cols = line.split("\t", -1);
            if (cols.length >= 2 && cols[1].equals(runId)) {
                return true;
            }
        }
        return false;
    }

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败:" + file, e);
        }
    }

    /** 先写临时文件 → 原子 rename。中途断电最坏结果是这次写入没发生。 */
    private void writeAtomically(Path file, byte[] bytes) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            Files.write(tmp, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败:" + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不该盖掉真正的错误
            }
        }
    }

    private void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("建目录失败:" + dir, e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asString(null);
    }

    private static Instant instant(JsonNode n, String field) {
        String s = text(n, field);
        return s == null ? null : Instant.parse(s);
    }
}
