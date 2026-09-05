package com.kaodian.server.syllabus;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * {@link SyllabusStore} 的 MySQL 实现 —— <b>{@code kaodian.data.store=jdbc} 时才装配</b>,
 * 默认那一档仍然是 {@link FileSyllabusStore}。
 *
 * <p>切过来的触发条件写在 {@code application.properties} 的「数据存储」一节:公网可达的服务器、
 * 或者第二个实例。第二个实例正是文件实现无声失效的那一刻 —— {@code synchronized} 守的是
 * <b>一个进程内</b>的那把锁,两个进程各有一把,而它们写的是同一个 {@code ~/.kaodian/syllabus.json}。
 * 这个类把那三条不变式(名字唯一、删除守则、树序)从「进程内的锁」搬到<b>库自己的约束</b>上。
 *
 * <h2>🔴 逐列取、逐列填,没有一处反射映射</h2>
 *
 * 与 {@link FileSyllabusStore#toNode} 是同一条纪律,只是换了个介质:那边是逐字段写 JSON 键,
 * 这边是逐列写 SQL。<b>不引 JPA、不做自动映射</b>,于是「骨架层的表里能出现哪些列」
 * 是下面这些常量显式列出来的 —— 哪天 {@link Syllabus.Node} 多了个字段,
 * 它不会因为「实体多了个属性」就自己流进库里,加列必须先过这里
 * (决策记录 §2.2 不碰内容 / docs/data/INDEX.md §5.2)。
 *
 * <h2>🔴 名字唯一性为什么要 {@code name_key} 这一列</h2>
 *
 * 唯一性的口径是 {@link SyllabusNames#nameKey}:strip → NFKC → 剥不可见码点 → 折叠内部空白 →
 * {@code toLowerCase(ROOT)}。MySQL 的 {@code utf8mb4_0900_ai_ci} <b>做不到其中的任何一步</b> ——
 * 它不做 NFKC(全角「ＧＤＰ」与半角「GDP」是两个值)、不剥零宽(「增长量计算」后面缀一个 U+200B
 * 就是一个新值)。把唯一索引直接建在 {@code name} 上,等于把不变式换成一条更松的规则,
 * 而松掉的那部分正是整条约束要防的东西:两个肉眼分不出的考点,记录被劈到两个 code 上。
 * <p>
 * 所以库里存两份:{@code name} 是用户输入的原样(只 {@code strip()},规范化是有损的,
 * 替用户改名字不是我们的事 —— 见 {@link SyllabusNames} 类注释),{@code name_key} 是比较用的形状,
 * 唯一索引建在后者上。<b>两份都由这段代码写</b>,不由触发器、不由生成列 ——
 * 口径只有 {@link SyllabusNames#nameKey} 这一个,写入(这里)与载入
 * ({@link SyllabusLoader})共用它,不变式才在一处成立、到处成立。
 *
 * <h2>🔴 查重靠唯一索引冲突,不靠「先查再写」</h2>
 *
 * {@link FileSyllabusStore#addNode} 那句注释在这里逐字有效:<b>分开就存在一个能挤进两个同名的窗口</b>。
 * 文件版靠一把进程内的锁把「查」和「写」合成一步;换到库上,能做到同样效果的是唯一索引本身 ——
 * 它是<b>写的那一刻</b>判定的,中间没有窗口。
 * <p>
 * 于是这里的顺序是「先写,冲突了再回头查是谁占着」:回查只为了拼那句
 * {@link SyllabusEditException#nodeNameTakenByArchived} 要说的话(占名字的是谁、在哪个题型下、
 * 归没归档),<b>判定早在索引那一步就做完了</b>。回查落空(冲突不在 {@code name_key} 上)就原样抛回
 * 原始异常,不把一次别的冲突改写成一句「名字被占了」。
 *
 * <h2>🔴 删除守则的原子性落在哪</h2>
 *
 * {@link #deleteNode} / {@link #deleteGroup} 的「数记录数」与「真的删」必须是<b>一步</b>。
 * 文件版把它们放进同一个 {@code synchronized},理由是消灭「先删树、后想起还有记录」这条调用顺序
 * ({@link NodeRecordLedger} 类注释)。这里的等价物是两样东西叠在一起:
 * <ul>
 *   <li><b>{@code @Transactional(REPEATABLE_READ)}</b> —— 计数与删除同处一个事务、同一个快照,
 *       并且 {@link NodeRecordLedger} 的实现走的是同一个 {@code DataSource},
 *       它的那次计数<b>就在这个事务里</b>,不是另开一条连接去问的;</li>
 *   <li><b>对那一行 {@code SELECT … FOR UPDATE}</b> —— 排他行锁。两个实例同时删同一个考点时,
 *       后到的那个会等在锁上,等到的是「已经没有这一行了」,而不是各自数出 0 条各删一次。
 *       {@link #deleteGroup} 锁的是题型那一行,而 {@code syllabus_node.group_code} 的外键
 *       会让并发的 {@code addNode} 去拿同一行的共享锁 —— 于是「一边删空题型、一边往里加考点」
 *       这条顺序也被同一把锁挡住了。</li>
 * </ul>
 * 守则守的是覆盖率 —— 这个产品唯一的那个数,所以这里同样<b>没有 force 参数</b>。
 *
 * <h2>🔴 树序为什么必须显式持久化</h2>
 *
 * 文件版的顺序就是 JSON 数组的顺序,换到 SQL 之后<b>「顺序」没有天然载体</b>:
 * 表是集合,不带序;不写 {@code ORDER BY} 的两次查询可以给出两种排列。
 * 而树序是有产品含义的 —— 盲区排序在 {@code blindScore} 并列时按树序决定先后
 * ({@code CoverageService.blindSpots}),「先补这几个」的前几名就是用户唯一会看的东西
 * ({@link SyllabusStore#reorderGroups})。所以每张表都带一列 {@code sort_order},
 * 每次读都显式按它排。
 * <p>
 * 排序再补一个 {@code code} 做 tie-break:{@code sort_order} 上没有唯一约束,
 * 万一出现两个相同的值,「同一份数据两次读出不同的顺序」是无声的
 * (schema.sql 开头那段「为什么每张表都有 seq」说的是同一件事,骨架层这两张表没有 seq,
 * 用主键顶上)。
 *
 * <h2>🔴 读不出来就吵着失败,绝不当成一棵空树</h2>
 *
 * {@link SyllabusLoader} 那条教训在这里一个字不改:空树意味着覆盖度的<b>分母为 0</b>,
 * 而那是个会静默传播的假事实 —— 所有记录一起变成孤儿,百分比莫名其妙地动,没有任何一处报错。
 * 所以下面任何一次查询异常都<b>原样往上抛</b>,一处 catch 成空集合的都没有。
 */
@Component
@ConditionalOnProperty(name = "kaodian.data.store", havingValue = "jdbc")
@Transactional
public class JdbcSyllabusStore implements SyllabusStore {

    /** 服务端生成的 code 前缀。种子里的 code 是手写的英文短语,这两个前缀让来源一眼可分。 */
    private static final String NODE_CODE_PREFIX = "n-";
    private static final String GROUP_CODE_PREFIX = "g-";

    // ———————————————————————— 读 ————————————————————————

    private static final String SQL_SELECT_SUBJECT =
            "SELECT code, region, exam, module, recent5y_window FROM syllabus_subject WHERE id = 1";

    private static final String SQL_SELECT_GROUPS =
            "SELECT code, name FROM syllabus_group ORDER BY sort_order, code";

    private static final String SQL_SELECT_NODES =
            "SELECT code, group_code, name, recent5y_count, archived FROM syllabus_node "
                    + "ORDER BY sort_order, code";

    private static final String SQL_SELECT_GROUP_NODES =
            "SELECT code, name, recent5y_count, archived FROM syllabus_node "
                    + "WHERE group_code = ? ORDER BY sort_order, code";

    private static final String SQL_GROUP_CODES =
            "SELECT code FROM syllabus_group ORDER BY sort_order, code";

    /** 某题型下、某个归档状态的考点 code,按树序。{@link #reorderNodes} 要的两段就是它跑两遍。 */
    private static final String SQL_GROUP_NODE_CODES =
            "SELECT code FROM syllabus_node WHERE group_code = ? AND archived = ? "
                    + "ORDER BY sort_order, code";

    private static final String SQL_NODE_EXISTS = "SELECT code FROM syllabus_node WHERE code = ?";

    private static final String SQL_GROUP_EXISTS = "SELECT code FROM syllabus_group WHERE code = ?";

    private static final String SQL_NODE_GROUP =
            "SELECT group_code FROM syllabus_node WHERE code = ?";

    private static final String SQL_NODES_IN_GROUP =
            "SELECT COUNT(*) FROM syllabus_node WHERE group_code = ?";

    // ———————————————————————— 行锁 ————————————————————————

    /** 🔴 删除守则、归档、改名的落点:排他行锁,读到的就是这一刻的真值,并且没人能在中间改它。 */
    private static final String SQL_LOCK_NODE =
            "SELECT code, name, recent5y_count, archived FROM syllabus_node WHERE code = ? FOR UPDATE";

    private static final String SQL_LOCK_GROUP =
            "SELECT code FROM syllabus_group WHERE code = ? FOR UPDATE";

    /** {@link #moveRecords} 的第 ④ 关:目标归没归档必须锁着读,否则判完到搬完之间它可能被归档。 */
    private static final String SQL_LOCK_NODE_ARCHIVED =
            "SELECT archived FROM syllabus_node WHERE code = ? FOR UPDATE";

    // ———————————————————————— 写 ————————————————————————

    private static final String SQL_INSERT_SUBJECT =
            "INSERT INTO syllabus_subject (id, code, region, exam, module, recent5y_window) "
                    + "VALUES (1, ?, ?, ?, ?, ?)";

    private static final String SQL_INSERT_GROUP =
            "INSERT INTO syllabus_group (code, name, name_key, sort_order) VALUES (?, ?, ?, ?)";

    private static final String SQL_INSERT_NODE =
            "INSERT INTO syllabus_node "
                    + "(code, group_code, name, name_key, recent5y_count, archived, sort_order) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    /** 🔴 只改 name / name_key,{@code code} 一个字符都不动 —— 记录挂 code,改名不断历史。 */
    private static final String SQL_RENAME_NODE =
            "UPDATE syllabus_node SET name = ?, name_key = ? WHERE code = ?";

    private static final String SQL_RENAME_GROUP =
            "UPDATE syllabus_group SET name = ?, name_key = ? WHERE code = ?";

    private static final String SQL_SET_COUNT =
            "UPDATE syllabus_node SET recent5y_count = ? WHERE code = ?";

    private static final String SQL_SET_ARCHIVED =
            "UPDATE syllabus_node SET archived = ? WHERE code = ?";

    /** 换题型 = 改 group_code + 排到新题型的末尾。code 原样留着,记录一条都不受影响。 */
    private static final String SQL_MOVE_NODE =
            "UPDATE syllabus_node SET group_code = ?, sort_order = ? WHERE code = ?";

    private static final String SQL_NODE_ORDER =
            "UPDATE syllabus_node SET sort_order = ? WHERE code = ?";

    private static final String SQL_GROUP_ORDER =
            "UPDATE syllabus_group SET sort_order = ? WHERE code = ?";

    private static final String SQL_DELETE_NODE = "DELETE FROM syllabus_node WHERE code = ?";

    private static final String SQL_DELETE_GROUP = "DELETE FROM syllabus_group WHERE code = ?";

    /** 新增排在末尾({@code MAX + 1});表空时给 0。与文件版 {@code nodes.add(created)} 是同一句话。 */
    private static final String SQL_NEXT_NODE_ORDER =
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM syllabus_node WHERE group_code = ?";

    private static final String SQL_NEXT_GROUP_ORDER =
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM syllabus_group";

    // ———————————————————————— 冲突回查 ————————————————————————

    /** 唯一索引已经判完了,这一句只为拼报错:占名字的原样名、归没归档、在哪个题型下。 */
    private static final String SQL_NODE_BY_NAME_KEY =
            "SELECT n.name, n.archived, g.name FROM syllabus_node n "
                    + "JOIN syllabus_group g ON g.code = n.group_code WHERE n.name_key = ?";

    private static final String SQL_GROUP_BY_NAME_KEY =
            "SELECT name FROM syllabus_group WHERE name_key = ?";

    private final JdbcTemplate jdbc;

    /**
     * 行为层账本。<b>删除守则靠它兑现</b> —— 与 {@link FileSyllabusStore} 拿它的理由一模一样:
     * 让「先数记录」和「真的删」发生在同一个事务里,而不是分给两个类、留出一条绕过计数的调用顺序。
     *
     * <p>它的实现走同一个 {@code DataSource},所以 {@link NodeRecordLedger#countFor} 那次查询
     * 会加入本方法的事务 —— 计数与删除看的是同一个快照,这正是这层原子性的前提。
     */
    private final NodeRecordLedger ledger;

    public JdbcSyllabusStore(JdbcTemplate jdbc, NodeRecordLedger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    /**
     * 🔴 <b>每次调用都重新读库,一层缓存都没有</b>({@link SyllabusSource#current} 的约定)。
     *
     * <p>文件版可以把树留在内存里,因为「整个进程一份文件」是它的前提;这个实现存在的理由
     * 恰恰是那个前提没了。缓存一棵树在这里等于回到问题本身:另一个实例改了名、加了考点,
     * 这一侧的分母不动,<b>而且不会报错</b>。
     */
    @Override
    public Syllabus current() {
        Syllabus tree = readTree();
        return tree != null ? tree : seed();
    }

    // ———————————————————————— 考点 ————————————————————————

    @Override
    public Syllabus.Node addNode(String groupCode, String name, int recent5yCount) {
        String validName = FileSyllabusStore.validName(name);
        int count = validCount(recent5yCount);

        // 题型不在树上要先说 404 —— 与文件版同序:名字合不合法 → 频次 → 题型在不在 → 名字被没被占。
        // 顺带地,锁住题型那一行让并发的 addNode 排队,下面那个 MAX + 1 才不会被两个人同时读到同一个值。
        lockGroup(groupCode);

        // 🔴 code 由服务端生成,且不从名字派生 —— 见 SyllabusStore#addNode
        Syllabus.Node created = new Syllabus.Node(
                generateCode(NODE_CODE_PREFIX, this::nodeExists),
                validName, count, false);
        try {
            jdbc.update(SQL_INSERT_NODE, created.code(), groupCode, created.name(),
                    SyllabusNames.nameKey(created.name()), created.recent5yCount(), created.archived(),
                    oneInt(SQL_NEXT_NODE_ORDER, groupCode));
        } catch (DuplicateKeyException taken) {
            throw nodeNameConflict(created.name(), taken);
        }
        return created;
    }

    /**
     * 🔴 只改 name,code 原样留着。<b>整个类里没有任何一处会改动已存在的 code。</b>
     *
     * <p>先锁行判「树里有没有这个考点」,再让唯一索引判重名 —— 顺序与文件版一致,
     * 两者都失败时先说 404。反过来的话,给一个不存在的 code 改名会得到一句「名字被占了」,
     * 那是错的指路:用户会去改名字,而真正的问题是 code 根本不在树上。
     *
     * <p>🔴 <b>改回自己原来的名字要放行</b>:{@code name_key} 没变,MySQL 把它当成「这一行没动」,
     * 不会跟自己冲突。「增长量计算」改成「 增长量计算 」这种只差空格的同理 —— 它规范化之后就是自己。
     */
    @Override
    public Syllabus.Node renameNode(String nodeCode, String newName) {
        String validName = FileSyllabusStore.validName(newName);
        Syllabus.Node existing = lockedNode(nodeCode);
        try {
            jdbc.update(SQL_RENAME_NODE, validName, SyllabusNames.nameKey(validName), nodeCode);
        } catch (DuplicateKeyException taken) {
            throw nodeNameConflict(validName, taken);
        }
        return new Syllabus.Node(nodeCode, validName, existing.recent5yCount(), existing.archived());
    }

    @Override
    public Syllabus.Node setRecent5yCount(String nodeCode, int recent5yCount) {
        int count = validCount(recent5yCount);
        Syllabus.Node existing = lockedNode(nodeCode);
        jdbc.update(SQL_SET_COUNT, count, nodeCode);
        return new Syllabus.Node(nodeCode, existing.name(), count, existing.archived());
    }

    @Override
    public Syllabus.Node archiveNode(String nodeCode) {
        Syllabus.Node existing = lockedNode(nodeCode);
        if (existing.archived()) {
            throw SyllabusEditException.alreadyArchived(nodeCode);
        }
        jdbc.update(SQL_SET_ARCHIVED, true, nodeCode);
        return new Syllabus.Node(nodeCode, existing.name(), existing.recent5yCount(), true);
    }

    /**
     * 取消归档。
     *
     * <p>🔴 <b>这里不需要再查一次重名</b> —— 与文件版同一个理由,而且在这个实现里更硬:
     * {@code uk_syllabus_node_name_key} 建在<b>全表</b>上,归档行照样占着它的 {@code name_key}。
     * 归档考点的名字在它归档期间从来没被让出去过,所以接回来时必定还是唯一的。
     * 「唯一性含已归档」这条不是应用层的自觉,是索引的形状。
     */
    @Override
    public Syllabus.Node unarchiveNode(String nodeCode) {
        Syllabus.Node existing = lockedNode(nodeCode);
        if (!existing.archived()) {
            throw SyllabusEditException.notArchived(nodeCode);
        }
        jdbc.update(SQL_SET_ARCHIVED, false, nodeCode);
        return new Syllabus.Node(nodeCode, existing.name(), existing.recent5yCount(), false);
    }

    @Override
    public Syllabus.Node moveNode(String nodeCode, String targetGroupCode) {
        Syllabus.Node moving = lockedNode(nodeCode);
        String from = jdbc.queryForObject(SQL_NODE_GROUP, String.class, nodeCode);
        lockGroup(targetGroupCode);
        if (targetGroupCode.equals(from)) {
            return moving;                            // 已经在目标题型下,不写库
        }
        // 🔴 code 原样带过去,记录一条都不受影响;排在新题型的末尾,顺序另行调整
        jdbc.update(SQL_MOVE_NODE, targetGroupCode, oneInt(SQL_NEXT_NODE_ORDER, targetGroupCode),
                nodeCode);
        return moving;
    }

    /**
     * 🔴 删除守则的落点。见 {@link SyllabusStore#deleteNode} 的完整理由,以及类注释
     * 「删除守则的原子性落在哪」那一节。
     *
     * <p>三步在一个事务里:<b>锁住这一行</b> → 数记录 → 删。锁必须在计数<b>之前</b>拿到,
     * 否则「数出 0 条」和「删掉」之间就又有了一个窗口,而那正是这条守则要消灭的东西。
     *
     * <p>这里<b>没有 force 参数,也不接受任何形式的「我确定」</b>:一个能被绕过的守则不是守则。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void deleteNode(String nodeCode) {
        lockedNode(nodeCode);
        int records = ledger.countFor(nodeCode);
        if (records > 0) {
            throw SyllabusEditException.nodeHasRecords(nodeCode, records);
        }
        jdbc.update(SQL_DELETE_NODE, nodeCode);
    }

    /**
     * 删除守则给出的第一条出路。
     *
     * <p>目标必须是<b>未归档</b>的考点:归档的意思就是「不再往上挂东西」,
     * 往归档考点里搬记录等于把它们搬进一个不参与差集的地方 —— 数字上和丢了没区别。
     *
     * <p>🔴 四种拒绝<b>逐个分开,顺序照抄文件版</b>:先确认来源在不在(它可以是归档的 ——
     * 「把归档考点的记录搬走再真删掉」正是 {@code /api/v1/syllabus/archived} 那一屏的用途),
     * 再判同一个考点,最后才分「目标不存在」与「目标已归档」。
     * <p>
     * 最后一刀不能省。「已归档」很容易被写成一句 {@code NODE_NOT_FOUND} —— 那是错的指路:
     * 404 对应的下一步是「刷新一下,树可能变了」,而这里真正的下一步是「先给目标取消归档,或者换一个」。
     * 更糟的是它当场自相矛盾,因为归档清单刚把这个考点连名字带记录条数列出来过。
     *
     * <p>目标那一行是<b>锁着读</b>的:判完「没归档」到搬完之间,不能让另一个实例把它归档掉,
     * 否则记录会落在一个不参与差集的考点上,而调用方收到的是一句「搬走了 N 条」。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public int moveRecords(String fromNodeCode, String toNodeCode) {
        if (!nodeExists(fromNodeCode)) {
            throw SyllabusEditException.nodeNotFound(fromNodeCode);
        }
        if (fromNodeCode.equals(toNodeCode)) {
            // 归档与否都不影响这句话:来源和目标是同一个考点,这次搬迁没有意义
            throw SyllabusEditException.sameNode(fromNodeCode);
        }
        List<Boolean> target = jdbc.queryForList(SQL_LOCK_NODE_ARCHIVED, Boolean.class, toNodeCode);
        if (target.isEmpty()) {
            throw SyllabusEditException.nodeNotFound(toNodeCode);
        }
        if (Boolean.TRUE.equals(target.get(0))) {
            throw SyllabusEditException.nodeArchived(toNodeCode);
        }
        return ledger.moveAll(fromNodeCode, toNodeCode);
    }

    @Override
    public int recordCount(String nodeCode) {
        return ledger.countFor(nodeCode);
    }

    // ———————————————————————— 题型 ————————————————————————

    @Override
    public Syllabus.Group addGroup(String name) {
        String validName = FileSyllabusStore.validName(name);
        Syllabus.Group created = new Syllabus.Group(
                generateCode(GROUP_CODE_PREFIX, this::groupExists),
                validName, List.of());
        try {
            jdbc.update(SQL_INSERT_GROUP, created.code(), created.name(),
                    SyllabusNames.nameKey(created.name()), oneInt(SQL_NEXT_GROUP_ORDER));
        } catch (DuplicateKeyException taken) {
            throw groupNameConflict(created.name(), taken);
        }
        return created;
    }

    @Override
    public Syllabus.Group renameGroup(String groupCode, String newName) {
        String validName = FileSyllabusStore.validName(newName);
        lockGroup(groupCode);                          // 树里没有这个题型 → 404,先于重名判断
        List<Syllabus.Node> nodes = nodesOf(groupCode);
        try {
            // 🔴 code 原样留着。题型 code 同样是主键,同样不因改名而变
            jdbc.update(SQL_RENAME_GROUP, validName, SyllabusNames.nameKey(validName), groupCode);
        } catch (DuplicateKeyException taken) {
            throw groupNameConflict(validName, taken);
        }
        return new Syllabus.Group(groupCode, validName, nodes);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void deleteGroup(String groupCode) {
        lockGroup(groupCode);
        // 含已归档的考点 —— 归档节点上照样挂着记录,连带删除会一次性造出一批孤儿。
        // 计数与删除同处一个事务、题型那一行还锁着,所以「一边删空题型、一边往里加考点」这条顺序不成立。
        int nodes = oneInt(SQL_NODES_IN_GROUP, groupCode);
        if (nodes > 0) {
            throw SyllabusEditException.groupNotEmpty(groupCode, nodes);
        }
        jdbc.update(SQL_DELETE_GROUP, groupCode);
    }

    // ———————————————————————— 顺序 ————————————————————————

    @Override
    public Syllabus reorderGroups(List<String> groupCodes) {
        List<String> existing = jdbc.queryForList(SQL_GROUP_CODES, String.class);
        requirePermutation(existing, groupCodes, "题型");

        int order = 0;
        for (String code : groupCodes) {
            jdbc.update(SQL_GROUP_ORDER, order++, code);
        }
        return current();
    }

    /**
     * 重排某个题型下的考点。
     *
     * <p>🔴 已归档的考点<b>不参与排序,重排后统一沉到末尾、保持原有的相对顺序</b>
     * ({@link SyllabusStore#reorderNodes})。两段 code 都在改任何一行之前读出来,
     * 「原有的相对顺序」指的就是这一刻的 {@code sort_order},不是被改了一半之后的。
     */
    @Override
    public Syllabus reorderNodes(String groupCode, List<String> nodeCodes) {
        lockGroup(groupCode);
        List<String> active = jdbc.queryForList(SQL_GROUP_NODE_CODES, String.class, groupCode, false);
        List<String> archived = jdbc.queryForList(SQL_GROUP_NODE_CODES, String.class, groupCode, true);
        requirePermutation(active, nodeCodes, "考点");

        int order = 0;
        for (String code : nodeCodes) {
            jdbc.update(SQL_NODE_ORDER, order++, code);
        }
        for (String code : archived) {
            jdbc.update(SQL_NODE_ORDER, order++, code);
        }
        return current();
    }

    // ———————————————————————— 校验 ————————————————————————
    //
    // 名字的合法性【不在这里判】—— 上面每一处都直接调 FileSyllabusStore.validName。
    // 它是这个包里唯一一处两个 store 共用同一段实现的地方,而且必须是:那五条规则
    // (非空、40 字上限、拒控制字符、拒不可见码点、不能整个名字都看不见)还有第三个入口 ——
    // SyllabusLoader 载入磁盘/种子文件时走的也是它。三处各写一份的下场是
    // 「接口拒绝、文件却能载入」,而导出 → 手工改 → 放回正是官方给出的用法。

    /**
     * 顺序列表必须是现有条目的<b>完整排列</b> —— 与 {@code FileSyllabusStore#requirePermutation}
     * <b>逐字同一段</b>,包括那三句报错的措辞。
     *
     * <p>不做「给了几个就排几个,剩下的按原序补在后面」这种补救:
     * 那会让「客户端漏传了一个」和「客户端想把它排到最后」变成同一个请求,
     * 而前者的结果是<b>一个考点悄悄换了位置</b>,没有任何提示。宁可整体拒绝。
     *
     * <p>⚠️ 它是文件版那一份的<b>第二份拷贝</b>,因为那边是 {@code private}。两份用户可见的报错文案
     * 迟早会漂,而漂开的那天两个后端会对同一个请求说两句不同的话。要收成一份,
     * 只需把文件版那个方法的 {@code private} 去掉 —— 行为一个字节都不用动。
     */
    private static void requirePermutation(List<String> existing, List<String> given, String what) {
        if (given == null) {
            throw SyllabusEditException.orderNotAPermutation(what + "顺序不能为空");
        }
        Set<String> givenSet = new LinkedHashSet<>(given);
        if (givenSet.size() != given.size()) {
            throw SyllabusEditException.orderNotAPermutation(what + "顺序里有重复的 code");
        }
        if (givenSet.size() != existing.size() || !givenSet.containsAll(existing)) {
            throw SyllabusEditException.orderNotAPermutation(
                    "顺序必须是现有" + what + "的完整排列:现有 " + existing.size()
                            + " 个,收到 " + givenSet.size() + " 个。少一个就等于悄悄删一个,所以整体拒绝。");
        }
    }

    /**
     * 近五年频次必须非负。缺省成 0 看起来更宽容,实际是把「这个考点近五年一次没考过」
     * 和「调用方算错了」混成同一个值,而前者会让它在盲区排序里直接沉底,没人会发现。
     */
    private static int validCount(int recent5yCount) {
        if (recent5yCount < 0) {
            throw SyllabusEditException.invalidFrequency(recent5yCount);
        }
        return recent5yCount;
    }

    /**
     * 生成一个还没被用过的 code —— 与 {@code FileSyllabusStore#generateCode} 同一种生成方式,
     * 只是「用过没有」问的是库而不是内存里那棵树。
     *
     * <p>🔴 <b>不从名字派生</b>。派生等于把名字焊回 code,而 code 存在的全部理由就是
     * 「改名不断历史」({@link Syllabus} 的字段说明)。中文名派生只有两条路 ——
     * 直接拿中文当 code,或者转拼音把某一种措辞编码进主键 —— 两条都不走。
     *
     * <p>这次「查了没被占」到「真的插进去」之间仍有一个理论窗口,但它<b>不需要靠这里堵</b>:
     * code 是主键,撞上了就是一次唯一键冲突,{@link #nodeNameConflict} 回查 {@code name_key}
     * 落空后会把原始异常原样抛出去 —— 不会被误报成一句「名字被占了」。
     */
    private static String generateCode(String prefix, Predicate<String> taken) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String code = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            if (!taken.test(code)) {
                return code;
            }
        }
        throw new IllegalStateException("连续 100 次都生成到重复的 code,这不该发生");
    }

    // ———————————————————————— 载入 ————————————————————————

    /**
     * 读整棵树。<b>{@code null} 只有一个意思:这个库还没播过种。</b>
     *
     * <p>判据取 {@code syllabus_subject} 那一行,不取「有没有题型」:题型是可以被用户删光的
     * ({@link #deleteGroup}),一棵零题型的树是合法状态;而学科元信息那一行<b>没有任何一个接口能删</b>
     * ({@link SyllabusStore} 上根本没有 subject 这一档),它在就是播过种。
     * <p>
     * 于是「subject 没了、题型还在」是第三种状态 —— 有人手工删了那一行。<b>它必须响亮失败</b>:
     * 当成「还没播种」去补种会撞上唯一索引报出一句看不懂的话,当成空树更糟 ——
     * 覆盖度的分母静默归零,所有记录一起变成孤儿。
     */
    private Syllabus readTree() {
        List<Syllabus.Subject> subject = jdbc.query(SQL_SELECT_SUBJECT, (rs, row) -> new Syllabus.Subject(
                rs.getString("code"),
                rs.getString("region"),
                rs.getString("exam"),
                rs.getString("module"),
                rs.getString("recent5y_window")));
        List<Syllabus.Group> groups = readGroups();

        if (subject.isEmpty()) {
            if (!groups.isEmpty()) {
                throw new SyllabusDataException("骨架数据不合法(syllabus_subject):学科元信息那一行不见了,"
                        + "可 syllabus_group 里还有 " + groups.size() + " 个题型。这不是「还没播种」——"
                        + "没有任何接口能删掉那一行,所以它只可能是被手工删的。"
                        + "当成空树继续跑,覆盖度的分母会静默归零,所有记录一起变成孤儿;"
                        + "补种又会撞上唯一索引。先把 syllabus_subject 那一行补回来再启动。");
            }
            return null;
        }
        return new Syllabus(subject.get(0), groups);
    }

    /** 两次查询拼一棵树:题型按树序,考点按组内树序、<b>含已归档</b>(差集那一侧自己筛)。 */
    private List<Syllabus.Group> readGroups() {
        Map<String, List<Syllabus.Node>> byGroup = new LinkedHashMap<>();
        jdbc.query(SQL_SELECT_NODES, (rs, row) -> byGroup
                .computeIfAbsent(rs.getString("group_code"), g -> new ArrayList<>())
                .add(new Syllabus.Node(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getInt("recent5y_count"),
                        rs.getBoolean("archived"))));

        List<Syllabus.Group> groups = jdbc.query(SQL_SELECT_GROUPS, (rs, row) -> {
            String code = rs.getString("code");
            return new Syllabus.Group(code, rs.getString("name"),
                    List.copyOf(byGroup.getOrDefault(code, List.of())));
        });

        // 外挂不上题型的考点。外键 fk_syllabus_node_group 让它不可能发生 —— 除非有人关掉了外键检查
        // (导入、恢复备份时很常见)。悄悄丢掉它们就是悄悄让分母少几个,所以宁可在这里起不来。
        long attached = groups.stream().mapToLong(g -> g.nodes().size()).sum();
        long total = byGroup.values().stream().mapToLong(List::size).sum();
        if (attached != total) {
            throw new SyllabusDataException("骨架数据不合法(syllabus_node):有 " + (total - attached)
                    + " 个考点的 group_code 指向一个不存在的题型。它们挂不进树,就等于从覆盖度的分母里"
                    + "消失了,而这件事没有任何提示。外键 fk_syllabus_node_group 本该挡住它 ——"
                    + "先查一下这个库的外键检查是不是被关掉过。");
        }
        return groups;
    }

    /**
     * 播种。表为空时从 classpath 种子建树,与文件版第一次启动走的是<b>同一份解析</b>
     * ({@link SyllabusLoader}):重复 code、重复名、非法 {@code recent5yCount} 都在那里响亮失败。
     *
     * <p>🔴 种子是<b>我们自己归纳的一棵树</b>,不是从任何机构的目录页拷来的
     * (R-07 / docs/decisions/实施路径.md §1.2)。播完之后它就是用户自己的树 ——
     * {@link #current()} 只在 subject 那一行不存在时播,种子文件再也不会覆盖它。
     *
     * <h2>并发首播只会发生一次</h2>
     *
     * 靠的是 {@code syllabus_subject} 的主键(单行表,{@code id} 恒为 1),而不是「先查再写」。
     * 两个实例同时开张时,后到的那条 INSERT 会等在行锁上,等到的是一次唯一键冲突 ——
     * 而<b>那是本事务的第一条写语句,此刻一行都还没写进去</b>,所以什么都不用撤销。
     * <p>
     * 冲突之后不再重读一遍:REPEATABLE READ 的快照在上面那次 {@link #readTree()} 就定住了,
     * 再读看到的还是空。赢的那一方写进去的正是同一份 classpath 种子(同一个 jar、同一段解析),
     * 所以这一次直接返回解析结果 —— 它逐字段就是库里那棵树;下一次 {@code current()} 是新事务、
     * 新快照,读的是真行。
     * <p>
     * 种子本身的重复在 {@link SyllabusLoader} 就被挡下了,所以这里的冲突<b>只剩并发首播一个解释</b>。
     * 也正因为如此,题型与考点的插入<b>不</b>吞冲突:那一段再撞,就是真的坏了,让它炸。
     */
    private Syllabus seed() {
        Syllabus seed = SyllabusLoader.loadDefault();
        try {
            jdbc.update(SQL_INSERT_SUBJECT, seed.subject().code(), seed.subject().region(),
                    seed.subject().exam(), seed.subject().module(), seed.subject().recent5yWindow());
        } catch (DuplicateKeyException raced) {
            return seed;
        }

        int groupOrder = 0;
        for (Syllabus.Group g : seed.groups()) {
            jdbc.update(SQL_INSERT_GROUP, g.code(), g.name(), SyllabusNames.nameKey(g.name()),
                    groupOrder++);
            int nodeOrder = 0;
            for (Syllabus.Node n : g.nodes()) {
                jdbc.update(SQL_INSERT_NODE, n.code(), g.code(), n.name(),
                        SyllabusNames.nameKey(n.name()), n.recent5yCount(), n.archived(), nodeOrder++);
            }
        }
        return readTree();                 // 读自己刚写进去的行,顺便验一遍每一列都填对了
    }

    // ———————————————————————— 内部 ————————————————————————

    /** 锁住考点那一行并读出来。树里没有 → 404,与文件版 {@code groupIndexOfNode} 同一句话。 */
    private Syllabus.Node lockedNode(String nodeCode) {
        List<Syllabus.Node> found = jdbc.query(SQL_LOCK_NODE, (rs, row) -> new Syllabus.Node(
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("recent5y_count"),
                rs.getBoolean("archived")), nodeCode);
        if (found.isEmpty()) {
            throw SyllabusEditException.nodeNotFound(nodeCode);
        }
        return found.get(0);
    }

    /** 锁住题型那一行。树里没有 → 404,与文件版 {@code groupIndex} 同一句话。 */
    private void lockGroup(String groupCode) {
        if (jdbc.queryForList(SQL_LOCK_GROUP, String.class, groupCode).isEmpty()) {
            throw SyllabusEditException.groupNotFound(groupCode);
        }
    }

    private List<Syllabus.Node> nodesOf(String groupCode) {
        return jdbc.query(SQL_SELECT_GROUP_NODES, (rs, row) -> new Syllabus.Node(
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("recent5y_count"),
                rs.getBoolean("archived")), groupCode);
    }

    private boolean nodeExists(String nodeCode) {
        return !jdbc.queryForList(SQL_NODE_EXISTS, String.class, nodeCode).isEmpty();
    }

    private boolean groupExists(String groupCode) {
        return !jdbc.queryForList(SQL_GROUP_EXISTS, String.class, groupCode).isEmpty();
    }

    /** 一条只返回一个整数的查询 —— 「下一个树序」和「组里还剩几个考点」共用它。 */
    private int oneInt(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    /**
     * 唯一索引已经拒了这次写入,这里只负责把「被谁占着」查出来,拼成那句说得清的 409。
     *
     * <p>回查落空说明冲突不在 {@code name_key} 上(比如撞了主键)——<b>原样抛回原始异常</b>。
     * 把一次别的冲突改写成「名字被占了」,会让人对着一个没人叫的名字改上半天。
     */
    private RuntimeException nodeNameConflict(String wanted, DuplicateKeyException cause) {
        return jdbc.query(SQL_NODE_BY_NAME_KEY, (ResultSetExtractor<RuntimeException>) rs -> {
            if (!rs.next()) {
                return cause;
            }
            String existing = rs.getString(1);
            boolean archived = rs.getBoolean(2);
            String groupName = rs.getString(3);
            // 🔴 归档与否要分开说:归档的那个用户在树上根本看不见,不点破就是一句无解的报错
            return archived
                    ? SyllabusEditException.nodeNameTakenByArchived(wanted, existing, groupName)
                    : SyllabusEditException.nodeNameTaken(wanted, existing, groupName);
        }, SyllabusNames.nameKey(wanted));
    }

    /** 题型没有归档这回事({@link Syllabus.Group} 上没有 archived),所以只有一种冲突。 */
    private RuntimeException groupNameConflict(String wanted, DuplicateKeyException cause) {
        List<String> existing = jdbc.queryForList(SQL_GROUP_BY_NAME_KEY, String.class,
                SyllabusNames.nameKey(wanted));
        return existing.isEmpty()
                ? cause
                : SyllabusEditException.groupNameTaken(wanted, existing.get(0));
    }
}
