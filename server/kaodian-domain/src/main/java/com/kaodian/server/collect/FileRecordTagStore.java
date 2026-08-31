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
 * {@link RecordTagStore} 的阶段 0/1 实现 —— <b>一个 JSON 文件,没有数据库。</b>
 *
 * <p>形态、纪律、失败方式全部照抄 {@link FileTouchStore}:全量重写 + 原子 rename、
 * 读写逐字段列举、坏文件宁可启动不了也不当成 0 行。理由不重复,见那个类;
 * 这里只写<b>不一样的那两处</b>。
 *
 * <h2>不一样之一:没有种子文件</h2>
 *
 * {@link FileTouchStore} 要播种,因为第一次跑起来得看见那 44%。这里不用 ——
 * 那 44% 由主标签推出来({@link RecordTag#effectiveTagsOf}),一行都不必存。
 * 造一个 {@code record-tags-demo.json} 去镜像那 8 条记录,反而多了一份<b>会和行为层对不上的状态</b>。
 *
 * <h2>不一样之二:{@link #put} 是这个文件唯一的写入口,而它带着一道拒绝</h2>
 *
 * {@code origin} / {@code recordId} / {@code nodeCode} 三个字段一旦落行就不许改
 * (契约见 {@link RecordTagStore#put})。这道检查放在<b>存储层</b>而不是只放在服务层,
 * 是因为服务层将来会有第二个调用者(补标、批量确认),而红线不能靠每个调用者自觉。
 */
@Component
public class FileRecordTagStore implements RecordTagStore {

    private static final String FILE_NAME = "record-tags.json";
    private static final String TMP_SUFFIX = ".tmp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    /** 单进程单用户,一把锁足够 —— 与 {@link FileTouchStore} 同一句。 */
    private final Object lock = new Object();

    /** {@code null} 表示还没载入。载入推迟到第一次访问。 */
    private List<RecordTag> tags;

    /**
     * @param dataDir 数据目录,默认 {@code ~/.kaodian}。与行为层同一个目录 ——
     *                「把这个目录拷走就是全部数据」这句话不能因为多了一张表就不成立
     */
    @Autowired
    public FileRecordTagStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public FileRecordTagStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    /** 数据文件的位置。 */
    public Path dataFile() {
        return file;
    }

    @Override
    public List<RecordTag> findAll() {
        synchronized (lock) {
            ensureLoaded();
            return List.copyOf(tags);
        }
    }

    @Override
    public List<RecordTag> findByRecord(String recordId) {
        synchronized (lock) {
            ensureLoaded();
            return tags.stream().filter(t -> t.recordId().equals(recordId)).toList();
        }
    }

    @Override
    public RecordTag find(String tagId) {
        synchronized (lock) {
            ensureLoaded();
            return lookup(tagId);
        }
    }

    /** 契约见 {@link RecordTagStore#put} —— <b>「查 + 校验 + 写」在同一把锁里</b>。 */
    @Override
    public RecordTag put(RecordTag tag) {
        synchronized (lock) {
            ensureLoaded();

            RecordTag existing = lookup(tag.id());
            if (existing != null) {
                // 🔴 origin 是来源不是状态(docs/技术架构 §5.2)。这一句是它的第三道锁 ——
                //    前两道在 RecordTag 上(record 没有 setter、confirm/discard 的签名里没有 origin 的位置),
                //    但那两道只挡得住「顺着现有 API 走」的人。这一道挡的是自己 new 一个再 put 进来的写法。
                if (existing.origin() != tag.origin()) {
                    throw new IllegalArgumentException(
                            "标签的 origin 写入后不可变:" + existing.origin().wireName()
                                    + " → " + tag.origin().wireName()
                                    + " —— 它记的是这条标签从哪来,不是它现在什么状态。"
                                    + "用户确认只写 confirmed_at(docs/技术架构 §5.2)");
                }
                if (!existing.recordId().equals(tag.recordId())) {
                    throw new IllegalArgumentException(
                            "标签不能换宿主记录 —— 覆盖度按记录去重,换了会一条记录数进两个考点");
                }
                if (!existing.nodeCode().equals(tag.nodeCode())) {
                    throw new IllegalArgumentException(
                            "标签不能原地改挂考点 —— 改挂是「丢弃这条、新挂一条」,"
                                    + "原地改会让「我曾经把它标成什么」这件事消失");
                }
            }

            List<RecordTag> next = new ArrayList<>(tags.size() + 1);
            boolean replaced = false;
            for (RecordTag t : tags) {
                if (t.id().equals(tag.id())) {
                    next.add(tag);
                    replaced = true;
                } else {
                    next.add(t);
                }
            }
            if (!replaced) {
                next.add(tag);
            }

            // 先落盘再改内存:写失败时内存与磁盘仍然一致(与 FileTouchStore 同一条纪律)
            writeAtomically(next);
            tags = next;
            return tag;
        }
    }

    /** 契约见 {@link RecordTagStore#deleteByRecord}。 */
    @Override
    public int deleteByRecord(String recordId) {
        synchronized (lock) {
            ensureLoaded();
            List<RecordTag> next = new ArrayList<>(tags.size());
            int removed = 0;
            for (RecordTag t : tags) {
                if (t.recordId().equals(recordId)) {
                    removed++;
                } else {
                    next.add(t);
                }
            }
            if (removed == 0) {
                return 0;               // 什么都没变,不必写盘
            }
            writeAtomically(next);
            tags = next;
            return removed;
        }
    }

    @Override
    public int count() {
        synchronized (lock) {
            ensureLoaded();
            return tags.size();
        }
    }

    /** 调用方必须已经持有 {@link #lock} 且已 {@link #ensureLoaded}。 */
    private RecordTag lookup(String tagId) {
        if (tagId == null || tagId.isBlank()) {
            return null;
        }
        for (RecordTag t : tags) {
            if (tagId.equals(t.id())) {
                return t;
            }
        }
        return null;
    }

    // —— 载入 ——

    /**
     * 推迟到第一次访问才载入 —— 与 {@link FileTouchStore#ensureLoaded} 同一个理由:
     * 构造 bean 是一件不该有副作用的事,起一次上下文就往 {@code ~/.kaodian} 写文件,
     * 会让每一次跑测试都污染真实用户目录。
     *
     * <p>文件不存在时<b>不写盘</b>,只当成空表。这与行为层不同:行为层要播种,
     * 播种就得先落盘让「文件是唯一事实来源」成立;这里没有种子,空表就是空表,
     * 第一次 {@link #put} 时自然会把文件建出来。
     */
    private void ensureLoaded() {
        if (tags != null) {
            return;
        }
        tags = Files.exists(file) ? read() : List.of();
    }

    private List<RecordTag> read() {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(MAPPER.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("标签数据文件读取失败:" + file, e);
        }
    }

    /**
     * 解析一份标签 JSON。
     *
     * <h2>认不出来就吵着失败,绝不当成 0 行</h2>
     *
     * 与 {@code FileTouchStore#parse} 完全同一条:{@code path("tags")} 在缺键、键名写错时
     * 只会安静地给回一个 MissingNode,于是「解析成功、0 行标签」——而下一次 {@link #put}
     * 是<b>全量重写</b>,那 0 行会盖掉磁盘上真实存在的标签。
     * <p>
     * 丢标签比丢记录轻,但不是没有后果:用户丢弃过的错标会集体复活,重新计进覆盖度,
     * 而<b>覆盖度就是这个产品</b>。
     */
    private static List<RecordTag> parse(JsonNode root) {
        JsonNode array = root.path("tags");
        if (!array.isArray()) {
            throw new IllegalStateException(
                    "标签数据里没有 tags 数组 —— 宁可在这里失败,也不能当成 0 行,"
                            + "否则下一次写入会把磁盘上真实存在的标签整个盖掉");
        }

        List<RecordTag> result = new ArrayList<>();
        for (JsonNode n : array) {
            try {
                result.add(toTag(n));
            } catch (IllegalArgumentException | DateTimeException e) {
                // 与 FileTouchStore#parse 同一条:领域构造器的消息是写给接口调用方看的,
                // 而这里的输入是磁盘上一份坏文件 —— 那是服务端的事,得吵出来。
                throw new IllegalStateException(
                        "标签数据里有一行不合法:" + n.path("id").asString("?"), e);
            }
        }
        return result;
    }

    /** 一个 JSON 对象 → 一行标签。<b>只认这几个键,别的一概不看。</b> */
    private static RecordTag toTag(JsonNode n) {
        String confirmedAt = n.path("confirmedAt").asString("");
        return new RecordTag(
                required(n, "id"),
                required(n, "recordId"),
                required(n, "nodeCode"),
                n.path("confidence").asDouble(RecordTag.MANUAL_CONFIDENCE),
                TagOrigin.ofWireName(required(n, "origin")),
                confirmedAt.isEmpty() ? null : Instant.parse(confirmedAt),
                n.path("discarded").asBoolean(false));
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asString("");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("标签行缺少必填字段:" + field);
        }
        return v;
    }

    // —— 写入 ——

    /**
     * 一行标签 → 一个 JSON 对象。
     *
     * <p>🔴 逐字段写,<b>不是</b>把 {@link RecordTag} 交给 Jackson 自动序列化 ——
     * 与 {@code FileTouchStore#toNode} 同一个理由:自动序列化会跟着 record 的形状走,
     * 哪天有人给它加了个字段,那个字段就会不声不响地流进用户的数据文件。
     */
    private static ObjectNode toNode(RecordTag t) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("id", t.id());
        o.put("recordId", t.recordId());
        o.put("nodeCode", t.nodeCode());         // 只有考点树里的 code,没有任何标签文字(R-07)
        o.put("confidence", t.confidence());
        o.put("origin", t.origin().wireName());
        if (t.confirmedAt() != null) {
            o.put("confirmedAt", t.confirmedAt().toString());
        }
        o.put("discarded", t.discarded());
        return o;
    }

    /** 先写临时文件 → fsync → 原子 rename。与 {@code FileTouchStore#writeAtomically} 同一份。 */
    private void writeAtomically(List<RecordTag> all) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode comment = root.putArray("_comment");
            comment.add("标签层 —— 每一行是「某条记录挂在某个考点上」。");
            comment.add("🔴 只有考点树里的 code,没有任何标签文字:标签不能自己起名(R-07)。");
            comment.add("origin 记的是这条标签从哪来,不是它现在什么状态 —— 确认只写 confirmedAt。");
            comment.add("discarded=true 的行仍然可见,但不计覆盖度(P1-7)。");
            comment.add("采集时挑的那条主标签不在这个文件里 —— 它由记录本身推出来,见 RecordTag。");
            ArrayNode arr = root.putArray("tags");
            for (RecordTag t : all) {
                arr.add(toNode(t));
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
            throw new IllegalStateException("标签层写入失败:" + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不该盖掉真正的错误
            }
        }
    }
}
