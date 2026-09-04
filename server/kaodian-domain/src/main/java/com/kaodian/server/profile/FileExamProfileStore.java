package com.kaodian.server.profile;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ExamProfileStore} 的阶段 0/1 实现 —— <b>一个 JSON 文件,没有数据库。</b>
 *
 * <p>形态、纪律、失败方式全部照抄 {@code FileAssertionStore}:全量重写 + 原子 rename、
 * 读写逐字段列举、坏文件宁可启动不了也不当成 0 行、没有种子文件。
 * 理由不重复,见那个类;这里只写<b>不一样的那两处</b>。
 *
 * <h2>不一样之处 ①:主键是 {@code userId},一个人只有一行</h2>
 *
 * {@code FileAssertionStore} 的主键是 {@code (userId, nodeCode)} 这一对,而这里只有前半截。
 * 于是 {@link #put} 的语义不是「已经有了就原样返回」,而是<b>照直覆盖</b> ——
 * 备考档案本来就该随时改,「改了不生效」比「改错了」严重得多。
 * <p>
 * 🔴 覆盖是<b>就地替换那一行</b>,不是追加一行新的再按时间取最新:
 * 后者会让文件里攒出一条时间轴,而那条时间轴就是「你的备考轨迹」——
 * 一旦攒出来,有人只需要写一个读取方法就能把它端上屏(§12.9.1:不留历史)。
 *
 * <h2>不一样之处 ②:两个业务字段都<b>可以缺</b>,而缺不是坏文件</h2>
 *
 * {@code FileAssertionStore#required} 那一套在这里只对 {@code userId} 与 {@code updatedAt} 用。
 * {@code examType} / {@code examDate} 缺席是<b>合法状态</b>(两格皆空 = 清空),
 * 把它们也当必填,一个刚清空过档案的用户会让整个服务起不来。
 * <p>
 * 但「日期写成了 {@code 2027-13-45}」仍然是坏文件,照样吵着失败 ——
 * <b>「可以没有」与「可以是垃圾」是两件事</b>。
 */
@Component
public class FileExamProfileStore implements ExamProfileStore {

    private static final String FILE_NAME = "exam-profiles.json";
    private static final String TMP_SUFFIX = ".tmp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    /** 单进程单用户,一把锁足够 —— 与 {@code FileAssertionStore} 同一句。 */
    private final Object lock = new Object();

    /** {@code null} 表示还没载入。载入推迟到第一次访问。 */
    private List<ExamProfile> profiles;

    /**
     * @param dataDir 数据目录,默认 {@code ~/.kaodian}。与其它几张表同一个目录 ——
     *                「把这个目录拷走就是全部数据」这句话不能因为多了一张表就不成立
     */
    @Autowired
    public FileExamProfileStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public FileExamProfileStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    /** 数据文件的位置。 */
    public Path dataFile() {
        return file;
    }

    @Override
    public ExamProfile find(long userId) {
        requireUserId(userId);
        synchronized (lock) {
            ensureLoaded();
            return lookup(userId);
        }
    }

    /** 契约见 {@link ExamProfileStore#put} —— <b>按 {@code userId} 就地覆盖,不追加</b>。 */
    @Override
    public void put(ExamProfile profile) {
        requireUserId(profile.userId());
        synchronized (lock) {
            ensureLoaded();

            List<ExamProfile> next = new ArrayList<>(profiles.size() + 1);
            for (ExamProfile p : profiles) {
                // 🔴 同一个人的旧行【不进 next】—— 这一句就是「不留历史」的全部实现。
                //    改成 next.add(p) 之后一切照常工作(读取取最后一条),
                //    唯一的变化是文件里开始攒出一条时间轴,而那正是禁掉的那样东西。
                if (p.userId() != profile.userId()) {
                    next.add(p);
                }
            }
            next.add(profile);

            // 先落盘再改内存:写失败时内存与磁盘仍然一致(与 FileAssertionStore 同一条纪律)
            writeAtomically(next);
            profiles = next;
        }
    }

    /** 调用方必须已经持有 {@link #lock} 且已 {@link #ensureLoaded}。 */
    private ExamProfile lookup(long userId) {
        for (ExamProfile p : profiles) {
            if (p.userId() == userId) {
                return p;
            }
        }
        return null;
    }

    /**
     * 🔴 只校验形状,不查存在性 —— 与 {@code ExamProfile} 的构造器同一句,
     * 理由见那里(B0-3 §4.3:查存在性会把 domain → auth 那条边建出来)。
     */
    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException(
                    "userId 必须是正数,拿到的是 " + userId
                            + " —— 0 不是「暂时没有用户」的意思,它根本不是一个合法用户(B0 §3.3)");
        }
    }

    // —— 载入 ——

    /**
     * 推迟到第一次访问才载入,文件不存在时<b>不写盘</b>,只当成空表。
     *
     * <p>理由与 {@code FileAssertionStore#ensureLoaded} 逐字相同:这张表同样没有种子
     * (没有人「默认要考国考」),而起一次上下文就往 {@code ~/.kaodian} 写文件,
     * 会让每一次跑测试都污染真实用户目录。
     */
    private void ensureLoaded() {
        if (profiles != null) {
            return;
        }
        profiles = Files.exists(file) ? read() : List.of();
    }

    private List<ExamProfile> read() {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(MAPPER.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("备考档案数据文件读取失败:" + file, e);
        }
    }

    /**
     * 解析一份档案 JSON。<b>认不出来就吵着失败,绝不当成 0 行。</b>
     *
     * <p>与 {@code FileAssertionStore#parse} 同一条:{@code path("profiles")} 在缺键、
     * 键名写错时只会安静地给回一个 MissingNode,于是「解析成功、0 行档案」——
     * 而下一次 {@link #put} 是<b>全量重写</b>,那 0 行会盖掉磁盘上真实存在的档案。
     * <p>
     * 丢档案的后果是:用户下次进「问一下」时被<b>再问一遍</b>他早就答过的问题,
     * 而端上判「该不该出档案屏」的依据只有这一个响应体(§5.4)。
     */
    private static List<ExamProfile> parse(JsonNode root) {
        JsonNode array = root.path("profiles");
        if (!array.isArray()) {
            throw new IllegalStateException(
                    "备考档案数据里没有 profiles 数组 —— 宁可在这里失败,也不能当成 0 行,"
                            + "否则下一次写入会把磁盘上真实存在的档案整个盖掉");
        }

        List<ExamProfile> result = new ArrayList<>();
        for (JsonNode n : array) {
            try {
                result.add(toProfile(n));
            } catch (IllegalArgumentException | DateTimeException e) {
                // 🔴 消息里只有 userId,没有那两个业务字段的值 —— 与接口层那条纪律同一条。
                throw new IllegalStateException(
                        "备考档案里有一行不合法:userId=" + n.path("userId").asLong(0), e);
            }
        }
        return result;
    }

    /** 一个 JSON 对象 → 一行档案。<b>只认这四个键,别的一概不看。</b> */
    private static ExamProfile toProfile(JsonNode n) {
        return new ExamProfile(
                n.path("userId").asLong(0),
                // 🔴 缺席是合法的(两格皆空 = 清空),所以这两个不走 required
                optional(n, "examType"),
                date(optional(n, "examDate")),
                Instant.parse(required(n, "updatedAt")));
    }

    private static String optional(JsonNode n, String field) {
        String v = n.path(field).asString("");
        return v.isEmpty() ? null : v;
    }

    /** ⚠️ 「可以没有」与「可以是垃圾」是两件事:缺席返回 {@code null},写坏了照样抛。 */
    private static LocalDate date(String raw) {
        return raw == null ? null : LocalDate.parse(raw);
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asString("");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("档案行缺少必填字段:" + field);
        }
        return v;
    }

    // —— 写入 ——

    /**
     * 一行档案 → 一个 JSON 对象。
     *
     * <p>🔴 逐字段写,<b>不是</b>把 {@link ExamProfile} 交给 Jackson 自动序列化 ——
     * 与 {@code FileAssertionStore#toNode} 同一个理由:自动序列化会跟着 record 的形状走,
     * 哪天有人给它加了个字段,那个字段就会不声不响地流进用户的数据文件。
     *
     * <p>🔴 空字段<b>整个键不写</b>,不写 {@code null}:文件里的形状与
     * {@code GET} 出口的空值规则保持同一副 —— 两处不一致时,先分叉的一定是没人看的那一处。
     */
    private static ObjectNode toNode(ExamProfile p) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("userId", p.userId());
        if (p.examType() != null) {
            o.put("examType", p.examType());
        }
        if (p.examDate() != null) {
            o.put("examDate", p.examDate().toString());   // ISO,YYYY-MM-DD,不带时分秒
        }
        o.put("updatedAt", p.updatedAt().toString());
        return o;
    }

    /** 先写临时文件 → fsync → 原子 rename。与 {@code FileAssertionStore#writeAtomically} 同一份。 */
    private void writeAtomically(List<ExamProfile> all) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode comment = root.putArray("_comment");
            comment.add("备考档案 —— 每人最多一行:「我要考的那一场」是哪一场、哪一天。");
            comment.add("🔴 一个人只有一行,改了就覆盖。这里【没有历史】——");
            comment.add("留了历史就长出「你的备考轨迹」,那是学习分析(接口契约 §12.9.1)。");
            comment.add("两格都可以缺席,缺席 = 没设过 / 已清空,两者在契约上是同一件事。");
            ArrayNode arr = root.putArray("profiles");
            for (ExamProfile p : all) {
                arr.add(toNode(p));
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
            throw new IllegalStateException("备考档案写入失败:" + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不该盖掉真正的错误
            }
        }
    }
}
