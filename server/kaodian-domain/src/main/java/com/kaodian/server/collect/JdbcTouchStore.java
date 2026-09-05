package com.kaodian.server.collect;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * {@link TouchStore} 的 MySQL 实现 —— {@code server/db/schema.sql} 里的 {@code touch} 表。
 *
 * <h2>什么时候生效</h2>
 *
 * {@code kaodian.data.store=jdbc} 时装配这个,否则装配 {@link FileTouchStore}(默认 {@code file})。
 * 两边都挂着条件,少挂一个就是两个 {@link TouchStore} bean 一起进上下文,启动即失败。
 * <b>这个类不建表</b>:表结构的答案在 {@code schema.sql} 里,让应用自己 {@code CREATE TABLE}
 * 等于把它挪进运行时,而运行时的答案没人读得到。
 *
 * <h2>🔴 文件版靠 {@code synchronized},这里靠唯一索引与事务</h2>
 *
 * {@link FileTouchStore} 的每个方法都在一把进程内的锁里,前提是「整个进程一份 touches.json」——
 * 换成库之后这个前提就没了(第二个实例、离线队列的两次补传同时到达)。所以这里没有锁:
 * <table border="1">
 *   <caption>每条语义落在哪</caption>
 *   <tr><th>语义</th><th>落点</th></tr>
 *   <tr><td>{@code append} 幂等</td>
 *       <td><b>{@code uk_touch_client_token} 唯一索引</b> —— 先 INSERT,撞了再回查。
 *           <b>不是「先查再写」</b>:那有一个窗口,两次补传各自查到「没有」再各自写一条,
 *           用户看到记录变双份,而那正是覆盖度分子里的数</td></tr>
 *   <tr><td>{@code findAll} 顺序</td>
 *       <td>{@code ORDER BY occurred_at ASC, seq ASC}。文件版靠 TimSort 的稳定性保住同毫秒的插入序,
 *           SQL 里没有这个天然载体,所以 {@code seq} 是显式的 tie-break</td></tr>
 *   <tr><td>播种一次</td>
 *       <td>表为空才播,且每一条都容忍 {@code uk_touch_id} 冲突 —— 两个实例同时起来也只会有一份种子</td></tr>
 * </table>
 *
 * <h2>🔴 逐列取、逐列填,没有任何自动映射</h2>
 *
 * {@link #TOUCH_ROW} 与 {@link #bind} 把八列一个一个写出来,和文件版的 {@code toNode} /
 * {@link TouchSeed#toTouch} 是同一条纪律:<b>谁的表里能出现哪些列,由代码逐字列举</b>。
 * 换成 JPA 或任何反射映射,这条纪律会被悄悄取消 —— 哪天有人给 {@link Touch} 加了个字段,
 * 它就会不声不响地长出一列(决策记录 §2.2 / docs/technical/INDEX.md §5.1)。
 */
@Component
@ConditionalOnProperty(name = "kaodian.data.store", havingValue = "jdbc")
public class JdbcTouchStore implements TouchStore {

    private static final String SQL_SELECT = """
            SELECT id, node_code, source_name, kind, occurred_at,
                   drill_practiced, drill_correct, client_token
            FROM touch""";

    /** 🔴 seq 是 tie-break,不是装饰:同一毫秒落的两条,单靠 occurred_at 给不出稳定次序。 */
    private static final String SQL_SELECT_ALL = SQL_SELECT + " ORDER BY occurred_at ASC, seq ASC";

    private static final String SQL_FIND_BY_NODE =
            SQL_SELECT + " WHERE node_code = ? ORDER BY occurred_at ASC, seq ASC";

    private static final String SQL_FIND_BY_TOKEN = SQL_SELECT + " WHERE client_token = ?";

    private static final String SQL_FIND_BY_ID = SQL_SELECT + " WHERE id = ?";

    private static final String SQL_INSERT = """
            INSERT INTO touch (id, node_code, source_name, kind, occurred_at,
                               drill_practiced, drill_correct, client_token)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SQL_DELETE = "DELETE FROM touch WHERE id = ?";

    /**
     * 🔴 一条 UPDATE,<b>不是 delete + insert</b>。
     *
     * <p>删旧建新会重置 {@code occurred_at},而它是「多久前」的唯一依据 ——
     * 一批记录集体变年轻,覆盖率不动、五态却会不报错地整体漂移(契约见 {@link TouchStore#reassign})。
     * 顺带 {@code seq} 与 {@code client_token} 也原样留着:丢了后者,那条记录就重新变得可以被补传一次。
     */
    private static final String SQL_REASSIGN = "UPDATE touch SET node_code = ? WHERE node_code = ?";

    private static final String SQL_COUNT = "SELECT COUNT(*) FROM touch";

    /** 一行 → 一条记录。八列逐个取,取不出来就抛 —— 坏数据要响亮失败,不许降级成空列表。 */
    private static final RowMapper<Touch> TOUCH_ROW = (rs, rowNum) -> {
        int practiced = rs.getInt("drill_practiced");
        // wasNull() 必须紧跟着 getInt:仅接触的记录两列都是 NULL,而 getInt 把 NULL 读成 0。
        // 「练了 0 道」和「这条记录里根本没有做题这回事」在 Touch 上是两种东西(见 TouchSeed#toTouch)。
        Touch.Drill drill = rs.wasNull() ? null : new Touch.Drill(practiced, rs.getInt("drill_correct"));
        return new Touch(
                rs.getString("id"),
                rs.getString("node_code"),
                rs.getString("source_name"),        // 只有来源【名字】,没有来源的内容
                TouchKind.valueOf(rs.getString("kind")),
                rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                drill,
                rs.getString("client_token"));
    };

    private final JdbcTemplate jdbc;

    /**
     * 播种基准时刻的来源,与差集运算共用同一个 bean —— 理由逐字见 {@link TouchSeed#load}。
     */
    private final Clock clock;

    /**
     * 「这个进程查过表空不空了吗」。
     *
     * <p>它<b>不是</b>幂等的依据 —— 跨进程的幂等落在 {@code uk_touch_id} 上(见 {@link #ensureSeeded})。
     * 它只是省掉每次调用一次 {@code COUNT(*)}。
     */
    private volatile boolean seeded;

    public JdbcTouchStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<Touch> findAll() {
        ensureSeeded();
        return jdbc.query(SQL_SELECT_ALL, TOUCH_ROW);
    }

    @Override
    public List<Touch> findByNode(String nodeCode) {
        ensureSeeded();
        return jdbc.query(SQL_FIND_BY_NODE, TOUCH_ROW, nodeCode);
    }

    @Override
    public Touch findByClientToken(String clientToken) {
        ensureSeeded();
        if (clientToken == null || clientToken.isBlank()) {
            // 「没有去重键」不是一个能互相匹配的值(契约见 TouchStore#findByClientToken)。
            // 不发这条查询:SQL 里 client_token = NULL 永远不成立,查了也只是白跑一趟,
            // 而写成 IS NULL 会把一堆「都没填」的记录互相当成对方的重传。
            return null;
        }
        return jdbc.query(SQL_FIND_BY_TOKEN, TOUCH_ROW, clientToken).stream().findFirst().orElse(null);
    }

    /**
     * 追加一条记录。契约见 {@link TouchStore#append} —— <b>幂等落在唯一索引上,不落在一次「先查再写」上。</b>
     *
     * <p>先 INSERT,撞了 {@code uk_touch_client_token} 再回查那条已存在的<b>原样返回</b>:
     * id 与 {@code occurredAt} 都是第一次的。「不覆盖」这一条要紧 ——
     * 补传的那份带的是【补传时刻】的服务端时间戳,拿它盖掉第一次落地的 {@code occurredAt},
     * 等于让一条记录凭空变年轻,而「多久前」是五态里唯一的时间依据。
     */
    @Override
    public Touch append(Touch touch) {
        ensureSeeded();
        try {
            jdbc.update(SQL_INSERT, ps -> bind(ps, touch));
            return touch;
        } catch (DuplicateKeyException e) {
            // clientToken 为 null 时不参与判重(MySQL 的唯一索引允许多个 NULL),
            // 所以这里回查不到 —— 那就只可能是 uk_touch_id 撞了,即同一个 id 被签发了两次。
            // 🔴 那是真错误,不是一次补传。吞掉它等于让一条用户记下的记录静默消失。
            Touch existing = findByClientToken(touch.clientToken());
            if (existing == null) {
                throw e;
            }
            return existing;
        }
    }

    /** 契约见 {@link TouchStore#delete} —— 删一条不存在的记录返回 {@code null},不抛异常。 */
    @Override
    public Touch delete(String id) {
        ensureSeeded();
        Touch victim = jdbc.query(SQL_FIND_BY_ID, TOUCH_ROW, id).stream().findFirst().orElse(null);
        if (victim == null) {
            return null;
        }
        // 这两句之间不加事务:DELETE 的受影响行数就是「这次调用到底删没删掉」。
        // 并发下别人抢先删掉了同一条,这里拿到 0 行 → 返回 null,而那与「这个 id 不存在」
        // 正是同一个答复,契约要的就是它。
        return jdbc.update(SQL_DELETE, id) == 0 ? null : victim;
    }

    @Override
    public int count() {
        ensureSeeded();
        Integer n = jdbc.queryForObject(SQL_COUNT, Integer.class);
        return n == null ? 0 : n;
    }

    /** 整体改挂。契约见 {@link TouchStore#reassign} —— <b>搬家,不扔东西</b>,理由见 {@link #SQL_REASSIGN}。 */
    @Override
    public int reassign(String fromNodeCode, String toNodeCode) {
        ensureSeeded();
        return jdbc.update(SQL_REASSIGN, toNodeCode, fromNodeCode);
    }

    // —— 播种 ——

    /**
     * 表为空时播一次种。
     *
     * <h2>为什么 JDBC 这侧也要播</h2>
     *
     * 不播的话,同一份代码换个存储后端读出的覆盖度就不是同一个数,
     * 而「吐出去的还是不是同一个数」正是这一轮的验收断言。种子从
     * {@link TouchSeed} 来,与 {@link FileTouchStore} <b>同一份</b>。
     *
     * <h2>为什么不是 {@code @PostConstruct}</h2>
     *
     * 与 {@code FileTouchStore#ensureLoaded} 同一个理由:构造 bean 是一件不该有副作用的事。
     * 起一次 Spring 上下文就往库里写八行,会让每一次跑测试都污染目标库。
     *
     * <h2>幂等落在哪</h2>
     *
     * {@code synchronized} 只保证<b>本进程</b>不重复跑这段;真正挡住重复播种的是
     * {@code uk_touch_id} —— 种子的 id 是写死的({@code seed-growth-rate} 之类),
     * 第二个实例插同一批必然撞唯一索引,而那正是「已经播过了」。
     * 种子里没有 {@code clientToken},所以撞的不可能是另一个索引。
     */
    private void ensureSeeded() {
        if (seeded) {
            return;
        }
        synchronized (this) {
            if (seeded) {
                return;
            }
            Integer existing = jdbc.queryForObject(SQL_COUNT, Integer.class);
            if (existing != null && existing == 0) {
                for (Touch t : TouchSeed.load(clock)) {
                    try {
                        jdbc.update(SQL_INSERT, ps -> bind(ps, t));
                    } catch (DuplicateKeyException alreadySeeded) {
                        // 另一个实例抢在前面播了同一批。见上面「幂等落在哪」。
                    }
                }
            }
            seeded = true;
        }
    }

    // —— 写入 ——

    /**
     * 一条记录 → 八个占位符。
     *
     * <p>🔴 逐列填,<b>不是</b>把 {@link Touch} 交给任何自动映射 —— 与 {@code FileTouchStore#toNode}
     * 同一个理由:自动映射跟着 record 的形状走,哪天有人给 {@link Touch} 加了个字段,
     * 它就会不声不响地流进库里。逐列填让「表里能出现哪些列」是这段代码显式列出来的。
     */
    private static void bind(PreparedStatement ps, Touch t) throws SQLException {
        ps.setString(1, t.id());
        ps.setString(2, t.nodeCode());
        ps.setString(3, t.sourceName());        // 只有来源【名字】,没有来源的内容
        ps.setString(4, t.kind().name());
        ps.setObject(5, utc(t.occurredAt()));
        if (t.drill() == null) {
            // 仅接触。两列同生同灭 —— 写成 0 的话「听了一节课」会变成「练了 0 道题」。
            ps.setNull(6, Types.INTEGER);
            ps.setNull(7, Types.INTEGER);
        } else {
            ps.setInt(6, t.drill().practiced());
            ps.setInt(7, t.drill().correct());  // 用户自己填的数,不是判出来的
        }
        if (t.clientToken() == null) {
            // 🔴 落 NULL,不落空串。MySQL 的唯一索引允许多个 NULL,这恰好等于
            // Touch 构造器把空白 client_token 归一成 null 的语义:没填的记录不互相判重。
            ps.setNull(8, Types.VARCHAR);
        } else {
            // 🔴 去重键必须落库。只留在内存里的话,进程一重启,离线队列里那批记录就能再补传一次(R-32)。
            ps.setString(8, t.clientToken());
        }
    }

    /**
     * {@code Instant} → {@code occurred_at DATETIME(3)} 要落的那个值。
     *
     * <p>两件事各有理由:
     * <ul>
     *   <li><b>走 UTC,不走系统默认时区</b> —— {@code DATETIME} 不带时区,读写各按各的机器时区换算的话,
     *       换一台机器同一条记录就换一个时刻。{@code DomainBeans#clock} 给的本来就是
     *       {@code Clock.systemUTC()},这里只是把「库里存的是 UTC 墙上时间」写死</li>
     *   <li><b>先截到毫秒</b> —— MySQL 对超出列精度的小数秒是<b>四舍五入</b>而不是截断,
     *       于是 {@code 23:59:59.9996} 会进位成第二天的 {@code 00:00:00.000}。
     *       时间线按 {@code kaodian.api.timeline.zone} 切「一天」,那一下就是一条记录换了一天</li>
     * </ul>
     */
    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
