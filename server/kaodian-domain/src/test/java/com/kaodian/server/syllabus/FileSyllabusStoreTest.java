package com.kaodian.server.syllabus;

import com.kaodian.server.collect.FileTouchStore;
import com.kaodian.server.collect.TouchLedger;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.coverage.CoverageService.Summary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 骨架层可写之后的红线测试。
 *
 * <p>四条被钉住的东西:<b>code 由服务端生成且不从名字派生</b>、
 * <b>改名不动 code(所以记录不丢)</b>、<b>有记录的考点删不掉</b>、
 * <b>坏文件响亮失败而不是退化成一棵空树</b>。
 *
 * <p>前两条与后两条其实是同一件事的两面:记录挂在 code 上。
 * 挂在 code 上,所以改名安全;也正因为挂在 code 上,所以删 code 会丢数据。
 */
class FileSyllabusStoreTest {

    @TempDir
    Path dataDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FileTouchStore touches;

    /** 骨架 store,背后接着一个真的行为层 —— 删除守则要数的就是那边的记录。 */
    private FileSyllabusStore store() {
        touches = new FileTouchStore(dataDir.resolve("touches.json"));
        return new FileSyllabusStore(dataDir.resolve("syllabus.json"), new TouchLedger(touches));
    }

    /** 不需要行为层的场景(纯树操作),给一个永远说「0 条」的账本。 */
    private FileSyllabusStore emptyLedgerStore() {
        return new FileSyllabusStore(dataDir.resolve("syllabus.json"), new NodeRecordLedger() {
            @Override
            public int countFor(String nodeCode) {
                return 0;
            }

            @Override
            public int moveAll(String from, String to) {
                return 0;
            }
        });
    }

    private Path file() {
        return dataDir.resolve("syllabus.json");
    }

    // ——————————————————— 种子与播种 ———————————————————

    @Test
    @DisplayName("首次访问自动播种,构造 bean 本身没有副作用")
    void seedsOnFirstAccess() {
        FileSyllabusStore store = emptyLedgerStore();
        assertFalse(Files.exists(file()), "构造 bean 不该有副作用 —— 播种推迟到第一次访问");

        Syllabus s = store.current();
        assertEquals(18, s.nodeCount(), "种子:18 个考点");
        assertEquals(5, s.groups().size(), "种子:5 个题型");
        assertEquals("山东省考 · 行测 · 资料分析", s.subject().display());
        assertTrue(Files.exists(file()), "播种后文件应当已经落盘");
        assertEquals(file().toAbsolutePath(), store.dataFile());
    }

    @Test
    @DisplayName("🔴 骨架种子里只有名称/层级/频次 —— 逐个键检查,不允许出现任何装内容的位置")
    void seedCarriesNoCourseContent() {
        Set<String> allowedOnNode = Set.of("code", "name", "recent5yCount", "archived");
        Set<String> forbidden = Set.of("content", "text", "body", "question", "stem", "transcript",
                "answer", "explanation", "solution", "note", "imageUrl", "image", "url", "sample");

        JsonNode root = readSeedResource();
        int nodeCount = 0;
        for (JsonNode g : root.path("groups")) {
            for (String key : g.propertyNames()) {
                assertFalse(forbidden.contains(key), "题型上出现了禁止的键:" + key);
            }
            for (JsonNode n : g.path("nodes")) {
                nodeCount++;
                for (String key : n.propertyNames()) {
                    assertTrue(allowedOnNode.contains(key),
                            "考点上出现了契约之外的键(决策记录 §2.2 不碰内容):" + key);
                }
            }
        }
        assertEquals(18, nodeCount, "种子必须正好 18 个考点 —— 覆盖率 8/18 = 44% 的分母靠它");
    }

    @Test
    @DisplayName("已经存在的数据文件不会被种子覆盖 —— 播种只发生一次,用户改过的树不会被打回原样")
    void existingFileIsNeverReseeded() throws Exception {
        FileSyllabusStore first = emptyLedgerStore();
        Syllabus.Node created = first.addNode("growth", "自己加的考点", 1);

        FileSyllabusStore second = emptyLedgerStore();
        assertEquals(19, second.current().nodeCount(), "文件已存在就照读,种子不再插手");
        assertNotNull(second.current().node(created.code()));
    }

    // ——————————————————— 🔴 坏文件响亮失败 ———————————————————

    @Test
    @DisplayName("坏文件要吵着失败,不能悄悄当成一棵空树")
    void corruptFileFailsLoudly() throws Exception {
        Files.createDirectories(dataDir);
        Files.writeString(file(), "{ 这不是 JSON", StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> emptyLedgerStore().current());
    }

    @Test
    @DisplayName("🔴 groups 键没了不能当成 0 个题型 —— 否则下一次编辑会把整棵骨架盖掉,所有记录一起变孤儿")
    void unrecognisableFileNeverDegradesToEmpty() throws Exception {
        Files.createDirectories(dataDir);
        // 一份合法 JSON,但结构不认识(手工改坏、或换了个键名)。
        // 松散写法在这里「解析成功、0 个题型」,再编辑一次就把真实的骨架全量重写没了。
        Files.writeString(file(), """
                {
                  "subject": { "code": "sd-xingce-ziliao", "region": "山东省考", "exam": "行测",
                               "module": "资料分析", "recent5yWindow": "2021-2025" },
                  "categories": [ { "code": "growth", "name": "增长类", "nodes": [] } ]
                }
                """, StandardCharsets.UTF_8);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> emptyLedgerStore().current(), "缺 groups 数组必须炸,不能静默当成空树");
        assertTrue(e.getMessage().contains("groups"), "报错要指出缺的是什么");
        assertTrue(Files.readString(file(), StandardCharsets.UTF_8).contains("categories"),
                "失败之后原文件必须一个字都没被动过");
    }

    @Test
    @DisplayName("🔴 重复的考点 code 必须当场失败 —— 记录挂 code,重复就意味着归属不明")
    void duplicateNodeCodeFailsLoudly() throws Exception {
        writeTree("""
                { "code": "growth", "name": "增长类", "nodes": [
                    { "code": "growth-rate", "name": "增长率计算", "recent5yCount": 9 },
                    { "code": "growth-rate", "name": "增长率(重复)", "recent5yCount": 3 } ] }
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> emptyLedgerStore().current());
        assertTrue(e.getMessage().contains("growth-rate"), "要说出是哪个 code 重复了");
    }

    @Test
    @DisplayName("频次缺失或为负都是坏文件 —— 缺省成 0 会把「没考过」和「文件坏了」混成一个值")
    void frequencyMustBePresentAndNonNegative() throws Exception {
        writeTree("""
                { "code": "growth", "name": "增长类", "nodes": [
                    { "code": "growth-rate", "name": "增长率计算" } ] }
                """);
        assertThrows(IllegalStateException.class, () -> emptyLedgerStore().current());

        writeTree("""
                { "code": "growth", "name": "增长类", "nodes": [
                    { "code": "growth-rate", "name": "增长率计算", "recent5yCount": -1 } ] }
                """);
        assertThrows(IllegalStateException.class, () -> emptyLedgerStore().current());
    }

    @Test
    @DisplayName("🔴 有人手工往骨架文件里塞讲解也没用 —— 读取只认那四个键,进不了内存也回不到文件")
    void handEditedContentIsNeverRead() throws Exception {
        writeTree("""
                { "code": "growth", "name": "增长类", "nodes": [
                    { "code": "growth-rate", "name": "增长率计算", "recent5yCount": 9,
                      "explanation": "增长率 = 增长量 ÷ 基期量,先约分再截位",
                      "sample": "2023 年全国粮食产量为..." } ] }
                """);

        FileSyllabusStore store = emptyLedgerStore();
        assertEquals(1, store.current().nodeCount());
        assertEquals("增长率计算", store.current().node("growth-rate").name());

        // 触发一次全量重写:塞进去的内容不会被写回去,它到此为止
        store.renameNode("growth-rate", "增长率");
        String written = Files.readString(file(), StandardCharsets.UTF_8);
        assertFalse(written.contains("explanation"), "重写后文件里不该再有讲解字段");
        assertFalse(written.contains("先约分再截位"));
        assertFalse(written.contains("粮食产量"));
    }

    // ——————————————————— 🔴 code 由服务端生成 ———————————————————

    @Test
    @DisplayName("🔴 新增考点的 code 由服务端生成,而且不从中文名派生")
    void generatedCodeIsServerSideAndNotDerivedFromName() {
        FileSyllabusStore store = emptyLedgerStore();
        Syllabus.Node created = store.addNode("growth", "复合增长率", 3);

        assertTrue(created.code().startsWith("n-"), "服务端生成的考点 code 有固定前缀:" + created.code());
        assertFalse(created.code().contains("复合"), "🔴 不许拿中文名当 code");
        assertFalse(created.code().contains("增长"), "🔴 也不许从名字派生 —— 派生等于把名字焊回主键");
        for (int i = 0; i < created.code().length(); i++) {
            assertTrue(created.code().charAt(i) < 128, "code 必须是可搬运的 ASCII:" + created.code());
        }

        // 名字必须唯一(见 nameIsUniqueAcrossTheWholeTree),所以这里换一个名字再来一次。
        // 要钉的是「code 与名字之间没有任何函数关系」:名字相近,code 也必须毫不相干。
        Syllabus.Node second = store.addNode("growth", "复合增长率(年均)", 3);
        assertNotEquals(created.code(), second.code(), "两个考点拿到的 code 必须不同");
        assertEquals("复合增长率", store.current().node(created.code()).name());
    }

    @Test
    @DisplayName("🔴 接口签名上就没有让客户端指定 code 的位置")
    void thereIsNoWayForACallerToChooseACode() throws Exception {
        Method addNode = SyllabusStore.class.getMethod("addNode", String.class, String.class, int.class);
        assertEquals(3, addNode.getParameterCount(),
                "addNode 只收 (groupCode, name, recent5yCount) —— 多一个参数就是把主键交出去了");

        Method addGroup = SyllabusStore.class.getMethod("addGroup", String.class);
        assertEquals(1, addGroup.getParameterCount(), "addGroup 只收 name");

        for (Method m : SyllabusStore.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            assertFalse(name.contains("import") || name.contains("bulk") || name.contains("batch"),
                    "🔴 不许出现批量导入考点体系的方法(R-07 / docs/decisions/实施路径.md §1.2):" + m.getName());
        }
    }

    // ——————————————————— 🔴 改名不动 code ———————————————————

    @Test
    @DisplayName("🔴 重命名只改 name:code 不变,记录一条不少,覆盖率一个数不动")
    void renameKeepsCodeAndAllRecords() {
        FileSyllabusStore store = store();
        Summary before = summarize(store);

        Syllabus.Node renamed = store.renameNode("growth-rate", "增长率(我自己的说法)");

        assertEquals("growth-rate", renamed.code(), "🔴 改名绝不改 code");
        assertEquals("增长率(我自己的说法)", renamed.name());
        assertEquals(1, touches.findByNode("growth-rate").size(), "记录挂在 code 上,改名之后还在原处");

        Summary after = summarize(store);
        assertEquals(before.total(), after.total(), "分母不动");
        assertEquals(before.covered(), after.covered(), "分子不动");
        assertEquals(before.percent(), after.percent(),
                "🔴 改名之后覆盖率必须逐字不变 —— 这就是当初用 code 而不是中文名做主键的全部理由");
    }

    @Test
    @DisplayName("移动考点到另一个题型:code 不变,记录不动,只换了归属")
    void movingANodeKeepsItsCodeAndRecords() {
        FileSyllabusStore store = store();
        store.moveNode("growth-rate", "fast-math");

        assertEquals("fast-math", store.current().groupOf("growth-rate").code());
        assertEquals(1, touches.findByNode("growth-rate").size());
        assertEquals(18, summarize(store).total(), "总数不变 —— 只是换了个题型");
    }

    // ——————————————————— 🔴 删除守则 ———————————————————

    @Test
    @DisplayName("🔴 有记录的考点不允许删除,报错要说出有几条,而且树一个字都没动")
    void deletingANodeWithRecordsIsRefused() {
        FileSyllabusStore store = store();
        assertEquals(1, store.recordCount("growth-rate"));

        SyllabusEditException e = assertThrows(SyllabusEditException.class,
                () -> store.deleteNode("growth-rate"));

        assertEquals(SyllabusEditException.Reason.NODE_HAS_RECORDS, e.reason());
        assertEquals(1, e.count(), "界面要说得出具体数字,不能只说「删不掉」");
        assertTrue(e.getMessage().contains("1 条记录"));
        assertTrue(e.getMessage().contains("归档"), "必须给出正确出路,否则用户只会去找更硬的删法");

        assertNotNull(store.current().node("growth-rate"), "被拒之后考点还在");
        assertEquals(18, summarize(store).total());
        assertEquals(1, touches.findByNode("growth-rate").size(), "记录一条都没少");
    }

    @Test
    @DisplayName("🔴 出路一:把记录搬到别的考点之后才能删。记录总数不变,时间戳不重置")
    void movingRecordsAwayIsTheWayToDelete() {
        FileSyllabusStore store = store();
        Instant originalAt = touches.findByNode("growth-rate").get(0).occurredAt();
        int totalBefore = touches.count();

        int moved = store.moveRecords("growth-rate", "average-calc");
        assertEquals(1, moved);
        assertEquals(0, store.recordCount("growth-rate"));
        assertEquals(1, store.recordCount("average-calc"));
        assertEquals(totalBefore, touches.count(), "🔴 搬家不扔东西 —— 记录总数必须不变");
        assertEquals(originalAt, touches.findByNode("average-calc").get(0).occurredAt(),
                "时间戳不能重置 —— 「多久前」是这个产品仅有的三个维度之一");
        assertEquals(12, touches.findByNode("average-calc").get(0).drill().practiced(),
                "做题数原样带过去");

        store.deleteNode("growth-rate");
        assertNull(store.current().node("growth-rate"));
        assertEquals(17, summarize(store).total(), "删掉一个空考点,分母 −1");
        assertEquals(8, summarize(store).covered(), "有记录的考点数不变 —— 记录只是换了个家");
    }

    /**
     * 四种拒绝要<b>分得开</b>,因为界面上该说的下一步各不相同。
     *
     * <p>尤其是「目标已归档」不能退化成 {@code NODE_NOT_FOUND}:那个考点<b>在</b>树里,
     * {@code GET /api/v1/syllabus/archived} 刚把它连名字带记录条数列出来过,
     * 紧接着回一句「骨架树里没有这个考点」是当场自相矛盾,而且把用户支到
     * 「刷新一下」那条死路上 —— 真正的下一步是取消归档,或者换一个没归档的考点。
     */
    @Test
    @DisplayName("🔴 搬记录的四种拒绝各是各的:来源不存在 / 原地搬 / 目标不存在 / 目标已归档")
    void recordsCannotBeMovedIntoNowhere() {
        FileSyllabusStore store = store();
        store.archiveNode("average-calc");

        assertEquals(SyllabusEditException.Reason.NODE_NOT_FOUND,
                assertThrows(SyllabusEditException.class,
                        () -> store.moveRecords("我自己想的考点", "growth-rate")).reason(),
                "来源不在树里");
        assertEquals(SyllabusEditException.Reason.SAME_NODE,
                assertThrows(SyllabusEditException.class,
                        () -> store.moveRecords("growth-rate", "growth-rate")).reason(),
                "原地搬");
        assertEquals(SyllabusEditException.Reason.NODE_NOT_FOUND,
                assertThrows(SyllabusEditException.class,
                        () -> store.moveRecords("growth-rate", "我自己想的考点")).reason(),
                "目标不在树里");

        // 🔴 目标在树里、只是归档了 —— 这一条如果也回 NODE_NOT_FOUND,前端就没法说对下一步
        SyllabusEditException archived = assertThrows(SyllabusEditException.class,
                () -> store.moveRecords("growth-rate", "average-calc"));
        assertEquals(SyllabusEditException.Reason.NODE_ARCHIVED, archived.reason(),
                "目标已归档 ≠ 目标不存在:归档清单里明明还看得见它");
        assertTrue(archived.getMessage().contains("unarchive"),
                "拒绝的同时必须点名出路,否则用户只会去找一个更硬的搬法:" + archived.getMessage());

        // 来源本身可以是归档的 —— 「先把记录搬走,再真正删掉」正是归档清单那一屏的用途
        store.archiveNode("share-calc");                 // 这个考点上有 1 条记录
        assertEquals(1, store.moveRecords("share-calc", "growth-rate"),
                "归档考点上的记录必须搬得走,否则它就成了看不见又删不掉的东西");

        assertEquals(2, touches.findByNode("growth-rate").size(), "四次拒绝一条没丢,最后一次搬成了");
        assertEquals(0, touches.findByNode("share-calc").size());
    }

    @Test
    @DisplayName("🔴 出路二:归档 —— 退出差集,但 code 和记录一条都没动,还能接回来")
    void archivingRetiresANodeWithoutLosingAnything() {
        FileSyllabusStore store = store();

        store.archiveNode("growth-rate");
        Summary archived = summarize(store);
        assertEquals(17, archived.total(), "分母 −1");
        assertEquals(7, archived.covered(), "分子也 −1 —— 比值仍然诚实");
        assertEquals(1, touches.findByNode("growth-rate").size(), "🔴 记录一条都没动");
        assertNull(store.current().node("growth-rate"), "退出差集");
        assertNotNull(store.current().nodeIncludingArchived("growth-rate"),
                "但 code 还在 —— 时间线上的老记录仍然认得出名字");

        store.unarchiveNode("growth-rate");
        Summary back = summarize(store);
        assertEquals(18, back.total());
        assertEquals(8, back.covered());
        assertEquals(44, back.percent(), "接回来之后一切照旧");
    }

    @Test
    @DisplayName("归档的考点删起来同样要过删除守则 —— 归档不是「删了一半」")
    void archivedNodeStillObeysTheDeleteRule() {
        FileSyllabusStore store = store();
        store.archiveNode("growth-rate");

        assertEquals(SyllabusEditException.Reason.NODE_HAS_RECORDS,
                assertThrows(SyllabusEditException.class, () -> store.deleteNode("growth-rate")).reason());

        store.moveRecords("growth-rate", "average-calc");
        store.deleteNode("growth-rate");
        assertNull(store.current().nodeIncludingArchived("growth-rate"));
    }

    @Test
    @DisplayName("没有记录的考点可以直接删,分母跟着 −1")
    void emptyNodeCanBeDeleted() {
        FileSyllabusStore store = store();
        assertEquals(0, store.recordCount("average-calc"));

        store.deleteNode("average-calc");
        assertEquals(17, summarize(store).total());
        assertEquals(8, summarize(store).covered());
    }

    @Test
    @DisplayName("🔴 题型下面还有考点(含已归档)就不许删 —— 连带删除是「删考点丢数据」的放大版")
    void groupWithNodesCannotBeDeleted() {
        FileSyllabusStore store = store();

        SyllabusEditException e = assertThrows(SyllabusEditException.class,
                () -> store.deleteGroup("effect"));
        assertEquals(SyllabusEditException.Reason.GROUP_NOT_EMPTY, e.reason());
        assertEquals(2, e.count());

        store.archiveNode("contribution-rate");
        assertEquals(SyllabusEditException.Reason.GROUP_NOT_EMPTY,
                assertThrows(SyllabusEditException.class, () -> store.deleteGroup("effect")).reason(),
                "归档的考点上照样挂着记录的可能,所以它也算数");

        store.deleteNode("contribution-rate");
        store.deleteNode("pull-growth");
        store.deleteGroup("effect");
        assertEquals(4, store.current().groups().size());
    }

    // ——————————————————— 顺序 ———————————————————

    @Test
    @DisplayName("🔴 树序改得动「先补这几个」的名次 —— 所以它必须能改,而且要落盘")
    void treeOrderDecidesTiesInBlindSpots() {
        FileSyllabusStore store = store();
        CoverageService service = new CoverageService();

        List<String> before = service.blindSpots(
                        service.compute(store.current(), touches.findAll(), Instant.now()), 5)
                .stream().map(CoverageService.NodeCoverage::name).toList();
        assertEquals(List.of("增长量计算", "平均数计算", "截位直除", "现期量计算", "倍数计算"), before);

        // 把「倍数与比较」提到「增长类」前面 —— 5.0 分并列的那两个应当换位
        store.reorderGroups(List.of("multiple", "growth", "effect", "average-share", "fast-math"));

        List<String> after = service.blindSpots(
                        service.compute(store.current(), touches.findAll(), Instant.now()), 5)
                .stream().map(CoverageService.NodeCoverage::name).toList();
        assertEquals(List.of("增长量计算", "平均数计算", "截位直除", "倍数计算", "现期量计算"), after);

        assertEquals("multiple", emptyLedgerStore().current().groups().get(0).code(),
                "顺序要落盘,换个实例读出来还是新的排布");
    }

    @Test
    @DisplayName("🔴 顺序必须是完整排列:少一个就整体拒绝,不做「剩下的补在后面」这种补救")
    void reorderMustBeAFullPermutation() {
        FileSyllabusStore store = emptyLedgerStore();

        assertEquals(SyllabusEditException.Reason.ORDER_NOT_A_PERMUTATION,
                assertThrows(SyllabusEditException.class,
                        () -> store.reorderGroups(List.of("multiple", "growth"))).reason(),
                "少给一个和「想把它排到最后」在字节上没区别 —— 前者会让一个题型悄悄换位置");

        assertEquals(SyllabusEditException.Reason.ORDER_NOT_A_PERMUTATION,
                assertThrows(SyllabusEditException.class,
                        () -> store.reorderGroups(List.of("growth", "growth", "multiple",
                                "effect", "average-share", "fast-math"))).reason(),
                "重复的 code 同样拒绝");

        assertEquals("growth", store.current().groups().get(0).code(), "被拒之后顺序没变");
    }

    @Test
    @DisplayName("组内调序:归档的考点不参与排序,重排后沉到末尾")
    void reorderNodesIgnoresArchivedOnes() {
        FileSyllabusStore store = emptyLedgerStore();
        store.archiveNode("multiple-change");

        store.reorderNodes("multiple", List.of("yoy-mom", "multiple-calc"));

        Syllabus.Group g = store.current().group("multiple");
        assertEquals(List.of("yoy-mom", "multiple-calc"),
                g.activeNodes().stream().map(Syllabus.Node::code).toList());
        assertEquals(List.of("yoy-mom", "multiple-calc", "multiple-change"),
                g.nodes().stream().map(Syllabus.Node::code).toList(), "归档的沉到末尾");
    }

    // ——————————————————— 名字与形状 ———————————————————

    @Test
    @DisplayName("🔴 名字有长度上限、不许带换行 —— 挡住把一段题干贴进「考点名」")
    void nameRulesBlockContentSmuggling() {
        FileSyllabusStore store = emptyLedgerStore();

        assertEquals(SyllabusEditException.Reason.INVALID_NAME,
                assertThrows(SyllabusEditException.class,
                        () -> store.addNode("growth", "题".repeat(41), 1)).reason());
        assertEquals(SyllabusEditException.Reason.INVALID_NAME,
                assertThrows(SyllabusEditException.class,
                        () -> store.addNode("growth", "增长率\n2023 年全国粮食总产量为 13908 亿斤", 1)).reason(),
                "带换行的「考点名」通常意味着贴进来的是一段内容");
        assertEquals(SyllabusEditException.Reason.INVALID_NAME,
                assertThrows(SyllabusEditException.class,
                        () -> store.renameNode("growth-rate", "   ")).reason());

        assertEquals(18, store.current().nodeCount(), "三次都被拒,树没变");
        assertEquals("增长率计算", store.current().node("growth-rate").name());
    }

    // ——————————————————— 🔴 名字整棵树唯一 ———————————————————

    /**
     * 规范化口径是<b>单独一个纯函数</b>({@link SyllabusNames#nameKey}),不埋在 store 的私有方法里 ——
     * 因为写入路径(查重)和载入路径(启动校验)都要用它,两个口径就会有两种「同名」的定义,
     * 于是写得进去的树,下次启动读不出来。
     */
    @Test
    @DisplayName("🔴 规范化口径:前后空格 / 内部多空格 / 全角半角 / 大小写,都不构成区别")
    void nameKeyCollapsesTheDifferencesThatTheEyeCannotSee() {
        String base = SyllabusNames.nameKey("增长量计算");

        assertEquals(base, SyllabusNames.nameKey("  增长量计算  "), "前后空格");
        assertEquals(base, SyllabusNames.nameKey("\u3000增长量计算\u3000"), "前后全角空格");
        assertEquals(SyllabusNames.nameKey("增长量 计算"), SyllabusNames.nameKey("增长量   计算"),
                "内部连续空白折叠成一个");
        assertEquals(SyllabusNames.nameKey("GDP 增长率"), SyllabusNames.nameKey("ＧＤＰ 增长率"),
                "全角与半角");
        assertEquals(SyllabusNames.nameKey("GDP 增长率"), SyllabusNames.nameKey("gdp 增长率"),
                "英文大小写");
        assertEquals(SyllabusNames.nameKey("GDP 增长率"), SyllabusNames.nameKey("\u00a0gdp\u00a0增长率"),
                "不换行空格 strip 不掉,得靠 NFKC 折成普通空格再折叠");

        assertNotEquals(base, SyllabusNames.nameKey("增长率计算"), "不同的名字不能被折成同一个");
        assertNotEquals(base, SyllabusNames.nameKey("增长量速算"));
    }

    /**
     * 🔴 唯一性的范围是<b>整棵树</b>,不是「同题型内唯一」。
     *
     * <p>理由不是洁癖:前端是按<b>名字</b>从命令面板挑考点的,面板上不显示题型 ——
     * 跨题型同名和同题型同名一样分不出来,记录会被劈到两个语义相同的 code 上,
     * 覆盖率的分子被稀释。范围又是一个模块一个科目(决策记录 §5.4),
     * 18 个考点的树里冒出两个同名,那是命名错误,不是合法场景。
     */
    @Test
    @DisplayName("🔴 考点名整棵树唯一:同题型、跨题型、空格/全角/大小写变体,一律 NAME_TAKEN")
    void nameIsUniqueAcrossTheWholeTree() {
        FileSyllabusStore store = emptyLedgerStore();

        // 同题型内
        SyllabusEditException same = assertThrows(SyllabusEditException.class,
                () -> store.addNode("growth", "增长量计算", 0));
        assertEquals(SyllabusEditException.Reason.NAME_TAKEN, same.reason());
        assertTrue(same.getMessage().contains("增长类"), "要说出冲突对象在哪个题型下:" + same.getMessage());

        // 🔴 跨题型 —— 本次规则的关键分歧点
        SyllabusEditException cross = assertThrows(SyllabusEditException.class,
                () -> store.addNode("effect", "增长量计算", 0));
        assertEquals(SyllabusEditException.Reason.NAME_TAKEN, cross.reason(),
                "跨题型同名一样分不出来 —— 面板上不显示题型");
        assertTrue(cross.getMessage().contains("增长类"),
                "冲突对象在别的题型下,更要说出它在哪,否则用户盯着自己这个题型只会觉得莫名其妙");

        // 前后空白(含全角空格)不构成区别
        for (String variant : List.of("  增长量计算  ", "\u3000增长量计算\u3000")) {
            assertEquals(SyllabusEditException.Reason.NAME_TAKEN,
                    assertThrows(SyllabusEditException.class,
                            () -> store.addNode("growth", variant, 0)).reason(),
                    "前后空白变体照样是同一个名字:" + variant);
        }

        // 内部连续空白折叠成一个。🔴 折叠的是【多个空格】,不是删掉空格 ——
        // 「增长量计算」与「增长量 计算」仍然是两个不同的名字,那个空格是看得见的
        store.addNode("growth", "增长量 速算", 1);
        for (String variant : List.of("增长量   速算", "  增长量  速算  ")) {
            assertEquals(SyllabusEditException.Reason.NAME_TAKEN,
                    assertThrows(SyllabusEditException.class,
                            () -> store.addNode("effect", variant, 0)).reason(),
                    "内部多空格折叠之后是同一个名字:" + variant);
        }

        store.addNode("fast-math", "GDP 速算", 1);
        for (String variant : List.of("ＧＤＰ 速算", "gdp 速算", "Gdp\u3000速算")) {
            assertEquals(SyllabusEditException.Reason.NAME_TAKEN,
                    assertThrows(SyllabusEditException.class,
                            () -> store.addNode("growth", variant, 0)).reason(),
                    "全角/大小写变体照样是同一个名字:" + variant);
        }

        assertEquals(20, store.current().nodeCount(), "只成功了两次 —— 被拒的那些一个都没落到树上");
    }

    /**
     * 🔴 最容易让人困惑的一种冲突:占着名字的那个考点<b>已归档</b>,用户在树上根本看不见它。
     *
     * <p>所以报错必须显式说「被一个已归档的考点占着」并给出出路。
     * 只说「名字重复」的话,用户看到的是「这个名字明明没人用,却说被占了」——
     * 下一步只会是换个近义词硬凑一个,而那正是这条规则想防的事。
     */
    @Test
    @DisplayName("🔴 与【已归档】考点重名同样 NAME_TAKEN,而且报错里必须出现「归档」和出路")
    void archivedNodesStillHoldTheirNames() {
        FileSyllabusStore store = emptyLedgerStore();
        store.archiveNode("growth-amount");                 // 「增长量计算」退出差集,但名字没让出去
        assertNull(store.current().node("growth-amount"), "树上确实看不见它了");

        SyllabusEditException e = assertThrows(SyllabusEditException.class,
                () -> store.addNode("effect", "增长量计算", 0));
        assertEquals(SyllabusEditException.Reason.NAME_TAKEN, e.reason());
        assertTrue(e.getMessage().contains("归档"),
                "🔴 不点破「它已经归档了」,这条报错在用户那边就是无解的:" + e.getMessage());
        assertTrue(e.getMessage().contains("rename") && e.getMessage().contains("unarchive"),
                "必须给出两条出路,否则用户只会换个近义词硬凑一个:" + e.getMessage());

        // 🔴 正因为归档期间名字没被让出去,unarchive 不需要再查一次重名 —— 不变式在一处成立,到处成立
        store.unarchiveNode("growth-amount");
        assertEquals("增长量计算", store.current().node("growth-amount").name());
        assertEquals(18, store.current().nodeCount());
    }

    @Test
    @DisplayName("重命名成自己原来的名字(以及只差空格的写法)必须放行 —— 那是界面上最常见的一次确定")
    void renamingANodeToItsOwnNameIsAllowed() {
        FileSyllabusStore store = emptyLedgerStore();

        assertEquals("增长率计算", store.renameNode("growth-rate", "增长率计算").name());
        assertEquals("增长率计算", store.renameNode("growth-rate", "  增长率计算  ").name(),
                "规范化之后就是自己,不算冲突");
        assertEquals("增长率 计算", store.renameNode("growth-rate", "增长率 计算").name());

        // 改成别人的名字才是冲突
        assertEquals(SyllabusEditException.Reason.NAME_TAKEN,
                assertThrows(SyllabusEditException.class,
                        () -> store.renameNode("growth-rate", "基期量计算")).reason());
        assertEquals("增长率 计算", store.current().node("growth-rate").name(), "被拒之后名字没变");

        // 题型同理
        assertEquals("增长类", store.renameGroup("growth", "增长类").name());
        assertEquals(SyllabusEditException.Reason.NAME_TAKEN,
                assertThrows(SyllabusEditException.class,
                        () -> store.renameGroup("growth", "效应类")).reason());
    }

    @Test
    @DisplayName("🔴 题型名同样整棵树唯一;题型与考点是两个独立的命名空间")
    void groupNamesAreUniqueToo() {
        FileSyllabusStore store = emptyLedgerStore();

        assertEquals(SyllabusEditException.Reason.NAME_TAKEN,
                assertThrows(SyllabusEditException.class, () -> store.addGroup("效应类")).reason());
        assertEquals(SyllabusEditException.Reason.NAME_TAKEN,
                assertThrows(SyllabusEditException.class, () -> store.addGroup(" 效应类 ")).reason());
        assertEquals(5, store.current().groups().size(), "两次都被拒,题型数没变");

        // 题型叫「增长量计算」不与同名考点冲突:面板上挑的是考点,两者不会并排出现
        assertNotNull(store.addGroup("增长量计算"));
        assertEquals(6, store.current().groups().size());
    }

    /**
     * 零宽字符<b>拒绝,不是规范化掉</b> —— 悄悄删字符等于替用户改名字,而他永远不会知道。
     * 而且它是 400 {@code INVALID_NAME},不是 409:问题出在这个名字本身,不是「有人先占了」。
     */
    @Test
    @DisplayName("🔴 零宽字符直接拒绝(INVALID_NAME,不是 NAME_TAKEN),常见中英文标点一个都别误伤")
    void zeroWidthCharactersAreRejectedRatherThanNormalisedAway() {
        FileSyllabusStore store = emptyLedgerStore();

        // 逐个写成转义,源码里绝不放一个看不见的字符 —— 那正是这条规则要防的东西
        List<String> invisibles = List.of(
                "\u200b",   // 零宽空格
                "\u200c",   // 零宽非连接符
                "\u200d",   // 零宽连接符
                "\u2060",   // word joiner
                "\ufeff",   // BOM / 零宽不换行空格
                "\u00ad");  // 软连字符
        for (String invisible : invisibles) {
            SyllabusEditException e = assertThrows(SyllabusEditException.class,
                    () -> store.addNode("growth", invisible + "增长量计算", 0),
                    "零宽字符必须被拒:U+" + Integer.toHexString(invisible.codePointAt(0)));
            assertEquals(SyllabusEditException.Reason.INVALID_NAME, e.reason(),
                    "🔴 是 400 不是 409 —— 问题在这个名字本身,不是「有人先占了」");
            assertTrue(e.getMessage().contains("零宽"), "错误信息要说得清:" + e.getMessage());
        }

        // 🔴 别误伤:中文、英文、数字、常见标点都要能过
        List<String> legal = List.of(
                "增长率计算(逆向)", "GDP compound rate", "2021-2025 年均值",
                "速算:截位直除法", "A/B 对比,含 %", "题型 #3 —— 特殊情形", "比重·变化");
        for (String name : legal) {
            assertNotNull(store.addNode("growth", name, 0), "正常名字被误伤了:" + name);
        }
        assertEquals(18 + legal.size(), store.current().nodeCount());
    }

    /**
     * 🔴 「看不见的字符」<b>不等于 Unicode 类别 Cf</b>。
     *
     * <p>这条测试是一次实打实的绕过换来的:早先的判定是
     * {@code Character.getType(cp) == Character.FORMAT},下面这一批全都不是 Cf,
     * 于是「增长量计算」后面缀一个就凭空多出一个考点,渲染出来一模一样 ——
     * 正是整条约束要防的东西。它们的类别分别是 Mn(组合符)、Lo(<b>字母</b>)、So(符号),
     * 光看类别名根本想不到它们是隐形的。
     */
    @Test
    @DisplayName("🔴 不可见字符不止 Cf 一类:填充符(Lo)、盲文空点(So)、组合字素连接符(Mn)照样拒绝")
    void invisibleCharactersOutsideTheCfCategoryAreRejectedToo() {
        FileSyllabusStore store = emptyLedgerStore();

        // 🔴 逐个写成转义。源码里绝不放一个看不见的字符 —— 那正是这条规则要防的东西
        List<String> invisibles = List.of(
                "\u034F",   // 组合字素连接符 CGJ                  —— Mn
                "\u115F",   // 谚文初声填充符                       —— Lo
                "\u1160",   // 谚文中声填充符                       —— Lo
                "\u17B5",   // 高棉固有元音 AA                     —— Mn
                "\u180B",   // 蒙古自由变体选择符 1                  —— Mn
                "\u2800",   // 盲文空点,任何字体里都是一格空白        —— So
                "\u3164",   // 谚文填充符,「隐形字符生成器」的主力     —— Lo
                "\uFFA0");  // 半角谚文填充符                       —— Lo
        for (String invisible : invisibles) {
            SyllabusEditException e = assertThrows(SyllabusEditException.class,
                    () -> store.addNode("growth", "增长量计算" + invisible, 0),
                    "不可见字符必须被拒:U+" + Integer.toHexString(invisible.codePointAt(0)));
            assertEquals(SyllabusEditException.Reason.INVALID_NAME, e.reason(),
                    "🔴 是 400 不是 409 —— 问题在这个名字本身");
            assertTrue(e.getMessage().contains("看不见"), "错误信息要说得清:" + e.getMessage());
        }
        assertEquals(18, store.current().nodeCount(), "一个都没落到树上");
    }

    /**
     * 🔴 变体选择符是<b>唯一被放行</b>的不可见字符 —— 但它<b>造不出第二个名字</b>。
     *
     * <p>放行的理由见 {@link SyllabusNames#isVariationSelector}:它依附于前一个字符,
     * 会跟着 emoji 一起被正常输入(一颗红心是 U+2764 U+FE0F 两个码点)。拒绝它,
     * 一个明明看得见自己名字的用户会收到「名称里不能有看不见的字符」—— 那是误伤。
     * <p>
     * 代价由 {@link SyllabusNames#nameKey} 兜住:比较前剥掉它。于是缀一个变体选择符
     * 得到的是一句说得清的 {@code NAME_TAKEN},不是一个肉眼分不出的孪生考点。
     */
    @Test
    @DisplayName("🔴 变体选择符放行(不误伤 emoji),但缀在已有名字后面得到 NAME_TAKEN 而不是新考点")
    void variationSelectorsArePassedThroughButCannotMintATwin() {
        FileSyllabusStore store = emptyLedgerStore();

        // U+FE0F / U+FE00 在基本平面;U+E0100 在辅助平面 —— 必须逐【码点】判定才拦得住,
        // 逐 char 的写法会把它拆成两个代理项,两个都不是不可见码点,于是整个漏过去
        List<String> selectors = List.of("\uFE0F", "\uFE00",
                new String(Character.toChars(0xE0100)));
        for (String vs : selectors) {
            assertEquals(SyllabusEditException.Reason.NAME_TAKEN,
                    assertThrows(SyllabusEditException.class,
                            () -> store.addNode("effect", "增长量计算" + vs, 0)).reason(),
                    "缀一个变体选择符不该造出第二个「增长量计算」:U+"
                            + Integer.toHexString(vs.codePointAt(0)));
        }
        assertEquals(18, store.current().nodeCount());

        // 带 emoji 的名字要能建,而且【原样存下来】—— 规范化只用于比较,不用于存储
        String heart = "❤\uFE0F";                       // 一颗红心:基字 + 变体选择符
        Syllabus.Node created = store.addNode("growth", "增长量速算" + heart, 0);
        assertEquals("增长量速算" + heart, created.name(), "存的是用户输入的原样");

        // 而它照样占住了「不带变体选择符」的那个写法 —— 两者在列表里分不出来
        assertEquals(SyllabusEditException.Reason.NAME_TAKEN,
                assertThrows(SyllabusEditException.class,
                        () -> store.addNode("effect", "增长量速算❤", 0)).reason());
    }

    /**
     * 放行变体选择符换来的唯一新漏洞,在这里堵上:<b>一个只由不可见字符组成的名字</b>。
     * 它上面每一条校验都过得去,却在命令面板上渲染成一片空白 —— 而考点是按名字挑的,
     * 一个挑不出来的考点等于一个挂不上记录的考点。
     */
    @Test
    @DisplayName("🔴 名字里一个看得见的字符都没有 → INVALID_NAME(否则面板上会出现一个无名考点)")
    void aNameWithNothingVisibleInItIsRejected() {
        FileSyllabusStore store = emptyLedgerStore();

        for (String blank : List.of("\uFE0F", "\uFE0F\uFE00", "\uFE0F \uFE01")) {
            SyllabusEditException e = assertThrows(SyllabusEditException.class,
                    () -> store.addNode("growth", blank, 0));
            assertEquals(SyllabusEditException.Reason.INVALID_NAME, e.reason());
            assertTrue(e.getMessage().contains("看得见"), "要说清为什么:" + e.getMessage());
        }
        assertEquals(18, store.current().nodeCount());
    }

    /**
     * 规范化口径的另一半:不可见码点在比较时一律不算数。
     *
     * <p>与上面那些 store 级测试分开,是因为这是一个<b>纯函数</b>,
     * 而载入路径({@link SyllabusLoader})用的就是它 —— 磁盘上的文件没有经过
     * {@code validName},能不能查出「文件里藏着一对肉眼分不出的重名」全靠这一步。
     */
    @Test
    @DisplayName("🔴 nameKey 剥掉全部不可见码点;中文/英文/数字/常见标点一个都不能被当成不可见")
    void nameKeyIgnoresInvisibleCodePoints() {
        String base = SyllabusNames.nameKey("增长量计算");
        List<String> invisibles = new ArrayList<>(List.of(
                "\u200B", "\u200D", "\uFEFF", "\u00AD", "\u2060",   // Cf
                "\u034F", "\u115F", "\u1160", "\u17B5", "\u180B",   // Mn / Lo
                "\u2800", "\u3164", "\uFFA0", "\uFE0F"));           // So / Lo / Mn
        invisibles.add(new String(Character.toChars(0xE0100)));     // 辅助平面
        for (String invisible : invisibles) {
            assertTrue(SyllabusNames.isInvisible(invisible.codePointAt(0)),
                    "U+" + Integer.toHexString(invisible.codePointAt(0)) + " 应当算不可见");
            assertEquals(base, SyllabusNames.nameKey("增长量计算" + invisible),
                    "不可见码点不构成区别:U+" + Integer.toHexString(invisible.codePointAt(0)));
            assertEquals(base, SyllabusNames.nameKey(invisible + "增长量" + invisible + "计算"),
                    "夹在中间、放在开头也一样");
        }

        // 🔴 误伤比漏放更糟:凡是看得见的,一个都不能被判成不可见
        for (String visible : List.of("增", "长", "率", "A", "z", "7", "·", "(", ")",
                ",", "%", "—", "α", "±", "❤")) {
            assertFalse(SyllabusNames.isInvisible(visible.codePointAt(0)),
                    "看得见的字符被判成不可见了:" + visible);
            assertFalse(SyllabusNames.nameKey(visible).isEmpty(),
                    "看得见的字符被 nameKey 剥掉了:" + visible);
        }
    }

    /**
     * 写入路径守不住<b>已经躺在磁盘上的文件</b>:种子写错了、用户手工编辑过、
     * 或者是这条约束存在之前留下的树。带着两个同名考点启动,不变式从第一秒起就是假的,
     * 而没有任何一处会报错 —— 这和 {@code FileTouchStore} 那条「坏文件静默变成空树」是同一类问题。
     */
    @Test
    @DisplayName("🔴 数据文件里已经有重名 → 启动时响亮失败,不带病运行")
    void duplicateNamesInTheDataFileFailLoudly() throws Exception {
        writeTree("""
                { "code": "growth", "name": "增长类", "nodes": [
                    { "code": "growth-rate",   "name": "增长率计算", "recent5yCount": 9 },
                    { "code": "growth-rate-2", "name": "增长率计算", "recent5yCount": 3 } ] }
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> emptyLedgerStore().current());
        assertTrue(e.getMessage().contains("growth-rate") && e.getMessage().contains("growth-rate-2"),
                "要说出是哪两行 —— 名字看起来可能一模一样,只说名字没法定位:" + e.getMessage());

        // 跨题型、且只差空格/全角/大小写的,同样算重名
        writeTree("""
                { "code": "growth", "name": "增长类", "nodes": [
                    { "code": "growth-rate", "name": "GDP 增长率", "recent5yCount": 9 } ] },
                { "code": "effect", "name": "效应类", "nodes": [
                    { "code": "gdp-2", "name": "ｇｄｐ  增长率", "recent5yCount": 3 } ] }
                """);
        assertThrows(IllegalStateException.class, () -> emptyLedgerStore().current(),
                "载入与写入必须是同一个口径,否则写得进去的树下次启动读不出来");

        // 归档的也算 —— 否则 unarchive 会静默造出一个重名
        writeTree("""
                { "code": "growth", "name": "增长类", "nodes": [
                    { "code": "growth-rate",   "name": "增长率计算", "recent5yCount": 9 },
                    { "code": "growth-rate-2", "name": "增长率计算", "recent5yCount": 3,
                      "archived": true } ] }
                """);
        assertThrows(IllegalStateException.class, () -> emptyLedgerStore().current());

        // 题型重名同样
        writeTree("""
                { "code": "growth", "name": "增长类", "nodes": [] },
                { "code": "growth2", "name": " 增长类 ", "nodes": [] }
                """);
        assertThrows(IllegalStateException.class, () -> emptyLedgerStore().current());

        // 🔴 只差一个【看不见的码点】的一对,同样必须在这里被查出来。
        // 磁盘上的文件没有经过 validName —— 手工编辑的、从导出文件恢复回来的都算,
        // 所以这一半的守卫全靠 nameKey 剥不可见码点。JSON 里写转义,文件里不放看不见的字符。
        writeTree("""
                { "code": "growth", "name": "增长类", "nodes": [
                    { "code": "growth-rate",   "name": "增长率计算", "recent5yCount": 9 },
                    { "code": "growth-rate-2", "name": "增长率计算\\ufe0f", "recent5yCount": 3 } ] }
                """);
        assertThrows(IllegalStateException.class, () -> emptyLedgerStore().current(),
                "两个名字渲染出来一模一样 —— 带着这样一棵树启动,不变式从第一秒起就是假的");
    }

    @Test
    @DisplayName("🔴 现有 18 个种子考点必须仍然合法 —— 新约束不能让启动炸掉")
    void theSeedItselfSatisfiesTheNewInvariant() {
        Syllabus seeded = emptyLedgerStore().current();
        assertEquals(18, seeded.nodeCount());

        assertEquals(18, seeded.allNodesIncludingArchived().stream()
                .map(n -> SyllabusNames.nameKey(n.name())).distinct().count(), "18 个考点名两两不同");
        assertEquals(5, seeded.groups().stream()
                .map(g -> SyllabusNames.nameKey(g.name())).distinct().count(), "5 个题型名两两不同");
    }

    @Test
    @DisplayName("🔴 重名被拒之后,覆盖率的 total / covered / percent 一个数都没变")
    void refusedNameConflictMovesNoNumbers() {
        FileSyllabusStore store = store();
        Summary before = summarize(store);
        assertEquals(18, before.total());
        assertEquals(8, before.covered());
        assertEquals(44, before.percent());

        store.archiveNode("mixed-growth");                  // 造一个「名字被归档考点占着」的场景
        Summary afterArchive = summarize(store);

        assertThrows(SyllabusEditException.class, () -> store.addNode("growth", "增长量计算", 5));
        assertThrows(SyllabusEditException.class, () -> store.addNode("effect", "  增长量计算 ", 5));
        assertThrows(SyllabusEditException.class, () -> store.addNode("effect", "混合增长率", 5));
        assertThrows(SyllabusEditException.class, () -> store.renameNode("growth-rate", "基期量计算"));
        assertThrows(SyllabusEditException.class, () -> store.addGroup("速算技巧"));
        assertThrows(SyllabusEditException.class, () -> store.renameGroup("growth", "效应类"));

        Summary after = summarize(store);
        assertEquals(afterArchive.total(), after.total(), "分母不动");
        assertEquals(afterArchive.covered(), after.covered(), "分子不动");
        assertEquals(afterArchive.percent(), after.percent(), "百分比逐字不动");
        assertEquals("增长率计算", store.current().node("growth-rate").name(), "被拒的改名没有落下");
        assertEquals(5, store.current().groups().size(), "被拒的新题型没有落下");
    }

    @Test
    @DisplayName("频次不能为负;名字两端的空白会被去掉")
    void frequencyAndTrimming() {
        FileSyllabusStore store = emptyLedgerStore();
        assertEquals(SyllabusEditException.Reason.INVALID_FREQUENCY,
                assertThrows(SyllabusEditException.class,
                        () -> store.setRecent5yCount("growth-rate", -1)).reason());

        assertEquals("复合增长率", store.addNode("growth", "  复合增长率  ", 0).name());
    }

    @Test
    @DisplayName("🔴 不做第四层:考点上没有 children,题型上没有嵌套的 groups")
    void thereIsNoFourthLevel() {
        List<String> nodeFields = Arrays.stream(Syllabus.Node.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertEquals(List.of("code", "name", "recent5yCount", "archived"), nodeFields,
                "加字段前先回去看 决策记录 §2.5 —— 三层是产品决定,不是实现细节");

        for (RecordComponent c : Syllabus.Node.class.getRecordComponents()) {
            assertNotEquals(List.class, c.getType(), "考点下面不许挂列表,那就是第四层:" + c.getName());
        }
        for (String forbidden : List.of("children", "subNodes", "points", "items", "nodes")) {
            assertFalse(nodeFields.contains(forbidden), "考点上不允许出现子层字段:" + forbidden);
        }

        List<String> groupFields = Arrays.stream(Syllabus.Group.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertEquals(List.of("code", "name", "nodes"), groupFields);
        assertFalse(groupFields.contains("groups"), "题型下面不许再套题型");
    }

    // ——————————————————— 持久化 ———————————————————

    @Test
    @DisplayName("编辑之后换一个实例照样读得到 —— 文件是唯一事实来源")
    void editsSurviveReopen() {
        FileSyllabusStore first = emptyLedgerStore();
        Syllabus.Node created = first.addNode("growth", "复合增长率", 3);
        first.renameNode(created.code(), "复合增长率(自己的说法)");
        first.archiveNode("mixed-growth");
        first.addGroup("自己加的题型");

        Syllabus reopened = emptyLedgerStore().current();
        assertEquals("复合增长率(自己的说法)", reopened.node(created.code()).name());
        assertTrue(reopened.nodeIncludingArchived("mixed-growth").archived());
        assertNull(reopened.node("mixed-growth"));
        assertEquals(6, reopened.groups().size());
        assertEquals(18, reopened.nodeCount(), "+1 新增,−1 归档");
    }

    @Test
    @DisplayName("写入是先临时文件再原子 rename —— 写完不留 .tmp 残骸")
    void writeLeavesNoTempFile() throws Exception {
        emptyLedgerStore().addNode("growth", "复合增长率", 1);

        try (var entries = Files.list(dataDir)) {
            List<String> names = entries.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("syllabus.json"), names, "目录里只该有数据文件本身");
        }
    }

    @Test
    @DisplayName("并发新增一个都不能丢 —— 骨架是一个考点一个考点敲出来的,它和记录一样是资产")
    void concurrentEditsLoseNothing() throws Exception {
        FileSyllabusStore store = emptyLedgerStore();
        int threads = 6;
        int perThread = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.addNode("growth", "并发考点-" + id + "-" + i, i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发编辑超时");
        for (Thread w : workers) {
            w.join();
        }

        int expected = 18 + threads * perThread;
        assertEquals(expected, store.current().nodeCount(), "内存里一个不少");
        assertEquals(expected, emptyLedgerStore().current().nodeCount(), "磁盘上也一个不少");
    }

    @Test
    @DisplayName("默认数据目录是 ~/.kaodian,与行为层同一个目录,且可配置")
    void dataDirIsResolvableAndConfigurable() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(NodeRecordLedger.class, () -> new TouchLedger(
                    new FileTouchStore(dataDir.resolve("touches.json"))));
            ctx.register(PropertySourcesPlaceholderConfigurer.class, FileSyllabusStore.class);
            ctx.refresh();
            assertEquals(Path.of(System.getProperty("user.home"), ".kaodian", "syllabus.json"),
                    ctx.getBean(FileSyllabusStore.class).dataFile(),
                    "占位符解析不了的话,第一次真跑起来才会炸 —— 那太晚了");
        }

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("kaodian.data.dir", dataDir.toString())));
            ctx.registerBean(NodeRecordLedger.class, () -> new TouchLedger(
                    new FileTouchStore(dataDir.resolve("touches.json"))));
            ctx.register(PropertySourcesPlaceholderConfigurer.class, FileSyllabusStore.class);
            ctx.refresh();
            assertEquals(file().toAbsolutePath(), ctx.getBean(FileSyllabusStore.class).dataFile());
            assertFalse(Files.exists(file()), "只是造了个 bean,还没访问过 —— 不该写任何文件");
        }
    }

    @Test
    @DisplayName("归档的考点挂不上新记录 —— 归档的意思正是「不再往上挂东西」")
    void archivedNodeRefusesNewRecords() {
        FileSyllabusStore store = store();
        store.archiveNode("average-calc");

        assertNull(store.current().node("average-calc"),
                "CaptureService 用的就是这个查询,查不到就是拒绝(R-07)");
        assertNotNull(store.current().nodeIncludingArchived("average-calc"));
    }

    // ——————————————————— 夹具 ———————————————————

    private Summary summarize(SyllabusStore store) {
        CoverageService service = new CoverageService();
        return service.summarize(service.compute(store.current(), touches().findAll(), Instant.now()));
    }

    private FileTouchStore touches() {
        if (touches == null) {
            touches = new FileTouchStore(dataDir.resolve("touches.json"));
        }
        return touches;
    }

    /** 写一份只有指定题型的骨架文件,用来构造各种「文件坏成这样会怎样」。 */
    private void writeTree(String groupJson) throws Exception {
        Files.createDirectories(dataDir);
        Files.writeString(file(), """
                {
                  "subject": { "code": "sd-xingce-ziliao", "region": "山东省考", "exam": "行测",
                               "module": "资料分析", "recent5yWindow": "2021-2025" },
                  "groups": [ %s ]
                }
                """.formatted(groupJson), StandardCharsets.UTF_8);
    }

    private static JsonNode readSeedResource() {
        try (InputStream in = SyllabusLoader.class.getResourceAsStream(
                SyllabusLoader.DEFAULT_SEED_RESOURCE)) {
            assertNotNull(in, "找不到骨架种子文件");
            return MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
