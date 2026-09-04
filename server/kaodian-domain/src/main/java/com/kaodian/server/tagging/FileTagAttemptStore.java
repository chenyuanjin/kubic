package com.kaodian.server.tagging;

import com.kaodian.server.collect.OrphanGuard;
import com.kaodian.server.collect.Tenant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link TagAttemptStore} 的阶段 0/1 实现 —— <b>一个 JSON 文件,没有数据库</b>({@code B0-1})。
 *
 * <p>形态、纪律、失败方式全部照抄 {@code FileRecordTagStore}:全量重写 + 原子 rename、
 * 读写逐字段列举、坏文件宁可启动不了也不当成 0 行。理由不重复,这里只写<b>不一样的那两处</b>。
 *
 * <h2>不一样之一:{@link #put} 带着队列上限</h2>
 *
 * 排着队的行超过 {@link TagAttempt#QUEUE_CAPACITY} 时按 {@code updatedAt} 丢最旧
 * ({@code T-36})。🔴 <b>丢的是「稍后再帮你认一次」,不是用户记的那一笔</b> ——
 * 这个类碰不到行为层,结构上就丢不掉一条记录({@code I-1})。
 *
 * <h2>不一样之二:整张表里没有一个字段能装下内容</h2>
 *
 * 六个字段全部是标识、枚举名、计数与时刻。<b>没有模型响应留档、没有置信度历史、
 * 没有失败原因的文字</b>({@code M2} §10.3)—— 留档就是把模型输出的自由文本落了盘,
 * 而 {@code R-07} 的类型层保护当场被绕过。
 */
@Component
public class FileTagAttemptStore implements TagAttemptStore {

    private static final String FILE_NAME = "tag-attempts.json";
    private static final String TMP_SUFFIX = ".tmp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    /** 单进程,一把锁足够 —— 与 {@code FileRecordTagStore} 同一句。 */
    private final Object lock = new Object();

    /** {@code null} 表示还没载入。载入推迟到第一次访问。 */
    private List<TagAttempt> attempts;

    /**
     * @param dataDir 数据目录,默认 {@code ~/.kaodian}。与标签层同一个目录 ——
     *                「把这个目录拷走就是全部数据」这句话不能因为多了一张表就不成立
     */
    @Autowired
    public FileTagAttemptStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public FileTagAttemptStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    /** 数据文件的位置。 */
    public Path dataFile() {
        return file;
    }

    @Override
    public TagAttempt find(long userId, String recordId) {
        Tenant.requireUserId(userId);
        if (recordId == null || recordId.isBlank()) {
            return null;
        }
        synchronized (lock) {
            ensureLoaded();
            for (TagAttempt a : attempts) {
                if (a.userId() == userId && a.recordId().equals(recordId)) {
                    return a;
                }
            }
            return null;
        }
    }

    /** 契约见 {@link TagAttemptStore#put} —— <b>「覆盖 + 裁队 + 写」在同一把锁里</b>。 */
    @Override
    public TagAttempt put(TagAttempt attempt) {
        synchronized (lock) {
            ensureLoaded();

            List<TagAttempt> next = new ArrayList<>(attempts.size() + 1);
            boolean replaced = false;
            for (TagAttempt a : attempts) {
                if (a.userId() == attempt.userId() && a.recordId().equals(attempt.recordId())) {
                    next.add(attempt);
                    replaced = true;
                } else {
                    next.add(a);
                }
            }
            if (!replaced) {
                next.add(attempt);
            }

            trimQueue(next, attempt.userId());

            // 先落盘再改内存:写失败时内存与磁盘仍然一致。
            writeAtomically(next);
            attempts = next;
            return attempt;
        }
    }

    @Override
    public List<TagAttempt> dueForRetry(Instant now, int limit) {
        if (now == null) {
            throw new IllegalArgumentException("要问「到点了没有」,得先说此刻是几点");
        }
        if (limit <= 0) {
            return List.of();
        }
        synchronized (lock) {
            ensureLoaded();
            return attempts.stream()
                    .filter(a -> a.dueAt(now))
                    .sorted(Comparator.comparing(TagAttempt::nextRetryAt))
                    .limit(limit)
                    .toList();
        }
    }

    @Override
    public int pendingCount(long userId) {
        Tenant.requireUserId(userId);
        synchronized (lock) {
            ensureLoaded();
            int n = 0;
            for (TagAttempt a : attempts) {
                if (a.userId() == userId && a.queued()) {
                    n++;
                }
            }
            return n;
        }
    }

    @Override
    public int deleteByRecord(long userId, String recordId) {
        Tenant.requireUserId(userId);
        if (recordId == null || recordId.isBlank()) {
            return 0;
        }
        synchronized (lock) {
            ensureLoaded();
            List<TagAttempt> next = new ArrayList<>(attempts.size());
            int removed = 0;
            for (TagAttempt a : attempts) {
                if (a.userId() == userId && a.recordId().equals(recordId)) {
                    removed++;
                } else {
                    next.add(a);
                }
            }
            if (removed == 0) {
                return 0;               // 什么都没变,不必写盘
            }
            writeAtomically(next);
            attempts = next;
            return removed;
        }
    }

    /**
     * 队列满了就丢最旧的那些 —— 就地改 {@code all}。
     *
     * <p>🔴 丢的方式是<b>把那一行整个删掉</b>,不是把 {@code nextRetryAt} 清空留个空壳。
     * 留空壳的话「这条为什么没对上」还在,而它已经不会再被重试了 ——
     * 用户看到的是一条永远停在「稍后再认」的记录,而没有任何东西会再去认它。
     * 整行删掉之后这条记录退回 {@code TS-00},手动重试那个出口本来就一直在。
     */
    private static void trimQueue(List<TagAttempt> all, long userId) {
        List<TagAttempt> queued = all.stream()
                .filter(a -> a.userId() == userId && a.queued())
                .sorted(Comparator.comparing(TagAttempt::updatedAt))
                .toList();
        int excess = queued.size() - TagAttempt.QUEUE_CAPACITY;
        for (int i = 0; i < excess; i++) {
            all.remove(queued.get(i));
        }
    }

    // —— 载入 ——

    /** 推迟到第一次访问才载入 —— 构造 bean 不该有副作用(与 {@code FileRecordTagStore} 同一句)。 */
    private void ensureLoaded() {
        if (attempts != null) {
            return;
        }
        attempts = Files.exists(file) ? read() : List.of();
    }

    private List<TagAttempt> read() {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(MAPPER.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("打标尝试数据文件读取失败:" + file, e);
        }
    }

    /**
     * 解析一份尝试 JSON。
     *
     * <p>认不出来就吵着失败,绝不当成 0 行 —— 与 {@code FileRecordTagStore#parse} 逐字同一条:
     * 下一次 {@link #put} 是全量重写,那 0 行会盖掉磁盘上真实排着的队。
     * 后果是一批 {@code TS-06} 的记录<b>永远不会被再认一次</b>,而界面上它们仍然写着「稍后再认」。
     */
    private static List<TagAttempt> parse(JsonNode root) {
        JsonNode array = root.path("attempts");
        if (!array.isArray()) {
            throw new IllegalStateException(
                    "打标尝试数据里没有 attempts 数组 —— 宁可在这里失败,也不能当成 0 行,"
                            + "否则下一次写入会把磁盘上真实排着的队整个盖掉");
        }

        List<TagAttempt> result = new ArrayList<>();
        for (JsonNode n : array) {
            // 🔴 没有归属的行【丢弃】,不认领 —— 与 FileRecordTagStore#parse 同一句,理由见 OrphanGuard。
            if (OrphanGuard.isOrphan(n)) {
                continue;
            }
            try {
                result.add(toAttempt(n));
            } catch (IllegalArgumentException | DateTimeException e) {
                throw new IllegalStateException(
                        "打标尝试数据里有一行不合法:" + n.path("recordId").asString("?"), e);
            }
        }
        return result;
    }

    /** 一个 JSON 对象 → 一行尝试。<b>只认这六个键,别的一概不看。</b> */
    private static TagAttempt toAttempt(JsonNode n) {
        String nextRetryAt = n.path("nextRetryAt").asString("");
        return new TagAttempt(
                required(n, "recordId"),
                n.path(OrphanGuard.USER_ID).asLong(0),
                TagAttempt.Outcome.valueOf(required(n, "outcome")),
                n.path("attempts").asInt(0),
                nextRetryAt.isEmpty() ? null : Instant.parse(nextRetryAt),
                Instant.parse(required(n, "updatedAt")));
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asString("");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("打标尝试行缺少必填字段:" + field);
        }
        return v;
    }

    // —— 写入 ——

    /**
     * 一行尝试 → 一个 JSON 对象。
     *
     * <p>🔴 逐字段写,<b>不是</b>把 {@link TagAttempt} 交给 Jackson 自动序列化 ——
     * 与 {@code FileRecordTagStore#toNode} 同一个理由:自动序列化会跟着 record 的形状走,
     * 哪天有人给它加了个 {@code lastVendorResponse},那段自由文本就会不声不响地流进用户的数据文件,
     * 而这张表的全部意义就是<b>它装不下内容</b>。
     */
    private static ObjectNode toNode(TagAttempt a) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("recordId", a.recordId());
        o.put(OrphanGuard.USER_ID, a.userId());     // B0-3 租户列
        o.put("outcome", a.outcome().name());       // 一个枚举名,不是一段说明
        o.put("attempts", a.attempts());
        if (a.nextRetryAt() != null) {
            o.put("nextRetryAt", a.nextRetryAt().toString());
        }
        o.put("updatedAt", a.updatedAt().toString());
        return o;
    }

    /** 先写临时文件 → fsync → 原子 rename。与 {@code FileRecordTagStore#writeAtomically} 同一份。 */
    private void writeAtomically(List<TagAttempt> all) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode comment = root.putArray("_comment");
            comment.add("每一行是「某条记录最近一次打标尝试走到了哪一步」,一条记录只有一行。");
            comment.add("🔴 六个字段全是标识/枚举名/计数/时刻 —— 装不下题干、转写文本、模型响应。");
            comment.add("待补队列不是另一张表:它就是 outcome=UNAVAILABLE 且 nextRetryAt 非空的那些行。");
            comment.add("只有 UNAVAILABLE 进队;拿不到许可、没对上、骨架未建好都一次都不试。");
            ArrayNode arr = root.putArray("attempts");
            for (TagAttempt a : all) {
                arr.add(toNode(a));
            }

            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            Files.write(tmp, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }

            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("打标尝试层写入失败:" + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不该盖掉真正的错误
            }
        }
    }
}
