package com.kaodian.server.collect;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link TouchStore} 的阶段 0/1 实现 —— <b>一个 JSON 文件,没有数据库。</b>
 *
 * <h2>为什么是文件</h2>
 *
 * docs/technical/INDEX.md §零 的落地阶段表写着:数据层落库最早到<b>阶段 1 的 {@code 1.2.4}</b>,
 * 「阶段 0 是本地文件夹 + 纯文本」,阶段 0/1 全本地、不需要服务器。
 * 现在提前上 MySQL,买到的只是一个要运维的进程,和一次没人要求的迁移。
 * <p>
 * 思考模式 §盲区二记着这个项目自己的失败模式:<b>注意力流向能做的部分,不是最不确定的部分。</b>
 * 存储是「能做」的那一侧,所以它就该只花一个文件的力气。
 *
 * <h2>🔴 读写都是逐字段列举的,不用自动序列化</h2>
 *
 * 写:文件里能出现哪些键,由 {@link #toNode} 显式列出。
 * 读:文件里出现别的键一律被忽略,进不了内存 —— {@link TouchSeed#toTouch} 只认那几个。
 * <p>
 * 于是即便有人手工往 {@code touches.json} 里塞了一段题干,它也<b>到不了任何地方</b>:
 * 既不会被读进来,更不会因为 {@link Touch} 将来多了个字段就悄悄流回文件。
 * 这与 {@link com.kaodian.server.syllabus.SyllabusLoader} 是同一条思路 ——
 * 不给内容留位置(决策记录 §2.2 / docs/technical/INDEX.md §5.1)。
 *
 * <h2>写入:先写临时文件,再原子 rename</h2>
 *
 * 全量重写 + 原子替换,是「文件当库」唯一不会写坏的做法:
 * 直接在原文件上截断重写,写到一半断电就是一个半截 JSON —— 全部记录一起没。
 * 记录是这个产品的全部资产,不能有这种失败模式。
 * <p>
 * 全量重写在几百条量级完全够用;它撑不住的那天,正好就是 {@code 1.2.4} 换 JDBC 实现的那天,
 * 而那时 {@link com.kaodian.server.coverage.CoverageService} 一行都不用改(见 {@link TouchStore})。
 */
@Component
// KUBI-112:同类型的两个 store 只能起一个,否则上下文里两个 TouchStore bean 直接冲突。
// 不写这个键时走 file —— 默认值的理由见 application.properties 那一节。
@ConditionalOnProperty(name = "kaodian.data.store", havingValue = "file", matchIfMissing = true)
public class FileTouchStore implements TouchStore {

    private static final String FILE_NAME = "touches.json";
    private static final String TMP_SUFFIX = ".tmp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    /**
     * 播种时的「第 0 天」从哪来。
     *
     * <p>种子里的 {@code daysAgo} 全部相对它换算,所以它必须和差集运算用的是<b>同一个时钟</b>。
     * 图省事直接写 {@code Instant.now()} 的话,一旦有人把 {@link Clock} 换成固定时刻回放场景
     * (ApiBeans 里那个 bean 就是为此存在的),种子会落在真实的今天、差集却按固定时刻算 ——
     * 两条时间线一错开,「稳3·弱2·生疏2」这个契约会<b>不报错地</b>变成另一组数。
     */
    private final Clock clock;

    /** 单进程单用户,一把锁足够。这里不需要读写锁 —— 争用根本不存在。 */
    private final Object lock = new Object();

    /** {@code null} 表示还没载入。载入推迟到第一次访问,见 {@link #ensureLoaded}。 */
    private List<Touch> touches;

    /**
     * @param dataDir 数据目录,默认 {@code ~/.kaodian}。
     *                做成可配置是为了测试能指到临时目录,顺带让「换台机器怎么搬数据」有答案:
     *                <b>把这个目录拷走就是全部数据</b>——这也是 决策记录 §2.6「完整数据导出」最朴素的形态。
     * @param clock   播种基准时刻的来源,与差集运算共用同一个 bean
     */
    @Autowired
    public FileTouchStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir, Clock clock) {
        this(Path.of(dataDir).resolve(FILE_NAME), clock);
    }

    /** 不关心播种时刻的调用方(大多数测试)走这个,行为与旧签名一致。 */
    public FileTouchStore(Path file) {
        this(file, Clock.systemUTC());
    }

    public FileTouchStore(Path file, Clock clock) {
        this.file = file.toAbsolutePath();
        this.clock = clock;
    }

    /** 数据文件的位置。导出、备份、「我的数据到底存在哪」都指着它。 */
    public Path dataFile() {
        return file;
    }

    @Override
    public List<Touch> findAll() {
        synchronized (lock) {
            ensureLoaded();
            return List.copyOf(touches);
        }
    }

    @Override
    public List<Touch> findByNode(String nodeCode) {
        synchronized (lock) {
            ensureLoaded();
            return touches.stream().filter(t -> t.nodeCode().equals(nodeCode)).toList();
        }
    }

    @Override
    public Touch findByClientToken(String clientToken) {
        synchronized (lock) {
            ensureLoaded();
            return lookup(clientToken);
        }
    }

    /**
     * 追加一条记录。契约见 {@link TouchStore#append} —— <b>「查去重键 + 写」在同一把锁里。</b>
     *
     * <p>查重是线性扫描,没有索引。几百条量级下这是对的:为它建一张 {@code Map} 就要多一份
     * 与 {@code touches} 同生共死的状态,而<b>两份状态迟早对不上</b>(reassign、delete 都得记得维护它)。
     * 它撑不住的那天,正好就是 {@code 1.2.4} 换 JDBC 的那天 —— 那时候去重键是一个唯一索引,
     * 而唯一索引本来就是数据库该干的事,不是这个文件该干的事。
     */
    @Override
    public Touch append(Touch touch) {
        synchronized (lock) {
            ensureLoaded();

            // 🔴 幂等:同一个去重键重复提交 → 返回原来那条,不新建、不报错、不覆盖。
            // 「不覆盖」这一条要紧:补传的那份带的是【补传时刻】的服务端时间戳,
            // 拿它盖掉第一次落地的 occurredAt,等于让一条记录凭空变年轻 ——
            // 而「多久前」是五态里唯一的时间依据(见 reassign 里同一句话)。
            Touch existing = lookup(touch.clientToken());
            if (existing != null) {
                return existing;
            }

            List<Touch> next = new ArrayList<>(touches);
            next.add(touch);
            next.sort(Comparator.comparing(Touch::occurredAt));

            // 先落盘再改内存:写失败时内存与磁盘仍然一致,不会出现「界面上有、文件里没有」的记录。
            writeAtomically(next);
            touches = next;
            return touch;
        }
    }

    /** 契约见 {@link TouchStore#delete} —— 删一条不存在的记录返回 {@code null},不抛异常。 */
    @Override
    public Touch delete(String id) {
        synchronized (lock) {
            ensureLoaded();
            Touch victim = null;
            List<Touch> next = new ArrayList<>(touches.size());
            for (Touch t : touches) {
                // id 相同的只可能有一条(服务端签发的 UUID),但这里仍然写成「留下所有不匹配的」
                // 而不是 remove 第一个命中的:万一历史文件里真有两条同 id,一次删干净好过留半条。
                if (t.id().equals(id)) {
                    victim = t;
                } else {
                    next.add(t);
                }
            }
            if (victim == null) {
                return null;                    // 什么都没变,不必写盘
            }
            // 先落盘再改内存,与 append 同一条纪律
            writeAtomically(next);
            touches = next;
            return victim;
        }
    }

    /** 调用方必须已经持有 {@link #lock} 且已 {@link #ensureLoaded}。 */
    private Touch lookup(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) {
            return null;                        // 「没有去重键」不是一个能互相匹配的值
        }
        for (Touch t : touches) {
            if (clientToken.equals(t.clientToken())) {
                return t;
            }
        }
        return null;
    }

    @Override
    public int count() {
        synchronized (lock) {
            ensureLoaded();
            return touches.size();
        }
    }

    /**
     * 整体改挂。契约见 {@link TouchStore#reassign} —— <b>搬家,不扔东西。</b>
     *
     * <p>实现上刻意写成「逐条重建、只换 nodeCode」而不是删旧建新:
     * 新建会生成新的 id 和新的时间戳,而 {@code occurredAt} 是「多久前」的唯一依据,
     * 重置它等于让一批记录集体变年轻 —— 覆盖率不动,五态却会不报错地整体漂移。
     */
    @Override
    public int reassign(String fromNodeCode, String toNodeCode) {
        synchronized (lock) {
            ensureLoaded();
            List<Touch> next = new ArrayList<>(touches.size());
            int moved = 0;
            for (Touch t : touches) {
                if (t.nodeCode().equals(fromNodeCode)) {
                    // clientToken 也要原样带过去:丢了它,那条记录就重新变得可以被补传一次
                    next.add(new Touch(t.id(), toNodeCode, t.sourceName(), t.kind(),
                            t.occurredAt(), t.drill(), t.clientToken()));
                    moved++;
                } else {
                    next.add(t);
                }
            }
            if (moved == 0) {
                return 0;                       // 什么都没变,不必写盘
            }
            // 先落盘再改内存,与 append 同一条纪律
            writeAtomically(next);
            touches = next;
            return moved;
        }
    }

    // —— 载入与播种 ——

    /**
     * 推迟到第一次访问才载入 —— <b>不用 {@code @PostConstruct}</b>。
     *
     * <p>理由很具体:构造 bean 是一件不该有副作用的事。启动一次 Spring 上下文就往
     * {@code ~/.kaodian} 里写文件,会让每一次跑测试都污染真实用户目录。
     */
    private void ensureLoaded() {
        if (touches != null) {
            return;
        }
        if (Files.exists(file)) {
            touches = read();
            return;
        }
        // 第一次跑:播种。先落盘再进内存,让「文件是唯一事实来源」从第一秒就成立。
        // 🔴 种子解析在 TouchSeed,不在这里 —— KUBI-112 之后 JdbcTouchStore 要播同一份,
        //    而两份各自解析的种子对不上时,表现是「换个存储后端覆盖率就换个数」,不是编译错误。
        List<Touch> seeded = TouchSeed.load(clock);
        writeAtomically(seeded);
        touches = seeded;
    }

    private List<Touch> read() {
        try (InputStream in = Files.newInputStream(file)) {
            // seedAt 传 null:落盘文件里只会有绝对 occurredAt,相对天数只在种子里出现。
            return TouchSeed.parse(MAPPER.readTree(in), null);
        } catch (IOException e) {
            throw new IllegalStateException("行为层数据文件读取失败:" + file, e);
        }
    }

    // —— 写入 ——

    /**
     * 一条记录 → 一个 JSON 对象。
     *
     * <p>🔴 这里逐字段写,<b>不是</b>把 {@link Touch} 交给 Jackson 自动序列化。
     * 自动序列化会跟着 record 的形状走 —— 哪天有人给 {@link Touch} 加了个字段,
     * 它就会不声不响地流进用户的数据文件。逐字段写让「文件里能出现哪些键」
     * 是这段代码显式列出来的,加字段必须先过这里。
     */
    private static ObjectNode toNode(Touch t) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("id", t.id());
        o.put("nodeCode", t.nodeCode());
        o.put("sourceName", t.sourceName());     // 只有来源【名字】,没有来源的内容
        o.put("kind", t.kind().name());
        o.put("occurredAt", t.occurredAt().toString());
        if (t.drill() != null) {
            o.put("practiced", t.drill().practiced());
            o.put("correct", t.drill().correct());   // 用户自己填的数,不是判出来的
        }
        if (t.clientToken() != null) {
            // 🔴 去重键必须落盘。只留在内存里的话,进程一重启,离线队列里那批记录就能再补传一次,
            // 而那正是 R-32 的防线要挡的场景 —— 无网时记的东西不能变成双份。
            o.put("clientToken", t.clientToken());
        }
        return o;
    }

    /** 先写临时文件 → fsync → 原子 rename。中途断电最坏结果是这次写入没发生,已有记录不会坏。 */
    private void writeAtomically(List<Touch> all) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode comment = root.putArray("_comment");
            comment.add("行为层记录 —— 你碰过哪些考点、什么时候碰的。");
            comment.add("🔴 只有来源名与时间戳。没有课程内容、没有题干、没有转写文本、没有图片。");
            comment.add("practiced / correct 是你自己填的两个数,不是产品判出来的分。");
            ArrayNode arr = root.putArray("touches");
            for (Touch t : all) {
                arr.add(toNode(t));
            }

            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            Files.write(tmp, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            // 落到盘面再 rename。少了这一步,rename 是原子的但内容可能还在页缓存里。
            // 目录项本身不 fsync:阶段 0 单机单用户,为此多写的代码不值那点概率。
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }

            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("行为层写入失败:" + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);   // 失败路径上别留半截文件误导下一次
            } catch (IOException ignored) {
                // 清理失败不该盖掉真正的错误
            }
        }
    }
}
