package com.kaodian.server.syllabus;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * {@link SyllabusStore} 的阶段 0/1 实现 —— <b>一个 JSON 文件,没有数据库。</b>
 *
 * <p>与 {@code FileTouchStore} 是同一套做法,理由也是同一个(docs/technical/INDEX.md §零:
 * 数据层落库最早到阶段 1 的 {@code 1.2.4})。两个文件放在同一个目录里,
 * <b>把 {@code ~/.kaodian} 拷走就是全部数据</b> —— 骨架和行为一起,缺一个另一个就没意义。
 *
 * <h2>🔴 读写都是逐字段列举的,不用自动序列化</h2>
 *
 * 读:{@link SyllabusLoader#parse} 只认 code / name / recent5yCount / archived 四样。
 * 写:{@link #toNode} 显式列出文件里能出现哪些键。
 * <p>
 * 于是即便有人手工往 {@code syllabus.json} 里给某个考点塞了一段解析,它<b>到不了任何地方</b> ——
 * 既读不进来,也不会因为 {@link Syllabus.Node} 将来多了个字段就悄悄流回文件。
 * 这与 {@code FileTouchStore} 是同一条思路:不给内容留位置(决策记录 §2.2 / docs/technical/INDEX.md §5.1)。
 *
 * <h2>🔴 坏文件必须响亮失败</h2>
 *
 * {@code FileTouchStore} 的那条教训在这里更严重:{@code path("groups")} 在键名缺失时
 * 静默返回空 → 「解析成功、0 个题型」→ 下一次写入全量重写 → <b>整棵骨架被一棵空树盖掉,
 * 所有记录一起变成孤儿</b>。校验放在 {@link SyllabusLoader#parse} 里,种子和数据文件共用。
 *
 * <h2>写入:先写临时文件,再原子 rename</h2>
 *
 * 与行为层同样的理由:直接在原文件上截断重写,写到一半断电就是一个半截 JSON,
 * 整棵树一起没。骨架是用户一个考点一个考点敲出来的,它和记录一样是资产。
 */
@Component
public class FileSyllabusStore implements SyllabusStore {

    private static final String FILE_NAME = "syllabus.json";
    private static final String TMP_SUFFIX = ".tmp";

    /**
     * 考点名 / 题型名的长度上限。
     *
     * <p>「增长量计算」五个字,「资料分析速算技巧」八个字。40 是宽裕的,
     * 而它同时挡住了<b>把一整段题干贴进「考点名」</b>这条最省事的绕路 ——
     * 与 {@code CreateRecordRequest.sourceName} 上那个 60 是同一种上限:
     * 它防的不是「名字太长不好看」,是内容夹带。
     */
    public static final int MAX_NAME_LENGTH = 40;

    /** 服务端生成的 code 前缀。种子里的 code 是手写的英文短语,这两个前缀让来源一眼可分。 */
    private static final String NODE_CODE_PREFIX = "n-";
    private static final String GROUP_CODE_PREFIX = "g-";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    /**
     * 行为层账本。<b>删除守则靠它兑现</b> —— 见 {@link NodeRecordLedger}。
     *
     * <p>它被拿进 store 而不是留在控制器里,是为了让「先数记录、再决定能不能删」
     * 和「真的删」发生在<b>同一把锁下</b>:分开就意味着存在一个时间窗,
     * 也意味着存在一条绕过计数直接删的调用顺序。
     */
    private final NodeRecordLedger ledger;

    /** 单进程单用户,一把锁足够。 */
    private final Object lock = new Object();

    /** {@code null} 表示还没载入。载入推迟到第一次访问,见 {@link #ensureLoaded}。 */
    private Syllabus current;

    /**
     * @param dataDir 数据目录,默认 {@code ~/.kaodian} —— 与行为层同一个目录
     */
    @Autowired
    public FileSyllabusStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir,
                             NodeRecordLedger ledger) {
        this(Path.of(dataDir).resolve(FILE_NAME), ledger);
    }

    public FileSyllabusStore(Path file, NodeRecordLedger ledger) {
        this.file = file.toAbsolutePath();
        this.ledger = ledger;
    }

    /** 数据文件的位置。导出、备份、「我的树到底存在哪」都指着它。 */
    public Path dataFile() {
        return file;
    }

    @Override
    public Syllabus current() {
        synchronized (lock) {
            ensureLoaded();
            return current;
        }
    }

    // ———————————————————————— 考点 ————————————————————————

    @Override
    public Syllabus.Node addNode(String groupCode, String name, int recent5yCount) {
        String validName = validName(name);
        int count = validCount(recent5yCount);

        synchronized (lock) {
            ensureLoaded();
            Syllabus s = current;
            int gi = groupIndex(s, groupCode);
            // 🔴 名字整棵树唯一(含已归档)。在锁里查、在锁里写 —— 分开就存在一个能挤进两个同名的窗口
            requireNodeNameFree(s, validName, null);

            // 🔴 code 由服务端生成,且不从名字派生 —— 见 SyllabusStore#addNode
            Syllabus.Node created = new Syllabus.Node(
                    generateCode(NODE_CODE_PREFIX, c -> s.nodeIncludingArchived(c) != null),
                    validName, count, false);

            Syllabus.Group g = s.groups().get(gi);
            List<Syllabus.Node> nodes = new ArrayList<>(g.nodes());
            nodes.add(created);                       // 新考点排在本题型末尾,顺序另行调整
            commit(withGroup(s, gi, new Syllabus.Group(g.code(), g.name(), List.copyOf(nodes))));
            return created;
        }
    }

    /**
     * 🔴 只改 name,code 原样传下去。<b>整个类里没有任何一处会改动已存在的 code。</b>
     *
     * <p>安全性的来源见 {@link SyllabusStore#renameNode}:记录挂 code 不挂名字。
     *
     * <p>先判「树里有没有这个考点」再判重名:两者都失败时应当先说 404。
     * 反过来的话,给一个不存在的 code 改名会得到一句「名字被占了」——
     * 那是错的指路,用户会去改名字,而真正的问题是 code 根本不在树上。
     *
     * <p>🔴 <b>改回自己原来的名字要放行</b>({@code selfCode = nodeCode})。
     * 「改名」在界面上是一个预填了当前名字的输入框,直接按确定是最常见的操作之一,
     * 让它报 409 是荒唐的。顺带地,把「增长量计算」改成「 增长量计算 」这种只差空格的
     * 也照样放行 —— 它规范化之后就是自己。
     */
    @Override
    public Syllabus.Node renameNode(String nodeCode, String newName) {
        String validName = validName(newName);
        synchronized (lock) {
            ensureLoaded();
            if (current.nodeIncludingArchived(nodeCode) == null) {
                throw SyllabusEditException.nodeNotFound(nodeCode);
            }
            requireNodeNameFree(current, validName, nodeCode);
            return mutateNode(nodeCode, n ->
                    new Syllabus.Node(n.code(), validName, n.recent5yCount(), n.archived()));
        }
    }

    @Override
    public Syllabus.Node setRecent5yCount(String nodeCode, int recent5yCount) {
        int count = validCount(recent5yCount);
        return mutateNode(nodeCode, n ->
                new Syllabus.Node(n.code(), n.name(), count, n.archived()));
    }

    @Override
    public Syllabus.Node archiveNode(String nodeCode) {
        return mutateNode(nodeCode, n -> {
            if (n.archived()) {
                throw SyllabusEditException.alreadyArchived(nodeCode);
            }
            return new Syllabus.Node(n.code(), n.name(), n.recent5yCount(), true);
        });
    }

    /**
     * 取消归档。
     *
     * <p>🔴 <b>这里不需要再查一次重名</b>,而这不是省事,是
     * {@link #requireNodeNameFree} 「把已归档的也算进来」那条规则的直接兑现:
     * 归档考点的名字在它归档期间从来没被让出去过 —— 任何想占用它的新增/重命名都已经被 409 挡下。
     * 所以它接回差集时必定还是唯一的。
     * <p>
     * 反过来,如果唯一性只看未归档的节点,这里就必须再补一次检查,而且那次检查会
     * <b>在最坏的时刻</b>失败:用户只是想把一个考点接回来,却被告知名字没了。
     * 不变式在一处成立,到处成立。
     */
    @Override
    public Syllabus.Node unarchiveNode(String nodeCode) {
        return mutateNode(nodeCode, n -> {
            if (!n.archived()) {
                throw SyllabusEditException.notArchived(nodeCode);
            }
            return new Syllabus.Node(n.code(), n.name(), n.recent5yCount(), false);
        });
    }

    @Override
    public Syllabus.Node moveNode(String nodeCode, String targetGroupCode) {
        synchronized (lock) {
            ensureLoaded();
            Syllabus s = current;
            int from = groupIndexOfNode(s, nodeCode);
            int to = groupIndex(s, targetGroupCode);

            Syllabus.Group source = s.groups().get(from);
            Syllabus.Node moving = source.nodes().stream()
                    .filter(n -> n.code().equals(nodeCode)).findFirst().orElseThrow();
            if (from == to) {
                return moving;                        // 已经在目标题型下,不写盘
            }

            List<Syllabus.Group> groups = new ArrayList<>(s.groups());
            List<Syllabus.Node> fromNodes = new ArrayList<>(source.nodes());
            fromNodes.removeIf(n -> n.code().equals(nodeCode));
            groups.set(from, new Syllabus.Group(source.code(), source.name(), List.copyOf(fromNodes)));

            Syllabus.Group target = groups.get(to);
            List<Syllabus.Node> toNodes = new ArrayList<>(target.nodes());
            toNodes.add(moving);                      // 🔴 code 原样带过去,记录一条都不受影响
            groups.set(to, new Syllabus.Group(target.code(), target.name(), List.copyOf(toNodes)));

            commit(new Syllabus(s.subject(), List.copyOf(groups)));
            return moving;
        }
    }

    /**
     * 🔴 删除守则的落点。见 {@link SyllabusStore#deleteNode} 的完整理由。
     *
     * <p>这里<b>没有 force 参数,也不接受任何形式的「我确定」</b>:一个能被绕过的守则
     * 不是守则,而这条守则守的是覆盖率 —— 这个产品唯一的那个数。
     */
    @Override
    public void deleteNode(String nodeCode) {
        synchronized (lock) {
            ensureLoaded();
            Syllabus s = current;
            int gi = groupIndexOfNode(s, nodeCode);

            int records = ledger.countFor(nodeCode);
            if (records > 0) {
                throw SyllabusEditException.nodeHasRecords(nodeCode, records);
            }

            Syllabus.Group g = s.groups().get(gi);
            List<Syllabus.Node> nodes = new ArrayList<>(g.nodes());
            nodes.removeIf(n -> n.code().equals(nodeCode));
            commit(withGroup(s, gi, new Syllabus.Group(g.code(), g.name(), List.copyOf(nodes))));
        }
    }

    /**
     * 删除守则给出的第一条出路。
     *
     * <p>目标必须是<b>未归档</b>的考点:归档的意思就是「不再往上挂东西」,
     * 往归档考点里搬记录等于把它们搬进一个不参与差集的地方 —— 数字上和丢了没区别。
     *
     * <p>🔴 四种拒绝<b>逐个分开</b>,顺序也是有讲究的:先确认来源在不在(它可以是归档的 ——
     * 「把归档考点的记录搬走再真删掉」正是 {@code /api/syllabus/archived} 那一屏的用途),
     * 再判同一个考点,最后才分「目标不存在」与「目标已归档」。
     * <p>
     * 这最后一刀不能省。{@code Syllabus#node} 查不到归档考点,于是「已归档」很容易被写成
     * 一句 {@code NODE_NOT_FOUND} —— 那是错的指路:404 对应的下一步是「刷新一下,树可能变了」,
     * 而这里真正的下一步是「先给目标取消归档,或者换一个」。更糟的是它当场自相矛盾,
     * 因为 {@code GET /api/syllabus/archived} 刚把这个考点连名字带记录条数列出来过。
     */
    @Override
    public int moveRecords(String fromNodeCode, String toNodeCode) {
        synchronized (lock) {
            ensureLoaded();
            Syllabus s = current;
            if (s.nodeIncludingArchived(fromNodeCode) == null) {
                throw SyllabusEditException.nodeNotFound(fromNodeCode);
            }
            if (fromNodeCode.equals(toNodeCode)) {
                // 归档与否都不影响这句话:来源和目标是同一个考点,这次搬迁没有意义
                throw SyllabusEditException.sameNode(fromNodeCode);
            }
            if (s.nodeIncludingArchived(toNodeCode) == null) {
                throw SyllabusEditException.nodeNotFound(toNodeCode);
            }
            if (s.node(toNodeCode) == null) {
                throw SyllabusEditException.nodeArchived(toNodeCode);
            }
            return ledger.moveAll(fromNodeCode, toNodeCode);
        }
    }

    @Override
    public int recordCount(String nodeCode) {
        return ledger.countFor(nodeCode);
    }

    // ———————————————————————— 题型 ————————————————————————

    @Override
    public Syllabus.Group addGroup(String name) {
        String validName = validName(name);
        synchronized (lock) {
            ensureLoaded();
            Syllabus s = current;
            requireGroupNameFree(s, validName, null);
            Syllabus.Group created = new Syllabus.Group(
                    generateCode(GROUP_CODE_PREFIX, c -> s.group(c) != null),
                    validName, List.of());

            List<Syllabus.Group> groups = new ArrayList<>(s.groups());
            groups.add(created);
            commit(new Syllabus(s.subject(), List.copyOf(groups)));
            return created;
        }
    }

    @Override
    public Syllabus.Group renameGroup(String groupCode, String newName) {
        String validName = validName(newName);
        synchronized (lock) {
            ensureLoaded();
            Syllabus s = current;
            int gi = groupIndex(s, groupCode);       // 树里没有这个题型 → 404,先于重名判断
            requireGroupNameFree(s, validName, groupCode);   // 改回自己原来的名字要放行
            Syllabus.Group g = s.groups().get(gi);
            // 🔴 code 原样传下去。题型 code 同样是主键,同样不因改名而变
            Syllabus.Group renamed = new Syllabus.Group(g.code(), validName, g.nodes());
            commit(withGroup(s, gi, renamed));
            return renamed;
        }
    }

    @Override
    public void deleteGroup(String groupCode) {
        synchronized (lock) {
            ensureLoaded();
            Syllabus s = current;
            int gi = groupIndex(s, groupCode);
            Syllabus.Group g = s.groups().get(gi);
            if (!g.nodes().isEmpty()) {
                // 含已归档的考点 —— 归档节点上照样挂着记录,连带删除会一次性造出一批孤儿
                throw SyllabusEditException.groupNotEmpty(groupCode, g.nodes().size());
            }
            List<Syllabus.Group> groups = new ArrayList<>(s.groups());
            groups.remove(gi);
            commit(new Syllabus(s.subject(), List.copyOf(groups)));
        }
    }

    // ———————————————————————— 顺序 ————————————————————————

    @Override
    public Syllabus reorderGroups(List<String> groupCodes) {
        synchronized (lock) {
            ensureLoaded();
            Syllabus s = current;
            List<String> existing = s.groups().stream().map(Syllabus.Group::code).toList();
            requirePermutation(existing, groupCodes, "题型");

            List<Syllabus.Group> reordered = groupCodes.stream().map(s::group).toList();
            commit(new Syllabus(s.subject(), List.copyOf(reordered)));
            return current;
        }
    }

    @Override
    public Syllabus reorderNodes(String groupCode, List<String> nodeCodes) {
        synchronized (lock) {
            ensureLoaded();
            Syllabus s = current;
            int gi = groupIndex(s, groupCode);
            Syllabus.Group g = s.groups().get(gi);

            List<String> existing = g.activeNodes().stream().map(Syllabus.Node::code).toList();
            requirePermutation(existing, nodeCodes, "考点");

            List<Syllabus.Node> reordered = new ArrayList<>();
            for (String code : nodeCodes) {
                g.activeNodes().stream().filter(n -> n.code().equals(code)).findFirst()
                        .ifPresent(reordered::add);
            }
            reordered.addAll(g.archivedNodes());      // 归档的不参与排序,统一沉到末尾
            commit(withGroup(s, gi, new Syllabus.Group(g.code(), g.name(), List.copyOf(reordered))));
            return current;
        }
    }

    /**
     * 顺序列表必须是现有条目的<b>完整排列</b>。
     *
     * <p>不做「给了几个就排几个,剩下的按原序补在后面」这种补救:
     * 那会让「客户端漏传了一个」和「客户端想把它排到最后」变成同一个请求,
     * 而前者的结果是<b>一个考点悄悄换了位置</b>,没有任何提示。宁可整体拒绝。
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

    // ———————————————————————— 校验 ————————————————————————

    /**
     * 名字的五条规则:非空、不超长、不含控制字符、<b>不含看不见的字符</b>、
     * <b>不能整个名字都是看不见的字符</b>。
     *
     * <p>控制字符里最要紧的是<b>换行</b> —— 一个带换行的「考点名」几乎只可能是
     * 有人把一段题干或一段讲义贴了进来。名字是名字,不是放内容的地方(决策记录 §2.2)。
     *
     * <h2>🔴 看不见的字符<b>直接拒绝,不是规范化掉</b></h2>
     *
     * 它与换行同理,但更恶劣:零宽空格 U+200B、谚文填充符 U+3164、盲文空点 U+2800 这一类
     * 在考点名里<b>没有任何正当用途</b>,唯一效果就是造出一个肉眼不可见的区别 ——
     * 「增长量计算」和「​增长量计算」渲染出来一模一样,而用户是<b>按名字</b>挑考点的。
     * <p>
     * 那为什么不像空格那样规范化掉?因为悄悄删字符等于替用户改名字,而且他永远不会知道
     * 自己粘进来的东西被动过。<b>拒绝并说清楚</b>,才让人有机会去查粘贴源。
     *
     * <h2>🔴 判定口径在 {@link SyllabusNames#isInvisible},不在这里</h2>
     *
     * 早先这里写的是 {@code Character.getType(cp) == Character.FORMAT}。
     * 那只覆盖 Cf 一类,<b>实测挡不住</b>变体选择符(Mn)、谚文填充符(Lo)、盲文空点(So)——
     * 它们同样看不见,同样能凭空造出一个重名。口径挪到 {@link SyllabusNames} 之后,
     * 「什么算看不见」这件事写在一处,查重(比较)和校验(拒绝)不会各有一套说法。
     *
     * <p><b>唯一的例外是变体选择符</b>:它依附于前一个字符,会跟着 emoji 一起被正常输入,
     * 拒绝它就会误伤「增长量计算❤️」这种用户明明看得见的名字(理由见
     * {@link SyllabusNames#isVariationSelector})。它由 {@link SyllabusNames#nameKey}
     * 剥掉,所以放行它<b>造不出第二个名字</b>。
     *
     * <p>逐<b>码点</b>而不是逐 char,否则辅助平面里的不可见码点(U+E0100 那一批)
     * 会被拆成两个代理项漏过去。
     *
     * @return 去掉首尾空白之后的名字 —— <b>存的就是这个,不做规范化</b>。
     *         规范化只用于比较,见 {@link SyllabusNames#nameKey}
     */
    static String validName(String raw) {
        if (raw == null) {
            throw SyllabusEditException.invalidName("名称不能为空");
        }
        String name = raw.strip();
        if (name.isEmpty()) {
            throw SyllabusEditException.invalidName("名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw SyllabusEditException.invalidName(
                    "名称最长 " + MAX_NAME_LENGTH + " 个字符 —— 它是个名字,不是放内容的地方");
        }
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (Character.isISOControl(cp)) {
                throw SyllabusEditException.invalidName(
                        "名称里不能有换行或控制字符 —— 带换行的「考点名」通常意味着贴进来的是一段内容");
            }
            if (SyllabusNames.isInvisible(cp) && !SyllabusNames.isVariationSelector(cp)) {
                throw SyllabusEditException.invalidName(
                        "名称里不能有看不见的字符 —— 零宽、填充、占位这一类(U+"
                                + Integer.toHexString(cp).toUpperCase(Locale.ROOT)
                                + ")。它渲染不出任何东西,唯一的效果是造出一个肉眼分不出的重名。"
                                + "这类字符通常是从网页或 PDF 里粘贴时带进来的,请重新手打一遍这个名字。");
            }
            i += Character.charCount(cp);
        }
        // 🔴 放行变体选择符的代价:一个「只由变体选择符组成」的名字上面每一条都过得去,
        // 却在面板上渲染成一片空白 —— 用户会看到一个没有名字的考点,而他是按名字挑考点的。
        // 用 nameKey 判空最准:它剥掉的正好是「看不见的 + 空白」,剩下的就是看得见的部分。
        if (SyllabusNames.nameKey(name).isEmpty()) {
            throw SyllabusEditException.invalidName(
                    "名称里一个看得见的字符都没有 —— 它在列表里会渲染成一片空白,"
                            + "而考点是按名字挑的。请给它起一个念得出来的名字。");
        }
        return name;
    }

    // ———————————————————————— 🔴 名字唯一性 ————————————————————————

    /**
     * 🔴 考点名<b>整棵树唯一,并且包含已归档的考点</b>。
     *
     * <h2>为什么范围是整棵树,不是「同题型内唯一」</h2>
     *
     * <ol>
     *   <li><b>前端按名字挑考点</b>。命令面板上只有名字与状态,<b>不显示题型</b> ——
     *       跨题型同名和同题型同名一样分不出来,记录会被劈到两个语义相同的 code 上,
     *       覆盖率的分子被稀释,「整块空白」跟着失真。而覆盖率是这个产品唯一的那个数
     *       (决策记录 §2.2 宁缺毋滥)。</li>
     *   <li><b>范围是一个模块一个科目</b>(决策记录 §5.4)。18 个考点的树里出现两个同名,
     *       是命名错误,不是合法场景。真要区分,应该起两个不同的名字
     *       (「增长率计算」vs「增长率速算」),而不是靠所在题型去区分 ——
     *       靠题型区分的名字,一旦 {@code moveNode} 换个题型就自相矛盾了。</li>
     * </ol>
     *
     * <h2>为什么把已归档的也算进来</h2>
     *
     * 否则 {@code unarchive} 会<b>静默造出一个重名</b>:归档时名字空出来、被新考点占掉,
     * 取消归档就凭空多一个同名的。把归档节点算进来之后,
     * <b>{@link #unarchiveNode} 就不需要再做一次检查</b> —— 一个归档考点的名字在它归档期间
     * 从来没被让出去过,所以接回来时必定还是唯一的。不变式在一处成立,到处成立。
     *
     * <p>同理 {@link #moveNode} 也不需要检查:唯一性是整棵树的,换个题型不可能造出新的冲突。
     *
     * @param selfCode 允许与自己同名的那个 code(重命名成自己原来的名字不算冲突);
     *                 新增时传 {@code null}
     */
    private static void requireNodeNameFree(Syllabus s, String name, String selfCode) {
        String key = SyllabusNames.nameKey(name);
        for (Syllabus.Group g : s.groups()) {
            for (Syllabus.Node n : g.nodes()) {          // 🔴 g.nodes() 含已归档的,activeNodes() 不行
                if (n.code().equals(selfCode) || !SyllabusNames.nameKey(n.name()).equals(key)) {
                    continue;
                }
                // 🔴 归档与否要分开说:归档的那个用户在树上根本看不见,不点破就是一句无解的报错
                throw n.archived()
                        ? SyllabusEditException.nodeNameTakenByArchived(name, n.name(), g.name())
                        : SyllabusEditException.nodeNameTaken(name, n.name(), g.name());
            }
        }
    }

    /**
     * 题型名同样整棵树唯一。
     *
     * <p>与考点名是<b>两个独立的命名空间</b>:一个题型叫「速算技巧」、一个考点也叫「速算技巧」
     * 不算冲突 —— 面板上挑的是考点,两者不会在同一个列表里并排出现。
     * 而两个同名的<b>题型</b>会让「整块空白」指向两个地方,那才是要防的。
     *
     * <p>题型没有归档这回事({@link Syllabus.Group} 上没有 archived),所以只有一种冲突。
     */
    private static void requireGroupNameFree(Syllabus s, String name, String selfCode) {
        String key = SyllabusNames.nameKey(name);
        for (Syllabus.Group g : s.groups()) {
            if (!g.code().equals(selfCode) && SyllabusNames.nameKey(g.name()).equals(key)) {
                throw SyllabusEditException.groupNameTaken(name, g.name());
            }
        }
    }

    private static int validCount(int recent5yCount) {
        if (recent5yCount < 0) {
            throw SyllabusEditException.invalidFrequency(recent5yCount);
        }
        return recent5yCount;
    }

    // ———————————————————————— 内部 ————————————————————————

    private Syllabus.Node mutateNode(String nodeCode, UnaryOperator<Syllabus.Node> change) {
        synchronized (lock) {
            ensureLoaded();
            Syllabus s = current;
            int gi = groupIndexOfNode(s, nodeCode);
            Syllabus.Group g = s.groups().get(gi);

            List<Syllabus.Node> nodes = new ArrayList<>(g.nodes());
            int ni = indexOfNode(nodes, nodeCode);
            Syllabus.Node updated = change.apply(nodes.get(ni));
            nodes.set(ni, updated);

            commit(withGroup(s, gi, new Syllabus.Group(g.code(), g.name(), List.copyOf(nodes))));
            return updated;
        }
    }

    private static Syllabus withGroup(Syllabus s, int index, Syllabus.Group replacement) {
        List<Syllabus.Group> groups = new ArrayList<>(s.groups());
        groups.set(index, replacement);
        return new Syllabus(s.subject(), List.copyOf(groups));
    }

    private static int groupIndex(Syllabus s, String groupCode) {
        for (int i = 0; i < s.groups().size(); i++) {
            if (s.groups().get(i).code().equals(groupCode)) {
                return i;
            }
        }
        throw SyllabusEditException.groupNotFound(groupCode);
    }

    private static int groupIndexOfNode(Syllabus s, String nodeCode) {
        for (int i = 0; i < s.groups().size(); i++) {
            if (indexOfNode(s.groups().get(i).nodes(), nodeCode) >= 0) {
                return i;
            }
        }
        throw SyllabusEditException.nodeNotFound(nodeCode);
    }

    private static int indexOfNode(List<Syllabus.Node> nodes, String nodeCode) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).code().equals(nodeCode)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 生成一个还没被用过的 code。
     *
     * <p>🔴 <b>不从名字派生</b>。派生等于把名字焊回 code,而 code 存在的全部理由就是
     * 「改名不断历史」({@link Syllabus} 的字段说明)。中文名派生只有两条路 ——
     * 直接拿中文当 code,或者转拼音把某一种措辞编码进主键 —— 两条都不走。
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

    /** 先落盘再改内存:写失败时内存与磁盘仍然一致,不会出现「界面上有、文件里没有」的考点。 */
    private void commit(Syllabus next) {
        writeAtomically(next);
        current = next;
    }

    // ———————————————————————— 载入与播种 ————————————————————————

    /**
     * 推迟到第一次访问才载入 —— <b>不用 {@code @PostConstruct}</b>。
     *
     * <p>与 {@code FileTouchStore} 同样的理由:构造 bean 是一件不该有副作用的事。
     * 启动一次 Spring 上下文就往 {@code ~/.kaodian} 里写文件,会让每一次跑测试都污染真实用户目录。
     */
    private void ensureLoaded() {
        if (current != null) {
            return;
        }
        if (Files.exists(file)) {
            current = read();
            return;
        }
        // 第一次跑:从 classpath 种子播种。先落盘再进内存,让「文件是唯一事实来源」从第一秒就成立。
        Syllabus seeded = readSeed();
        writeAtomically(seeded);
        current = seeded;
    }

    private Syllabus read() {
        try (InputStream in = Files.newInputStream(file)) {
            return SyllabusLoader.parse(MAPPER.readTree(in), file.toString());
        } catch (IOException e) {
            throw new IllegalStateException("骨架层数据文件读取失败:" + file, e);
        }
    }

    /**
     * 播种。
     *
     * <p>🔴 种子是<b>我们自己归纳的一棵树</b>,不是从任何机构的目录页拷来的
     * (R-07 / docs/decisions/实施路径.md §1.2)。播完之后它就是用户自己的树了 ——
     * 用户改名、增删、调序,种子文件再也不会覆盖它({@link #ensureLoaded} 只在文件不存在时播)。
     */
    private static Syllabus readSeed() {
        return SyllabusLoader.loadDefault();
    }

    // ———————————————————————— 写入 ————————————————————————

    /**
     * 一个考点 → 一个 JSON 对象。
     *
     * <p>🔴 这里逐字段写,<b>不是</b>把 {@link Syllabus.Node} 交给 Jackson 自动序列化。
     * 自动序列化会跟着 record 的形状走 —— 哪天有人给它加了个字段,
     * 它就会不声不响地流进用户的数据文件。逐字段写让「文件里能出现哪些键」
     * 是这段代码显式列出来的,加字段必须先过这里。
     */
    private static ObjectNode toNode(Syllabus.Node n) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("code", n.code());
        o.put("name", n.name());
        o.put("recent5yCount", n.recent5yCount());   // 统计事实,不是内容(docs/data/INDEX.md)
        if (n.archived()) {
            o.put("archived", true);                 // 没归档就不写这个键,文件更干净
        }
        return o;
    }

    /** 先写临时文件 → fsync → 原子 rename。中途断电最坏结果是这次编辑没发生,已有的树不会坏。 */
    private void writeAtomically(Syllabus syllabus) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode comment = root.putArray("_comment");
            comment.add("骨架层 —— 你自己维护的考点树:模块 → 题型 → 考点,三层。");
            comment.add("🔴 只有名称、层级、近五年频次。没有题干、没有解析、没有任何机构的课程内容。");
            comment.add("🔴 考点名自行归纳,不沿用任何机构既有体系与措辞(R-07 / docs/decisions/实施路径.md §1.2)。");
            comment.add("code 是主键:改名不动 code,所以改名不会断掉任何历史记录。");

            ObjectNode s = root.putObject("subject");
            s.put("code", syllabus.subject().code());
            s.put("region", syllabus.subject().region());
            s.put("exam", syllabus.subject().exam());
            s.put("module", syllabus.subject().module());
            s.put("recent5yWindow", syllabus.subject().recent5yWindow());

            ArrayNode groups = root.putArray("groups");
            for (Syllabus.Group g : syllabus.groups()) {
                ObjectNode go = groups.addObject();
                go.put("code", g.code());
                go.put("name", g.name());
                ArrayNode nodes = go.putArray("nodes");
                for (Syllabus.Node n : g.nodes()) {
                    nodes.add(toNode(n));
                }
            }

            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            Files.write(tmp, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            // 落到盘面再 rename。少了这一步,rename 是原子的但内容可能还在页缓存里。
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }

            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("骨架层写入失败:" + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);   // 失败路径上别留半截文件误导下一次
            } catch (IOException ignored) {
                // 清理失败不该盖掉真正的错误
            }
        }
    }
}
