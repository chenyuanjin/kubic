package com.kaodian.server.agent.session;

import com.kaodian.server.agent.storage.RunRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import java.util.stream.Stream;

/**
 * 文件版会话仓库 —— 一个会话一个 JSON。
 *
 * <pre>
 * &lt;root&gt;/sessions/{sessionId}.json
 * </pre>
 *
 * <p>与 {@code FileRunRepository} 同一条纪律:<b>逐字段列举,不用自动序列化</b>;
 * 写走「临时文件 → 原子 rename」;读不出来就吵着失败,绝不当成空。
 * 理由都在那个类的注释里,这里不重复。
 *
 * <p><b>没有单独的索引文件。</b>会话数量在这个产品里是「一个人几十条」的量级,
 * 列表直接扫目录。加索引意味着多一个会与真实数据分叉的东西 ——
 * 而它要解决的性能问题在这个量级上不存在(与 {@code FileTouchStore} 的全量重写同理)。
 */
@Repository
public class FileSessionRepository implements SessionRepository {

    private static final String TMP_SUFFIX = ".tmp";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path sessionsDir;
    private final Path runsRoot;
    private final RunRepository runRepo;

    public FileSessionRepository(@Value("${kaodian.agent.storage.root}") String rootDir,
                                 RunRepository runRepo) {
        Path root = Path.of(rootDir.replaceFirst("^~", System.getProperty("user.home")));
        this.sessionsDir = root.resolve("sessions");
        this.runsRoot = root.resolve("runs");
        this.runRepo = runRepo;
    }

    @Override
    public void save(AgentSession session) {
        ObjectNode n = mapper.createObjectNode();
        n.put("sessionId", session.sessionId());
        n.put("userId", session.userId());
        n.put("title", session.title());
        n.put("runCount", session.runCount());
        n.put("createdAt", session.createdAt().toString());
        n.put("updatedAt", session.updatedAt().toString());
        writeAtomically(fileOf(session.sessionId()),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(n));
    }

    @Override
    public Optional<AgentSession> find(String sessionId) {
        Path file = fileOf(sessionId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonNode n = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return Optional.of(new AgentSession(
                    n.path("sessionId").asString(null),
                    n.path("userId").asLong(0L),
                    n.path("title").asString(null),
                    n.path("runCount").asInt(0),
                    Instant.parse(n.path("createdAt").asString(Instant.EPOCH.toString())),
                    Instant.parse(n.path("updatedAt").asString(Instant.EPOCH.toString()))));
        } catch (IOException e) {
            throw new UncheckedIOException("读不到会话:" + file, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "会话文件内容不合法:" + file + " —— 宁可在这里失败,也不能当成空数据", e);
        }
    }

    @Override
    public List<AgentSession> findByUser(long userId) {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        List<AgentSession> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(sessionsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        String id = p.getFileName().toString().replaceFirst("\\.json$", "");
                        find(id).filter(s -> s.userId() == userId).ifPresent(out::add);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("列会话失败:" + sessionsDir, e);
        }
        out.sort(Comparator.comparing(AgentSession::updatedAt).reversed());
        return out;
    }

    @Override
    public boolean delete(String sessionId) {
        Path file = fileOf(sessionId);
        if (!Files.exists(file)) {
            return false;
        }
        // 先删 run 目录再删会话档:反过来的话,中途失败会留下一批【没有会话认领的 run】——
        // 那些 run 谁都列不出来,只能靠人去磁盘上翻,等于悄悄泄漏用户问过的内容。
        for (var run : runRepo.findRunsBySession(sessionId)) {
            deleteRecursively(runsRoot.resolve(run.runId()));
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("删会话失败:" + file, e);
        }
        return true;
    }

    private Path fileOf(String sessionId) {
        // sessionId 会从请求里来,必须挡路径穿越 —— 它要拼进文件名。
        if (sessionId == null || sessionId.isBlank()
                || sessionId.contains("/") || sessionId.contains("\\") || sessionId.contains("..")) {
            throw new IllegalArgumentException("非法 sessionId:" + sessionId);
        }
        return sessionsDir.resolve(sessionId + ".json");
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("删 run 目录失败:" + dir, e);
        }
    }

    private void writeAtomically(Path file, byte[] bytes) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            Files.write(tmp, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写会话失败:" + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不该盖掉真正的错误
            }
        }
    }
}
