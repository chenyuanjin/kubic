package com.kaodian.server.collect;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * {@link AssertionStore} 的 MySQL 实现 —— {@code server/db/schema.sql} 里的 {@code user_assertion} 表。
 *
 * <h2>什么时候生效</h2>
 *
 * {@code kaodian.data.store=jdbc} 时装配这个,否则装配 {@link FileAssertionStore}(默认 {@code file})。
 * 两边都挂着条件,少挂一个就是两个 {@link AssertionStore} bean 一起进上下文,启动即失败。
 *
 * <h2>🔴 文件版靠 {@code synchronized},这里靠 {@code uk_user_assertion_node} 唯一索引</h2>
 *
 * 一个考点最多一条声明 —— 「我已掌握」没有「掌握了两次」这种说法,所以这张表不发 id,
 * {@code node_code} 就是业务唯一键。<b>去重靠的是索引的形状,不是写入时的一次检查。</b>
 * <p>
 * 这一点在库上比在文件上更要紧:这个按钮在界面上就是<b>连点会重复发请求</b>的那一类。
 * 「先查再写」有一个窗口,两个请求各自查到「没有」再各自写一行,概览里那个「已声明 N 个」
 * 就会变成 N+1 —— 而它是 docs/technical/INDEX.md §6.4 要求单列出来给用户看的那个数。
 * 所以 {@link #put} 先 INSERT,撞了再回查(见那个方法)。
 *
 * <h2>没有种子,也没有「更新」</h2>
 *
 * 没有人「默认已掌握」,所以这张表不播种(与 {@link FileAssertionStore} 一致)。
 * 一个考点要么被声明过要么没有,<b>没有第三种状态</b>:两个写方法都是幂等的,
 * 取消就是删行 —— 留一个 {@code cancelled_at} 等于把断言变成一条可查询的历史,
 * 而这个产品不记录用户的自我评价史。
 *
 * <h2>🔴 逐列取、逐列填,没有任何自动映射</h2>
 *
 * 与 {@code FileAssertionStore#toNode} 同一条纪律:<b>谁的表里能出现哪些列,由代码逐字列举</b>。
 */
@Component
@ConditionalOnProperty(name = "kaodian.data.store", havingValue = "jdbc")
public class JdbcAssertionStore implements AssertionStore {

    private static final String SQL_SELECT = "SELECT node_code, asserted_at FROM user_assertion";

    /** 契约:全部声明,<b>按写入顺序</b> —— 而写入顺序就是 {@code seq}。 */
    private static final String SQL_SELECT_ALL = SQL_SELECT + " ORDER BY seq ASC";

    private static final String SQL_FIND_BY_NODE = SQL_SELECT + " WHERE node_code = ?";

    private static final String SQL_INSERT =
            "INSERT INTO user_assertion (node_code, asserted_at) VALUES (?, ?)";

    private static final String SQL_DELETE = "DELETE FROM user_assertion WHERE node_code = ?";

    private static final String SQL_COUNT = "SELECT COUNT(*) FROM user_assertion";

    /** 一行 → 一条声明。两列逐个取,取不出来就抛 —— 坏数据要响亮失败,不许降级成空列表。 */
    private static final RowMapper<UserAssertion> ASSERTION_ROW = (rs, rowNum) -> new UserAssertion(
            rs.getString("node_code"),           // 只有考点树里的 code,没有任何自己起的名字(R-07)
            rs.getObject("asserted_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private final JdbcTemplate jdbc;

    public JdbcAssertionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UserAssertion> findAll() {
        return jdbc.query(SQL_SELECT_ALL, ASSERTION_ROW);
    }

    @Override
    public UserAssertion find(String nodeCode) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return null;                        // 与 FileAssertionStore#lookup 同一句
        }
        return jdbc.query(SQL_FIND_BY_NODE, ASSERTION_ROW, nodeCode).stream().findFirst().orElse(null);
    }

    /**
     * 声明掌握。契约见 {@link AssertionStore#put} —— <b>幂等,且已经存在时不刷新 {@code assertedAt}</b>。
     *
     * <p>先 INSERT,撞了 {@code uk_user_assertion_node} 再回查那条<b>原样返回</b>。
     * 「不刷新」这一条是这个方法唯一要额外守住的东西:{@code assertedAt} 唯一的用处是在界面上说
     * 「你在 X 月 X 日说过你会了」,而<b>连点两下按钮不该改写那句话</b>。真要重新计时,
     * 得先取消再声明 —— 那是两次明确的动作,不是一次误触。
     */
    @Override
    public UserAssertion put(UserAssertion assertion) {
        try {
            jdbc.update(SQL_INSERT, ps -> {
                ps.setString(1, assertion.nodeCode());
                // 走 UTC 且先截到毫秒,理由逐字见 JdbcTouchStore#utc。
                ps.setObject(2, LocalDateTime.ofInstant(
                        assertion.assertedAt().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
            });
            return assertion;
        } catch (DuplicateKeyException e) {
            UserAssertion existing = find(assertion.nodeCode());
            if (existing == null) {
                // 这张表只有 uk_user_assertion_node 一个唯一索引,回查不到说明撞的不是它 ——
                // 那是真错误,不该被当成一次重复声明吞掉。
                throw e;
            }
            return existing;                    // 🔴 原样返回,不刷新 assertedAt
        }
    }

    /**
     * 取消声明。契约见 {@link AssertionStore#remove} —— <b>幂等,没声明过也不报错。</b>
     *
     * <p>受影响行数 0 就是「本来就没有」,而用户想要的结果(「这个考点不带『我已掌握』」)
     * 已经成立了。这时候回一个失败,界面除了弹一句让人困惑的话之外什么都做不了。
     */
    @Override
    public boolean remove(String nodeCode) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return false;                       // 与 FileAssertionStore#remove 同一句
        }
        return jdbc.update(SQL_DELETE, nodeCode) > 0;
    }

    /** 声明的总数。概览里单列的那一格就是它(docs/technical/INDEX.md §6.4:<b>断言单列不并入</b>)。 */
    @Override
    public int count() {
        Integer n = jdbc.queryForObject(SQL_COUNT, Integer.class);
        return n == null ? 0 : n;
    }
}
