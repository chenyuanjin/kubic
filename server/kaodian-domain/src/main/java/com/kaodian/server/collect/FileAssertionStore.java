package com.kaodian.server.collect;

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
import java.util.List;

/**
 * {@link AssertionStore} 的阶段 0/1 实现 —— <b>一个 JSON 文件,没有数据库。</b>
 *
 * <p>形态、纪律、失败方式全部照抄 {@link FileRecordTagStore}:全量重写 + 原子 rename、
 * 读写逐字段列举、坏文件宁可启动不了也不当成 0 行、没有种子文件。理由不重复,见那两个类;
 * 这里只写<b>不一样的那一处</b>。
 *
 * <h2>不一样之处:主键是 {@code nodeCode},不是一个自己发的 id</h2>
 *
 * 一个考点上最多一条声明 —— 「我已掌握」没有「掌握了两次」这种说法。
 * 所以这张表不发 id:发了 id 就意味着同一个考点可以有两行,而那两行不管谁赢,
 * 概览里那个「已声明 N 个」都会开始说谎。<b>去重靠的是主键的形状,不是写入时的一次检查。</b>
 * <p>
 * 于是 {@link #put} 的幂等不是「查一下再决定」,而是「按 {@code nodeCode} 覆盖」的自然结果 ——
 * 唯一要额外守住的是<b>已经存在时不刷新 {@code assertedAt}</b>(契约见 {@link AssertionStore#put})。
 */
@Component
public class FileAssertionStore implements AssertionStore {

    private static final String FILE_NAME = "assertions.json";
    private static final String TMP_SUFFIX = ".tmp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    /** 单进程单用户,一把锁足够 —— 与 {@link FileTouchStore} 同一句。 */
    private final Object lock = new Object();

    /** {@code null} 表示还没载入。载入推迟到第一次访问。 */
    private List<UserAssertion> assertions;

    /**
     * @param dataDir 数据目录,默认 {@code ~/.kaodian}。与行为层同一个目录 ——
     *                「把这个目录拷走就是全部数据」这句话不能因为多了一张表就不成立
     */
    @Autowired
    public FileAssertionStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public FileAssertionStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    /** 数据文件的位置。 */
    public Path dataFile() {
        return file;
    }

    @Override
    public List<UserAssertion> findAll() {
        synchronized (lock) {
            ensureLoaded();
            return List.copyOf(assertions);
        }
    }

    @Override
    public UserAssertion find(String nodeCode) {
        synchronized (lock) {
            ensureLoaded();
            return lookup(nodeCode);
        }
    }

    /** 契约见 {@link AssertionStore#put} —— <b>「查 + 决定 + 写」在同一把锁里</b>。 */
    @Override
    public UserAssertion put(UserAssertion assertion) {
        synchronized (lock) {
            ensureLoaded();

            UserAssertion existing = lookup(assertion.nodeCode());
            if (existing != null) {
                // 幂等:已经声明过了,原样返回。不写盘,更不刷新 assertedAt ——
                // 连点两下按钮不该改写「你在 X 月 X 日说过你会了」这句话。
                return existing;
            }

            List<UserAssertion> next = new ArrayList<>(assertions.size() + 1);
            next.addAll(assertions);
            next.add(assertion);

            // 先落盘再改内存:写失败时内存与磁盘仍然一致(与 FileTouchStore 同一条纪律)
            writeAtomically(next);
            assertions = next;
            return assertion;
        }
    }

    /** 契约见 {@link AssertionStore#remove} —— 没有那一行就什么都不做,也不写盘。 */
    @Override
    public boolean remove(String nodeCode) {
        synchronized (lock) {
            ensureLoaded();
            if (nodeCode == null || nodeCode.isBlank()) {
                return false;
            }
            List<UserAssertion> next = new ArrayList<>(assertions.size());
            boolean removed = false;
            for (UserAssertion a : assertions) {
                if (nodeCode.equals(a.nodeCode())) {
                    removed = true;
                } else {
                    next.add(a);
                }
            }
            if (!removed) {
                return false;           // 什么都没变,不必写盘
            }
            writeAtomically(next);
            assertions = next;
            return true;
        }
    }

    @Override
    public int count() {
        synchronized (lock) {
            ensureLoaded();
            return assertions.size();
        }
    }

    /** 调用方必须已经持有 {@link #lock} 且已 {@link #ensureLoaded}。 */
    private UserAssertion lookup(String nodeCode) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return null;
        }
        for (UserAssertion a : assertions) {
            if (nodeCode.equals(a.nodeCode())) {
                return a;
            }
        }
        return null;
    }

    // —— 载入 ——

    /**
     * 推迟到第一次访问才载入 —— 与 {@link FileRecordTagStore#find} 那一侧同一个理由:
     * 构造 bean 是一件不该有副作用的事,起一次上下文就往 {@code ~/.kaodian} 写文件,
     * 会让每一次跑测试都污染真实用户目录。
     *
     * <p>文件不存在时<b>不写盘</b>,只当成空表:这张表没有种子(没有人「默认已掌握」),
     * 第一次 {@link #put} 时自然会把文件建出来。
     */
    private void ensureLoaded() {
        if (assertions != null) {
            return;
        }
        assertions = Files.exists(file) ? read() : List.of();
    }

    private List<UserAssertion> read() {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(MAPPER.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("「我已掌握」数据文件读取失败:" + file, e);
        }
    }

    /**
     * 解析一份声明 JSON。
     *
     * <h2>认不出来就吵着失败,绝不当成 0 行</h2>
     *
     * 与 {@code FileRecordTagStore#parse} 完全同一条:{@code path("assertions")} 在缺键、
     * 键名写错时只会安静地给回一个 MissingNode,于是「解析成功、0 行声明」——
     * 而下一次 {@link #put} 是<b>全量重写</b>,那 0 行会盖掉磁盘上真实存在的声明。
     * <p>
     * 丢声明的后果不是覆盖率变化(它本来就不进那个数),是<b>用户按掉的考点集体回到盲区榜上</b> ——
     * 而那正是他按这个按钮想让它停下来的事。
     */
    private static List<UserAssertion> parse(JsonNode root) {
        JsonNode array = root.path("assertions");
        if (!array.isArray()) {
            throw new IllegalStateException(
                    "「我已掌握」数据里没有 assertions 数组 —— 宁可在这里失败,也不能当成 0 行,"
                            + "否则下一次写入会把磁盘上真实存在的声明整个盖掉");
        }

        List<UserAssertion> result = new ArrayList<>();
        for (JsonNode n : array) {
            try {
                result.add(toAssertion(n));
            } catch (IllegalArgumentException | DateTimeException e) {
                throw new IllegalStateException(
                        "「我已掌握」数据里有一行不合法:" + n.path("nodeCode").asString("?"), e);
            }
        }
        return result;
    }

    /** 一个 JSON 对象 → 一行声明。<b>只认这两个键,别的一概不看。</b> */
    private static UserAssertion toAssertion(JsonNode n) {
        return new UserAssertion(
                required(n, "nodeCode"),
                Instant.parse(required(n, "assertedAt")));
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asString("");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("声明行缺少必填字段:" + field);
        }
        return v;
    }

    // —— 写入 ——

    /**
     * 一行声明 → 一个 JSON 对象。
     *
     * <p>🔴 逐字段写,<b>不是</b>把 {@link UserAssertion} 交给 Jackson 自动序列化 ——
     * 与 {@code FileTouchStore#toNode} 同一个理由:自动序列化会跟着 record 的形状走,
     * 哪天有人给它加了个字段,那个字段就会不声不响地流进用户的数据文件。
     */
    private static ObjectNode toNode(UserAssertion a) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("nodeCode", a.nodeCode());     // 只有考点树里的 code,没有任何自己起的名字(R-07)
        o.put("assertedAt", a.assertedAt().toString());
        return o;
    }

    /** 先写临时文件 → fsync → 原子 rename。与 {@code FileTouchStore#writeAtomically} 同一份。 */
    private void writeAtomically(List<UserAssertion> all) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode comment = root.putArray("_comment");
            comment.add("「我已掌握」—— 每一行是「用户声明他会某个考点」。");
            comment.add("🔴 这里的每一行都【不计入覆盖度】(01 §5.2:补丁不是解法)。");
            comment.add("作用只有两个:从盲区榜里排除,和在概览里单列一格。那个百分比一个字都不动。");
            comment.add("它不是归档 —— 归档把考点从分母里拿掉,这里留在分母里(见 UserAssertion)。");
            ArrayNode arr = root.putArray("assertions");
            for (UserAssertion a : all) {
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
            throw new IllegalStateException("「我已掌握」写入失败:" + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不该盖掉真正的错误
            }
        }
    }
}
