package com.kaodian.server.api.events;

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
import java.util.Set;

/**
 * 「主动查看盲区」这个事件的落点 —— <b>一个 JSON 文件,没有数据库</b>
 * ({@code M3-骨架与覆盖度差集} §6.5)。
 *
 * <h2>🔴 它落在 {@code app},不进 {@code domain}(契约 §11.1:「埋点,不进领域」)</h2>
 *
 * {@code domain} 是那条公式({@code 盲区 = 骨架层 − 行为层})。埋点是<b>观测公式的人</b>,
 * 不是公式的一部分。让 {@code domain} 认识一张事件表,下一步就有人从事件表里读一个数
 * 去参与覆盖度 —— 而那个数是「他看没看过」,不是「他碰没碰过」。
 * 判据是一行 grep:{@code grep -rniE 'blindspot|north_?star'
 * server/kaodian-domain/src/main/java/} 恒为 0 命中。
 *
 * <h2>🔴 去重在服务端,不在客户端</h2>
 *
 * 客户端去重挡不住重装与多端,而这个数是<b>北极星</b> —— 它不能被客户端状态左右(契约 §5.7)。
 * 端上仍要有本地队列(防丢),但正确性由这里的 {@code (userId, localDate, surface)}
 * 唯一键兜(§6.3)。
 * <p>
 * 形态、纪律、失败方式全部照抄 {@code FileAssertionStore}:全量重写 + 原子 rename、
 * 读写逐字段列举、坏文件宁可启动不了也不当成 0 行、没有种子文件。理由不重复,见那个类;
 * 这里只写<b>不一样的那两处</b>。
 *
 * <h2>不一样之处一:{@link #record} 是<b>先到的那条赢</b>,不是「按键覆盖」</h2>
 *
 * {@code FileAssertionStore#put} 的幂等是「按主键覆盖」的自然结果,这里不行 ——
 * 同日同屏先来 {@code outcome=data}、后来 {@code outcome=empty},覆盖会让那一天变成
 * 「他打开时是空的」。而事实是<b>他那天第一次看见的是有数据的那一屏</b>。
 * 所以这里是「已经有了就<b>什么都不做</b>」:第二次上报 {@code 200},不报错,不计第二次,
 * 也<b>不改写已经落下的那一行</b>(§6.3「重复上报」「一次 data 一次 empty → 1 行,先到的那条」)。
 *
 * <h2>不一样之处二:这里没有「查全部」的口子</h2>
 *
 * 只有 {@link #countOn} 与 {@link #northStarUserCount} 两个按日的读法。
 * 不给 {@code findAll}:一个能把全部事件行取出来的方法,就是一份<b>按人、按天排好的行为轨迹</b>,
 * 而 §6.1 刚刚拒绝了行为画像。<b>要什么给什么,不要什么就别让它存在</b>。
 */
@Component
public class BlindspotEventStore {

    /**
     * 🔴 <b>北极星公式里的 surface 集合 —— 全服务端只有这一处。</b>
     *
     * <pre>
     * 主动查看盲区的人数(某日) =
     *   SELECT COUNT(DISTINCT user_id) FROM blindspot_opened_event
     *   WHERE local_date = ? AND surface IN (NORTH_STAR_SURFACES)
     * </pre>
     *
     * <p>它<b>不是查询参数、不下发、不进任何 {@code .properties} / {@code .yml}</b>。
     * 做成参数或配置,这个数就有了两个以上的定义,而「一个数只能有一个定义」正是
     * 北极星之所以是北极星的全部内容。改它是<b>一次代码改动 + 一条决策记录</b>,不是一次迁移。
     *
     * <h2>为什么默认只有 {@code S-BLIND},而不是把 {@code S-ASK} 也算上</h2>
     *
     * ⚠️ 这一点上两份 2026-09-03 的冻结口径说的不是同一件事,<b>技术侧不选边</b>(§6.4):
     * {@code 看盲区} §十三 / {@code U3.2} §2.5 / {@code U3.4} §2.4 写死「北极星只数总览屏」;
     * 而 {@code 接口契约} §12.4.2 / §12.13 写着从 {@code S-ASK} 点进 {@code S-NODE}
     * 「直接生产这个指标的分子」。
     * <p>
     * 落法是<b>把两种读法的数据都存进库里</b>(每一行都带 {@code surface},唯一键也带上它),
     * 只把<b>口径</b>收窄到这一处常量。取收窄默认值的理由:<b>收窄可以随时放开,
     * 放开之后再收窄会让历史数据的口径断成两段。</b>
     * <p>
     * 🔴 待产品拍板:要不要含 {@code S-ASK}。一句话可改,改的是这一个常量。
     */
    public static final Set<String> NORTH_STAR_SURFACES = Set.of("S-BLIND");

    /**
     * {@code surface} 的取值全集 —— <b>它比 {@link #NORTH_STAR_SURFACES} 宽,这是有意的</b>:
     * 库里存两种读法的数据,口径只认一种。
     */
    static final Set<String> SURFACES = Set.of("S-BLIND", "S-ASK");

    /**
     * {@code entry} 的取值全集。🔴 <b>没有 {@code restore}</b> —— 冷启动恢复到这一屏恒不上报,
     * 它不是这一次的主动选择,是上一次的残留(§6.2)。取值域里留着它,
     * 端迟早会「顺手也报一个」,而那些行会把北极星撑成一个「谁昨天开着这一屏」的数。
     */
    static final Set<String> ENTRIES = Set.of("home", "deeplink");

    /** {@code outcome} 的取值全集。空态<b>也打</b>,但必须可区分(§6.1)。 */
    static final Set<String> OUTCOMES = Set.of("data", "empty");

    private static final String FILE_NAME = "blindspot-opened-events.json";
    private static final String TMP_SUFFIX = ".tmp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    /** 单进程单用户,一把锁足够 —— 与 {@code FileAssertionStore} 同一句。 */
    private final Object lock = new Object();

    /** {@code null} 表示还没载入。载入推迟到第一次访问。 */
    private List<Row> rows;

    /**
     * @param dataDir 数据目录,默认 {@code ~/.kaodian}。与行为层同一个目录 ——
     *                「把这个目录拷走就是全部数据」这句话不能因为多了一张表就不成立
     */
    @Autowired
    public BlindspotEventStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public BlindspotEventStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    /** 数据文件的位置。 */
    public Path dataFile() {
        return file;
    }

    /**
     * 一行事件 —— {@code blindspot_opened_event} 那张表逐字段的样子(§6.5)。
     *
     * <p>🔴 <b>六个分量,一个都不多。</b> 没有设备指纹、没有 {@code identity_kind}、
     * 没有停留时长 —— 请求体上拦掉的那些属性,不能从存储这一侧偷偷长回来
     * (§十四 增量 3:去重键「不含任何设备指纹」)。
     *
     * @param userId    身份。🔴 事件<b>只在已登录时产生</b> —— 这不是靠自觉,
     *                  是 {@code ApiAuthFilter} 那条「默认拒绝」的结构后果
     * @param localDate 端上那次动作的本地自然日。窗口按<b>设备本地时区</b>切,
     *                  所以跨零点 22:00 一次、次日 00:30 一次 = 两行
     * @param surface   发生在哪一屏。<b>唯一键带上它</b>,于是同日 {@code S-BLIND} 与
     *                  {@code S-ASK} 各一次是两行 —— 两种读法的数据都在库里
     * @param entry     怎么到达的。<b>不进唯一键</b>:同一天先 {@code home} 后
     *                  {@code deeplink} 是同一次「今天他来看了」,不是两次
     * @param outcome   有没有数据。同样不进唯一键,理由见 {@link #record}
     * @param createdAt 落库时刻(服务端)。<b>不参与去重</b> —— 补传按原始
     *                  {@code localDate} 去重,不按补传时刻(§6.3)
     */
    public record Row(
            long userId,
            LocalDate localDate,
            String surface,
            String entry,
            String outcome,
            Instant createdAt
    ) {
    }

    /**
     * 记一次「主动查看」。<b>已经有了就什么都不做</b>,见类注释。
     *
     * <p>「查 + 决定 + 写」在同一把锁里 —— 分开做的话,同一天两台设备几乎同时上报会落两行,
     * 而那一天的人数就多了一个不存在的人。
     *
     * @return {@code true} = 这一次真的落了一行(那一天那一屏的第一次);
     *         {@code false} = 已经有了,静默不计第二次。
     *         🔴 <b>这个返回值只在服务端用,绝不能回给端</b> —— 见
     *         {@code BlindspotEventController#blindspotOpened} 那条「响应体是空对象」
     */
    public boolean record(long userId, LocalDate localDate,
                          String surface, String entry, String outcome, Instant createdAt) {
        if (userId <= 0) {
            // 0 在结构上不是合法 userId(B0-2 §3.3),它是 AgentController 那个哨兵。
            throw new IllegalArgumentException("事件必须有归属(userId > 0):" + userId);
        }
        Row row = new Row(userId, localDate, surface, entry, outcome, createdAt);
        synchronized (lock) {
            ensureLoaded();

            // 🔴 唯一键 (userId, localDate, surface) —— 冲突【静默吞掉】,不报错也不覆盖。
            //    覆盖的那一版会让「先 data 后 empty」的那一天变成「他打开时是空的」,
            //    而事实是他第一次看见的是有数据的那一屏。
            if (locate(userId, localDate, surface) != null) {
                return false;
            }

            List<Row> next = new ArrayList<>(rows.size() + 1);
            next.addAll(rows);
            next.add(row);

            // 先落盘再改内存:写失败时内存与磁盘仍然一致(与 FileAssertionStore 同一条纪律)
            writeAtomically(next);
            rows = next;
            return true;
        }
    }

    /** 某一天落了几行事件(所有 surface、所有人)。 */
    public int countOn(LocalDate localDate) {
        synchronized (lock) {
            ensureLoaded();
            int n = 0;
            for (Row r : rows) {
                if (r.localDate().equals(localDate)) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * 🔴 <b>北极星:某一天「主动查看盲区的人数」。</b>
     *
     * <p>公式在 {@link #NORTH_STAR_SURFACES} 上,这里只是把它执行一遍。
     * {@code COUNT(DISTINCT user_id)} —— <b>是人数,不是打开次数</b>:同一天同一屏打开 5 次是
     * 1 行 1 人,同一天 {@code S-BLIND} 与 {@code S-ASK} 各一次是 2 行但仍是 1 人。
     * <p>
     * 🔴 四条不许(§6.3 / {@code U3.2} §2.5 / {@code 看盲区} §十三):改成「打开次数」·
     * 改成「人均打开次数」· 加分母做成率(<b>北极星是人数,不是率</b>)·
     * 把 {@code outcome=empty} 混进来不做区分。
     */
    public int northStarUserCount(LocalDate localDate) {
        synchronized (lock) {
            ensureLoaded();
            return (int) rows.stream()
                    .filter(r -> r.localDate().equals(localDate))
                    .filter(r -> NORTH_STAR_SURFACES.contains(r.surface()))
                    .mapToLong(Row::userId)
                    .distinct()
                    .count();
        }
    }

    /**
     * 那一天那一屏落下的<b>那一行</b>;没有则 {@code null}。
     *
     * <p>🔴 只按<b>完整唯一键</b>查 —— 不给「列出某人某段时间的全部事件」的口子,理由见类注释。
     *
     * <p>它存在是为了让「先到的那条赢」这句话可测:{@link #countOn} 只能证明落了 1 行,
     * 证明不了<b>活下来的是先到的那一条</b>。而这两件事的实现完全不同 ——
     * 「按键覆盖」也会是 1 行,只是那一行的 {@code outcome} 已经被后来的 {@code empty} 改写了。
     */
    public Row find(long userId, LocalDate localDate, String surface) {
        synchronized (lock) {
            ensureLoaded();
            return locate(userId, localDate, surface);
        }
    }

    /** 调用方必须已经持有 {@link #lock} 且已 {@link #ensureLoaded}。 */
    private Row locate(long userId, LocalDate localDate, String surface) {
        for (Row r : rows) {
            if (r.userId() == userId && r.localDate().equals(localDate) && r.surface().equals(surface)) {
                return r;
            }
        }
        return null;
    }

    // —— 载入 ——

    /**
     * 推迟到第一次访问才载入 —— 与 {@code FileAssertionStore#ensureLoaded} 同一个理由:
     * 构造 bean 是一件不该有副作用的事,起一次上下文就往 {@code ~/.kaodian} 写文件,
     * 会让每一次跑测试都污染真实用户目录。
     *
     * <p>文件不存在时<b>不写盘</b>,只当成空表:这张表没有种子(没有人「默认已经看过」)。
     */
    private void ensureLoaded() {
        if (rows != null) {
            return;
        }
        rows = Files.exists(file) ? read() : List.of();
    }

    private List<Row> read() {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(MAPPER.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("盲区打开事件数据文件读取失败:" + file, e);
        }
    }

    /**
     * 解析一份事件 JSON。
     *
     * <h2>认不出来就吵着失败,绝不当成 0 行</h2>
     *
     * 与 {@code FileAssertionStore#parse} 完全同一条:{@code path("events")} 在缺键、
     * 键名写错时只会安静地给回一个 MissingNode,于是「解析成功、0 行事件」——
     * 而下一次 {@link #record} 是<b>全量重写</b>,那 0 行会盖掉磁盘上真实存在的事件。
     * <p>
     * 丢事件的后果比丢别的表更重:这张表是<b>北极星的唯一数据源</b>,
     * 而且它<b>补不回来</b> —— 端的本地队列早就把成功上报的那条删掉了。
     * 一次静默清零表现出来是「那几天没人看盲区」,而那正是阶段判据要读的那个数。
     */
    private static List<Row> parse(JsonNode root) {
        JsonNode array = root.path("events");
        if (!array.isArray()) {
            throw new IllegalStateException(
                    "盲区打开事件数据里没有 events 数组 —— 宁可在这里失败,也不能当成 0 行,"
                            + "否则下一次写入会把磁盘上真实存在的事件整个盖掉,而这张表是北极星的唯一数据源");
        }

        List<Row> result = new ArrayList<>();
        for (JsonNode n : array) {
            // 🔴 没有归属的行【丢弃】,不认领 —— 与 FileAssertionStore 同一句:
            //    userId 是唯一键的一半,认领它等于凭空给某个人多算一天。
            if (n.path("userId").asLong(0) <= 0) {
                continue;
            }
            try {
                result.add(toRow(n));
            } catch (IllegalArgumentException | DateTimeException e) {
                throw new IllegalStateException(
                        "盲区打开事件里有一行不合法:" + n.path("localDate").asString("?"), e);
            }
        }
        return result;
    }

    /** 一个 JSON 对象 → 一行事件。<b>只认这六个键,别的一概不看。</b> */
    private static Row toRow(JsonNode n) {
        return new Row(
                n.path("userId").asLong(0),
                LocalDate.parse(required(n, "localDate")),
                requireOneOf(n, "surface", SURFACES),
                requireOneOf(n, "entry", ENTRIES),
                requireOneOf(n, "outcome", OUTCOMES),
                Instant.parse(required(n, "createdAt")));
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asString("");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("事件行缺少必填字段:" + field);
        }
        return v;
    }

    /**
     * 闭集在<b>读盘时也判一次</b>。
     *
     * <p>写入侧已经判过了,这里再判是因为文件是可以被手改的:一行
     * {@code "surface":"S-EVERYTHING"} 不会让任何东西报错,只会安静地不进北极星 ——
     * 而「那天的数怎么少了一个人」是查不出来的。<b>宁可在启动时炸,也不要一个说谎的数。</b>
     */
    private static String requireOneOf(JsonNode n, String field, Set<String> closed) {
        String v = required(n, field);
        if (!closed.contains(v)) {
            throw new IllegalArgumentException(field + " 不在闭集 " + closed + " 里:" + v);
        }
        return v;
    }

    // —— 写入 ——

    /**
     * 一行事件 → 一个 JSON 对象。
     *
     * <p>🔴 逐字段写,<b>不是</b>把 {@link Row} 交给 Jackson 自动序列化 ——
     * 与 {@code FileAssertionStore#toNode} 同一个理由,而在这张表上它还多一层意思:
     * 自动序列化会跟着 record 的形状走,哪天有人给 {@link Row} 加了个 {@code deviceId},
     * 那个字段就会<b>不声不响地开始落盘</b>,而红线检查只盯着请求体那一侧。
     */
    private static ObjectNode toNode(Row r) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("userId", r.userId());
        o.put("localDate", r.localDate().toString());
        o.put("surface", r.surface());
        o.put("entry", r.entry());
        o.put("outcome", r.outcome());
        o.put("createdAt", r.createdAt().toString());
        return o;
    }

    /** 先写临时文件 → fsync → 原子 rename。与 {@code FileAssertionStore#writeAtomically} 同一份。 */
    private void writeAtomically(List<Row> all) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode comment = root.putArray("_comment");
            comment.add("「主动查看盲区」—— 每一行是「某个人在某个本地自然日打开过某一屏」。");
            comment.add("🔴 北极星的唯一数据源。唯一键 (userId, localDate, surface),重复上报静默吞掉。");
            comment.add("🔴 六个字段就是全部:没有设备指纹、没有停留时长、没有滚动深度。");
            comment.add("它数的是【人】不是【次】,而且不判断对错 —— 只有「有没有」。");
            ArrayNode arr = root.putArray("events");
            for (Row r : all) {
                arr.add(toNode(r));
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
            throw new IllegalStateException("盲区打开事件写入失败:" + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不该盖掉真正的错误
            }
        }
    }
}
