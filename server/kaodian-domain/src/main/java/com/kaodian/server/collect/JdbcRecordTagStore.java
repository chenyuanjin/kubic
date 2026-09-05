package com.kaodian.server.collect;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * {@link RecordTagStore} 的 MySQL 实现 —— {@code server/db/schema.sql} 里的 {@code record_tag} 表。
 *
 * <h2>什么时候生效</h2>
 *
 * {@code kaodian.data.store=jdbc} 时装配这个,否则装配 {@link FileRecordTagStore}(默认 {@code file})。
 * 两边都挂着条件,少挂一个就是两个 {@link RecordTagStore} bean 一起进上下文,启动即失败。
 *
 * <h2>🔴 文件版靠 {@code synchronized},这里靠一次事务里的 {@code SELECT ... FOR UPDATE}</h2>
 *
 * {@link FileRecordTagStore#put} 把「查 + 校验 + 写」放在一把进程内的锁里。换成库之后那把锁没了,
 * 而这里的校验是<b>三个字段写入后不可变</b>({@code origin} / {@code recordId} / {@code nodeCode},
 * 契约见 {@link RecordTagStore#put})—— 校验和写入之间要是能插进另一次写,这道拒绝就形同虚设。
 * 所以 {@link #put} 是 {@code @Transactional},开头先 {@code SELECT ... FOR UPDATE} 把那一行锁住,
 * 校验、UPDATE、提交都在同一个事务里。
 * <p>
 * ⚪ 已知缺口:同一个<b>新 id</b> 被两个事务同时 {@code put} 时,{@code FOR UPDATE} 锁的是间隙不是行,
 * 两边都可能读到「没有」再各自 INSERT,后到的那个会撞 {@code uk_record_tag_id} 抛出去。
 * 失败方向是<b>响亮报错</b>而不是静默改写 origin,所以没为它加重试;真出现了再说。
 *
 * <h2>🔴 UPDATE 只碰可变的三列,不碰 {@code seq}</h2>
 *
 * {@code findByRecord} 按 {@code seq} 排,而 {@code seq} 就是写入序。
 * 一次确认(只写 {@code confirmed_at})如果把行挪到序尾,用户看到的标签顺序会因为「点了一下确认」
 * 而变,且不会有任何一条断言红。见 {@link #SQL_UPDATE_MUTABLE}。
 *
 * <h2>🔴 主标签不入库,派生规则也不进 SQL</h2>
 *
 * 每条记录采集那一刻就有的那条主标签<b>不占一行</b>,{@link #findAll} 只返回库里真实存在的行。
 * {@link RecordTag#effectiveTagsOf} 那套「主标签 + 库里其余标签、{@code nodeCode} 永远取自
 * {@link Touch#nodeCode()}」的派生规则留在 Java 里 —— 搬进 SQL 就有了两份,
 * 而它们算出来的分子迟早不一样(理由逐字见 {@link RecordTagStore} 类注释)。
 *
 * <h2>🔴 逐列取、逐列填,没有任何自动映射</h2>
 *
 * 与 {@code FileRecordTagStore#toNode} 同一条纪律:<b>谁的表里能出现哪些列,由代码逐字列举</b>。
 */
@Component
@ConditionalOnProperty(name = "kaodian.data.store", havingValue = "jdbc")
public class JdbcRecordTagStore implements RecordTagStore {

    private static final String SQL_SELECT = """
            SELECT id, record_id, node_code, confidence, origin, confirmed_at, discarded
            FROM record_tag""";

    private static final String SQL_SELECT_ALL = SQL_SELECT + " ORDER BY seq ASC";

    /** 契约:某条记录名下的标签行,<b>按写入顺序</b> —— 而写入顺序就是 {@code seq}。 */
    private static final String SQL_FIND_BY_RECORD = SQL_SELECT + " WHERE record_id = ? ORDER BY seq ASC";

    private static final String SQL_FIND_BY_ID = SQL_SELECT + " WHERE id = ?";

    /** {@link #put} 开头用它把那一行锁到事务结束,校验与写入之间就插不进第二次写了。 */
    private static final String SQL_LOCK_BY_ID = SQL_FIND_BY_ID + " FOR UPDATE";

    private static final String SQL_INSERT = """
            INSERT INTO record_tag (id, record_id, node_code, confidence, origin, confirmed_at, discarded)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

    /**
     * 🔴 SET 里只有可变的三列。
     *
     * <p>{@code origin} / {@code record_id} / {@code node_code} 不在这里,不是「记得别改」——
     * 是这条 SQL 里<b>没有能改它们的位置</b>,与 {@code RecordTag#confirm} 的签名里没有 origin
     * 是同一道锁的第四层。
     * <p>
     * {@code seq} 也不在这里:它是写入序,一次确认不该把这行挪到序尾。
     */
    private static final String SQL_UPDATE_MUTABLE =
            "UPDATE record_tag SET confidence = ?, confirmed_at = ?, discarded = ? WHERE id = ?";

    private static final String SQL_DELETE_BY_RECORD = "DELETE FROM record_tag WHERE record_id = ?";

    private static final String SQL_COUNT = "SELECT COUNT(*) FROM record_tag";

    /** 一行 → 一条标签。七列逐个取,取不出来就抛 —— 坏数据要响亮失败,不许降级成空列表。 */
    private static final RowMapper<RecordTag> TAG_ROW = (rs, rowNum) -> new RecordTag(
            rs.getString("id"),
            rs.getString("record_id"),
            rs.getString("node_code"),           // 只有考点树里的 code,没有任何标签文字(R-07)
            rs.getDouble("confidence"),
            // 认不出来就抛 —— 一个存坏了的 origin 不该被悄悄当成 manual(见 TagOrigin#ofWireName)
            TagOrigin.ofWireName(rs.getString("origin")),
            toInstant(rs.getObject("confirmed_at", LocalDateTime.class)),
            rs.getBoolean("discarded"));

    private final JdbcTemplate jdbc;

    public JdbcRecordTagStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 库里存着的全部标签行。<b>不含推出来的主标签</b>,见类注释。 */
    @Override
    public List<RecordTag> findAll() {
        return jdbc.query(SQL_SELECT_ALL, TAG_ROW);
    }

    @Override
    public List<RecordTag> findByRecord(String recordId) {
        return jdbc.query(SQL_FIND_BY_RECORD, TAG_ROW, recordId);
    }

    @Override
    public RecordTag find(String tagId) {
        if (tagId == null || tagId.isBlank()) {
            return null;                        // 与 FileRecordTagStore#lookup 同一句
        }
        return jdbc.query(SQL_FIND_BY_ID, TAG_ROW, tagId).stream().findFirst().orElse(null);
    }

    /**
     * 新增或更新一行。契约见 {@link RecordTagStore#put} ——
     * <b>「锁 + 校验 + 写」在同一个事务里</b>,对应文件版的「在同一把锁里」。
     *
     * <p>🔴 三道拒绝的顺序与消息与 {@link FileRecordTagStore#put} 逐字相同。它们放在<b>存储层</b>
     * 而不是只放在服务层,是因为服务层将来会有第二个调用者(补标、批量确认),而红线不能靠每个调用者自觉。
     */
    @Override
    @Transactional
    public RecordTag put(RecordTag tag) {
        RecordTag existing = jdbc.query(SQL_LOCK_BY_ID, TAG_ROW, tag.id()).stream().findFirst().orElse(null);
        if (existing == null) {
            jdbc.update(SQL_INSERT, ps -> bind(ps, tag));
            return tag;
        }

        // 🔴 origin 是来源不是状态(docs/technical/INDEX.md §5.2)。这一句是它的第三道锁 ——
        //    前两道在 RecordTag 上(record 没有 setter、confirm/discard 的签名里没有 origin 的位置),
        //    但那两道只挡得住「顺着现有 API 走」的人。这一道挡的是自己 new 一个再 put 进来的写法。
        if (existing.origin() != tag.origin()) {
            throw new IllegalArgumentException(
                    "标签的 origin 写入后不可变:" + existing.origin().wireName()
                            + " → " + tag.origin().wireName()
                            + " —— 它记的是这条标签从哪来,不是它现在什么状态。"
                            + "用户确认只写 confirmed_at(docs/technical/INDEX.md §5.2)");
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

        jdbc.update(SQL_UPDATE_MUTABLE, ps -> {
            ps.setDouble(1, tag.confidence());
            setInstant(ps, 2, tag.confirmedAt());
            ps.setBoolean(3, tag.discarded());
            ps.setString(4, tag.id());
        });
        return tag;
    }

    /** 契约见 {@link RecordTagStore#deleteByRecord} —— {@code DELETE /records/{id}} 的级联删标签。 */
    @Override
    public int deleteByRecord(String recordId) {
        return jdbc.update(SQL_DELETE_BY_RECORD, recordId);
    }

    @Override
    public int count() {
        Integer n = jdbc.queryForObject(SQL_COUNT, Integer.class);
        return n == null ? 0 : n;
    }

    // —— 写入 ——

    /**
     * 一条标签 → 七个占位符。
     *
     * <p>🔴 逐列填,<b>不是</b>把 {@link RecordTag} 交给任何自动映射 ——
     * 与 {@code FileRecordTagStore#toNode} 同一个理由:自动映射跟着 record 的形状走,
     * 哪天有人给它加了个字段,那个字段就会不声不响地长成一列。
     */
    private static void bind(PreparedStatement ps, RecordTag t) throws SQLException {
        ps.setString(1, t.id());
        ps.setString(2, t.recordId());
        ps.setString(3, t.nodeCode());              // 只有考点树里的 code,没有任何标签文字(R-07)
        ps.setDouble(4, t.confidence());
        ps.setString(5, t.origin().wireName());     // 契约里是小写的 auto / manual
        setInstant(ps, 6, t.confirmedAt());
        ps.setBoolean(7, t.discarded());
    }

    /** {@code confirmed_at} 可以为 NULL —— NULL 就是「还没人确认」,不是一个哨兵时刻。 */
    private static void setInstant(PreparedStatement ps, int index, Instant at) throws SQLException {
        if (at == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            // 走 UTC 且先截到毫秒,理由逐字见 JdbcTouchStore#utc。
            ps.setObject(index, LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
        }
    }

    /** 读回来的那一侧:NULL 仍然是 {@code null},不要变成 epoch。 */
    private static Instant toInstant(LocalDateTime at) {
        return at == null ? null : at.toInstant(ZoneOffset.UTC);
    }
}
