package com.kaodian.server.syllabus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 骨架树 JSON 的<b>唯一解析器</b> —— classpath 上的种子和 {@code ~/.kaodian/syllabus.json}
 * 走的是同一段代码。
 *
 * <p>阶段 0/1 骨架层就是一个 JSON 文件 —— docs/technical/INDEX.md §零:「阶段 0 是本地文件夹 + 纯文本」,
 * 数据层落库最早也要到阶段 1 的 {@code 1.2.4}。现在没有数据库,也不需要。
 *
 * <h2>这个类<b>只解析名称、层级、频次、归档标记</b></h2>
 *
 * 文件里即便被塞进别的字段也不会被读出来 —— 与 {@link com.kaodian.server.collect.Touch}
 * 同一条思路:不给内容留位置。写回去的时候由 {@link FileSyllabusStore} 逐字段列举,
 * 于是手工塞进去的东西<b>读不进来,也写不回去</b>。
 *
 * <h2>🔴 认不出来就吵着失败,绝不当成一棵空树</h2>
 *
 * 这条教训是 {@code FileTouchStore} 用行为层数据换来的:{@code path("xxx")} 在缺键、
 * 键名写错、根节点类型不对时都只是安静地给回一个 MissingNode,于是「解析成功、0 条」——
 * 而下一次写入是<b>全量重写</b>,那棵空树会原样盖掉磁盘上真实的骨架。
 * <p>
 * 骨架层比行为层更经不起这个:<b>树没了,所有记录就都成了孤儿</b>,
 * 覆盖率的分母归零、分子归零,而覆盖率就是这个产品本身。
 * 所以下面每一处 {@code path(...)} 后面都跟着一次类型检查,
 * 并且重复的 code 也当场失败 —— 两个考点共用一个 code,记录就分不清挂在谁身上了。
 *
 * <h2>🔴 重复的<b>名字</b>同样当场失败</h2>
 *
 * 写入路径上有 {@code FileSyllabusStore#requireNodeNameFree} 守着,但那守不住
 * <b>已经躺在磁盘上的文件</b>:种子写错了、用户手工编辑过、或者是新增这条约束之前留下的树。
 * 带着两个同名考点启动,不变式从第一秒起就是假的 —— 前端按名字挑考点,
 * 记录会被劈到两个 code 上,而没有任何一处会报错。
 * <p>
 * 这和 {@code FileTouchStore} 那条「坏文件静默变成空树」是同一类问题:
 * <b>安静地带病运行,比响亮地起不来糟得多。</b>
 */
public final class SyllabusLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认种子:山东省考 · 行测 · 资料分析。也是 {@link FileSyllabusStore} 第一次启动时的播种来源。 */
    public static final String DEFAULT_SEED_RESOURCE = "/seed/syllabus-ziliao.json";

    private SyllabusLoader() {
    }

    /** 默认种子:山东省考 · 行测 · 资料分析。 */
    public static Syllabus loadDefault() {
        return load(DEFAULT_SEED_RESOURCE);
    }

    public static Syllabus load(String classpathResource) {
        try (InputStream in = SyllabusLoader.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new SyllabusDataException("找不到骨架种子文件:" + classpathResource);
            }
            return parse(MAPPER.readTree(in), classpathResource);
        } catch (IOException e) {
            throw new SyllabusDataException("骨架种子文件读取失败:" + classpathResource, e);
        }
    }

    /**
     * 一份骨架 JSON → 一棵树。<b>任何一处对不上就抛 {@link SyllabusDataException}。</b>
     *
     * @param origin 出错时指出是哪份文件。它是服务端自己的路径,不是用户送来的字符串
     */
    public static Syllabus parse(JsonNode root, String origin) {
        if (root == null || !root.isObject()) {
            throw broken(origin, "根节点不是一个 JSON 对象");
        }

        JsonNode s = root.path("subject");
        if (!s.isObject()) {
            throw broken(origin, "缺少 subject 对象 —— 这棵树是哪个省、哪门考试、哪个模块必须写明");
        }
        Syllabus.Subject subject = new Syllabus.Subject(
                requiredText(s, "code", origin),
                requiredText(s, "region", origin),
                requiredText(s, "exam", origin),
                requiredText(s, "module", origin),
                requiredText(s, "recent5yWindow", origin));

        JsonNode groupsNode = root.path("groups");
        if (!groupsNode.isArray()) {
            throw broken(origin, "缺少 groups 数组 —— 宁可在这里失败,也不能当成一棵空树,"
                    + "否则下一次写入会把磁盘上真实的骨架整个盖掉,所有记录一起变成孤儿");
        }

        Set<String> seenGroupCodes = new HashSet<>();
        Set<String> seenNodeCodes = new HashSet<>();
        // 规范化名 → 已经占着这个名字的是谁。整棵树一份,而且不分归档与否 —— 见 SyllabusNames
        Map<String, String> seenGroupNames = new HashMap<>();
        Map<String, String> seenNodeNames = new HashMap<>();
        List<Syllabus.Group> groups = new ArrayList<>();

        for (JsonNode g : groupsNode) {
            if (!g.isObject()) {
                throw broken(origin, "groups 里有一项不是对象");
            }
            String groupCode = requiredText(g, "code", origin);
            if (!seenGroupCodes.add(groupCode)) {
                throw broken(origin, "题型 code 重复:" + groupCode);
            }

            JsonNode nodesNode = g.path("nodes");
            if (!nodesNode.isArray()) {
                throw broken(origin, "题型 " + groupCode + " 缺少 nodes 数组");
            }

            List<Syllabus.Node> nodes = new ArrayList<>();
            for (JsonNode n : nodesNode) {
                if (!n.isObject()) {
                    throw broken(origin, "题型 " + groupCode + " 的 nodes 里有一项不是对象");
                }
                String nodeCode = requiredText(n, "code", origin);
                if (!seenNodeCodes.add(nodeCode)) {
                    // 🔴 两个考点共用一个 code,行为层就分不清记录挂在谁身上了 —— 覆盖率当场失真
                    throw broken(origin, "考点 code 重复:" + nodeCode
                            + " —— 记录是挂在 code 上的,重复的 code 会让记录归属不明");
                }
                // 先过 validName 再查重名:这两条是不同的线。
                // 唯一性防的是「两个考点看起来一样」;validName 防的是「名字字段里装的根本不是名字」——
                // 带换行、超长的「考点名」几乎只可能是有人把一段题干或讲义贴了进来(决策记录 §2.2 不碰内容)。
                // 原来只查了唯一性,于是手工改过的文件能把一整段题干当名字载进来。
                String nodeName = validNameOrBroken(
                        requiredText(n, "name", origin), origin, "考点 " + nodeCode);
                requireNameNotTaken(seenNodeNames, nodeName, nodeCode, origin, "考点");
                nodes.add(new Syllabus.Node(
                        nodeCode,
                        nodeName,
                        requiredCount(n, origin, nodeCode),
                        optionalBoolean(n, "archived", origin, nodeCode)));
            }
            String groupName = validNameOrBroken(
                    requiredText(g, "name", origin), origin, "题型 " + groupCode);
            requireNameNotTaken(seenGroupNames, groupName, groupCode, origin, "题型");
            groups.add(new Syllabus.Group(groupCode, groupName, List.copyOf(nodes)));
        }
        return new Syllabus(subject, List.copyOf(groups));
    }

    /**
     * 🔴 名字整棵树唯一 —— 载入时的那一半。
     *
     * <p>写入路径上的那一半在 {@code FileSyllabusStore#requireNodeNameFree},两处共用
     * {@link SyllabusNames#nameKey} 这一个口径。<b>两个口径就会有两种「同名」的定义</b>,
     * 于是写得进去的树,下次启动读不出来。
     *
     * <p>报错里给出两个 code,因为名字本身看起来可能一模一样(差别在空格、全角、大小写上)——
     * 只说名字的话,拿着文件的人根本不知道该改哪一行。
     *
     * @param seen 规范化名 → 先占着它的那个 code。<b>会被就地更新</b>
     */
    private static void requireNameNotTaken(Map<String, String> seen, String name, String code,
                                            String origin, String what) {
        String owner = seen.putIfAbsent(SyllabusNames.nameKey(name), code);
        if (owner != null) {
            throw broken(origin, what + "名重复:" + owner + " 与 " + code + " 都叫「" + name + "」"
                    + "(前后空格、内部多余空格、全角半角、英文大小写,以及看不见的码点,都不算区别)。"
                    + "前端是按名字挑" + what + "的,两个同名的" + what
                    + "用户分不出来,记录会被劈到两个 code 上,覆盖率跟着失真 —— "
                    + "所以宁可在这里起不来,也不带着一棵已经违反不变式的树运行。"
                    + "改掉其中一个的 name(code 不用动,记录挂在 code 上,改名不断历史)。");
        }
    }

    private static String requiredText(JsonNode owner, String field, String origin) {
        JsonNode v = owner.path(field);
        if (!v.isString() || v.asString("").isBlank()) {
            throw broken(origin, "缺少必填字段或类型不对:" + field);
        }
        return v.asString();
    }

    /**
     * 近五年频次。<b>必须存在、必须是整数、必须非负。</b>
     *
     * <p>缺省成 0 看起来更宽容,实际是把「这个考点近五年一次没考过」和
     * 「这份文件坏了」混成同一个值 —— 而前者会让它在盲区排序里直接沉底,没人会发现。
     */
    private static int requiredCount(JsonNode n, String origin, String nodeCode) {
        JsonNode v = n.path("recent5yCount");
        if (!v.isIntegralNumber()) {
            throw broken(origin, "考点 " + nodeCode + " 的 recent5yCount 必须是整数");
        }
        int count = v.asInt();
        if (count < 0) {
            throw broken(origin, "考点 " + nodeCode + " 的 recent5yCount 不能为负:" + count);
        }
        return count;
    }

    /** 归档标记。老文件里没有这个键,按「没归档」处理;但写着别的类型就是文件坏了。 */
    private static boolean optionalBoolean(JsonNode n, String field, String origin, String nodeCode) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return false;
        }
        if (!v.isBoolean()) {
            throw broken(origin, "考点 " + nodeCode + " 的 " + field + " 必须是布尔值");
        }
        return v.asBoolean();
    }


    /**
     * 把 {@link FileSyllabusStore#validName} 的拒绝翻译成「这份文件不合法」。
     *
     * <p>同一条规则在两个入口上生效:走 API 编辑时它是 400,从文件载入时它是「文件读不了」。
     * 两处共用一份实现,是为了避免出现「接口拒绝、文件却能载入」这种口子 ——
     * 而导出→手工改→放回,恰恰是官方给出的用法。
     */
    private static String validNameOrBroken(String raw, String origin, String who) {
        try {
            return FileSyllabusStore.validName(raw);
        } catch (SyllabusEditException e) {
            throw broken(origin, who + " 的名字不合法:" + e.getMessage());
        }
    }

    private static SyllabusDataException broken(String origin, String detail) {
        return new SyllabusDataException("骨架数据不合法(" + origin + "):" + detail);
    }
}
