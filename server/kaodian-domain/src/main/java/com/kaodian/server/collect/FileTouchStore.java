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
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
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
 * 读:文件里出现别的键一律被忽略,进不了内存 —— {@link #toTouch} 只认那几个。
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
public class FileTouchStore implements TouchStore {

    /** 行为层种子。第一次跑起来就能看见 44%,而不是一个空白页。 */
    private static final String SEED_RESOURCE = "/seed/touches-demo.json";

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
    public List<Touch> findAll(long userId) {
        Tenant.requireUserId(userId);
        synchronized (lock) {
            ensureLoaded();
            return touches.stream().filter(t -> t.userId() == userId).toList();
        }
    }

    /** 契约见 {@link TouchStore#findAllAcrossUsers()} —— 有意的跨用户口,今天只剩 agent 那条路。 */
    @Override
    public List<Touch> findAllAcrossUsers() {
        synchronized (lock) {
            ensureLoaded();
            return List.copyOf(touches);
        }
    }

    /** 契约见 {@link TouchStore#countByNodeAcrossUsers} —— 删除守则数的是全库,不是当前这个人。 */
    @Override
    public int countByNodeAcrossUsers(String nodeCode) {
        synchronized (lock) {
            ensureLoaded();
            int n = 0;
            for (Touch t : touches) {
                if (t.nodeCode().equals(nodeCode)) {
                    n++;
                }
            }
            return n;
        }
    }

    @Override
    public Touch findByClientToken(long userId, String clientToken) {
        Tenant.requireUserId(userId);
        synchronized (lock) {
            ensureLoaded();
            return lookup(userId, clientToken);
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
            // 🔴 判重按 (userId, clientToken):两个人的客户端各自生成键,彼此之间没有任何约定。
            Touch existing = lookup(touch.userId(), touch.clientToken());
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

    /**
     * 契约见 {@link TouchStore#delete} —— 删一条不存在的记录返回 {@code null},不抛异常。
     *
     * <p>🔴 {@code userId} 参与匹配:别人的记录在这里等于不存在。
     */
    @Override
    public Touch delete(long userId, String id) {
        Tenant.requireUserId(userId);
        synchronized (lock) {
            ensureLoaded();
            Touch victim = null;
            List<Touch> next = new ArrayList<>(touches.size());
            for (Touch t : touches) {
                // id 相同的只可能有一条(服务端签发的 UUID),但这里仍然写成「留下所有不匹配的」
                // 而不是 remove 第一个命中的:万一历史文件里真有两条同 id,一次删干净好过留半条。
                if (t.userId() == userId && t.id().equals(id)) {
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
    private Touch lookup(long userId, String clientToken) {
        if (clientToken == null || clientToken.isBlank()) {
            return null;                        // 「没有去重键」不是一个能互相匹配的值
        }
        for (Touch t : touches) {
            if (t.userId() == userId && clientToken.equals(t.clientToken())) {
                return t;
            }
        }
        return null;
    }

    @Override
    public int count(long userId) {
        Tenant.requireUserId(userId);
        synchronized (lock) {
            ensureLoaded();
            int n = 0;
            for (Touch t : touches) {
                if (t.userId() == userId) {
                    n++;
                }
            }
            return n;
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
                    // clientToken 与归属都要原样带过去:丢了 clientToken,那条记录就重新变得
                    // 可以被补传一次;改了归属,它就成了另一个人的记录。搬家换的只有 nodeCode。
                    next.add(new Touch(t.id(), t.userId(), toNodeCode, t.sourceName(), t.kind(),
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
        List<Touch> seeded = readSeed(clock.instant());
        writeAtomically(seeded);
        touches = seeded;
    }

    private List<Touch> read() {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(MAPPER.readTree(in), null);
        } catch (IOException e) {
            throw new IllegalStateException("行为层数据文件读取失败:" + file, e);
        }
    }

    /**
     * 从 classpath 播种。
     *
     * @param seedAt 播种时刻。种子里的 {@code daysAgo} 以它为第 0 天 ——
     *               <b>种子不写死日期,否则放几天后「生疏」的判定就漂了</b>
     *               ({@link com.kaodian.server.coverage.NodeState#RUSTY_AFTER} 是 30 天)。
     *               落盘之后存的是绝对时间戳,此后记录会随真实时间自然变旧;
     *               这不是缺陷,「多久前」本来就是产品的三个维度之一。
     */
    private List<Touch> readSeed(Instant seedAt) {
        try (InputStream in = FileTouchStore.class.getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("找不到行为层种子文件:" + SEED_RESOURCE);
            }
            return parse(MAPPER.readTree(in), seedAt);
        } catch (IOException e) {
            throw new IllegalStateException("行为层种子文件读取失败:" + SEED_RESOURCE, e);
        }
    }

    /**
     * 解析一份行为层 JSON。
     *
     * <h2>认不出来就吵着失败,绝不当成 0 条</h2>
     *
     * {@code path("touches")} 在缺键、根节点是数组、键名写错时都只是安静地给回一个 MissingNode,
     * 于是「解析成功、0 条记录」——而下一次 {@link #append} 是<b>全量重写</b>,
     * 那 0 条会原样盖掉磁盘上真实存在的记录。{@code 坏文件 → 空数据 → 覆盖} 这条链走完,
     * 用户丢的是这个产品的全部资产,而且全程没有一行报错。
     * <p>
     * 所以这里要求 {@code touches} <b>必须是一个数组</b>:宁可启动不了,也不要静默清空。
     */
    private static List<Touch> parse(JsonNode root, Instant seedAt) {
        JsonNode array = root.path("touches");
        if (!array.isArray()) {
            throw new IllegalStateException(
                    "行为层数据里没有 touches 数组 —— 宁可在这里失败,也不能当成 0 条记录,"
                            + "否则下一次追加会把磁盘上真实存在的记录整个盖掉");
        }

        List<Touch> result = new ArrayList<>();
        for (JsonNode n : array) {
            // 🔴 没有归属的条目【丢弃】,不认领给任何人 —— 理由见 OrphanGuard。
            // 走到这里说明 kaodian.collect.accept-orphan-loss 已经被显式置为 true
            // (否则 OrphanGuard 在启动期就把进程拦住了),条数已经记在那条 ERROR 日志里。
            if (OrphanGuard.isOrphan(n)) {
                continue;
            }
            try {
                result.add(toTouch(n, seedAt));
            } catch (IllegalArgumentException | DateTimeException e) {
                // 领域构造器(Touch / Drill / TouchKind.valueOf)的校验消息是写给【接口调用方】看的,
                // 接口层据此回 400「你的请求不合法」并原样回显。可这里的输入不是请求,是磁盘上的
                // 一份坏文件 —— 那是服务端的事,得 5xx 吵出来,而不是让前端收到一句
                // 「No enum constant com.kaodian.server.collect.TouchKind.X」。
                throw new IllegalStateException(
                        "行为层数据里有一条记录不合法:" + n.path("id").asString("?"), e);
            }
        }
        result.sort(Comparator.comparing(Touch::occurredAt));   // 契约:按发生时间升序
        return result;
    }

    /**
     * 一个 JSON 对象 → 一条记录。<b>只认这几个键,别的一概不看。</b>
     *
     * @param seedAt 非 null 时允许用相对天数 {@code daysAgo};落地文件里只会有绝对 {@code occurredAt}
     */
    private static Touch toTouch(JsonNode n, Instant seedAt) {
        Instant at;
        if (n.has("occurredAt")) {
            at = Instant.parse(required(n, "occurredAt"));
        } else if (seedAt != null && n.has("daysAgo")) {
            at = seedAt.minus(Duration.ofDays(n.path("daysAgo").asInt(0)));
        } else {
            throw new IllegalStateException("记录缺少时间:" + n.path("id").asString("?")
                    + " —— 「多久前」全靠它");
        }

        // 没有 practiced 就是没做题(仅接触)。不是 0 道,是这条记录里根本没有做题这回事。
        Touch.Drill drill = n.has("practiced")
                ? new Touch.Drill(n.path("practiced").asInt(0), n.path("correct").asInt(0))
                : null;

        // 没有 clientToken 就是没有 —— 空串会被 Touch 的构造器归一成 null,
        // 好过让一堆「都没填」的老记录在 append 里互相判重。
        String clientToken = n.path("clientToken").asString("");

        return new Touch(
                required(n, "id"),
                n.path(OrphanGuard.USER_ID).asLong(0),
                required(n, "nodeCode"),
                n.path("sourceName").asString(""),
                TouchKind.valueOf(required(n, "kind")),
                at,
                drill,
                clientToken);
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asString("");
        if (v.isEmpty()) {
            throw new IllegalStateException("行为层记录缺少必填字段:" + field);
        }
        return v;
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
        o.put(OrphanGuard.USER_ID, t.userId());   // B0-3 租户列。没有它的条目读回来会被丢弃
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
