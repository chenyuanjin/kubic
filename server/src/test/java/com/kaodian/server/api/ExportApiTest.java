package com.kaodian.server.api;

import com.jayway.jsonpath.JsonPath;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.InMemoryRecordTagStore;
import com.kaodian.server.collect.RecordTagStore;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusLoader;
import com.kaodian.server.syllabus.SyllabusSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 把 docs/10 §6.5 的三条承诺钉住:<b>无删减、无水印、不限次数</b>({@code 1.3.6.1}),
 * 外加 {@code R-06} 的内容边界。
 *
 * <h2>为什么这三条值得各写一个测试</h2>
 *
 * 它们都是「<b>没有做某件事</b>」的承诺,而没做的事写不进代码 ——
 * 代码里只看得到「这里没加频控」,看不到「这里永远不许加频控」。
 * 一个只在文档里的承诺,下一个人加缓存、加配额、加一句「导出请升级」的时候不会挡住他。
 * 这几条断言就是那个挡住他的东西。
 *
 * <h2>「同一份数据的三种写法」怎么验</h2>
 *
 * 不是分别验三次条数(那只证明三个数字碰巧相等),而是<b>把三份里的记录 id 逐条取出来比对</b>。
 * 少一条、多一条、或者某一格错位,这条都会红。
 */
@WebMvcTest(controllers = ExportController.class)
@Import(ApiBeans.class)     // web 切片不扫 @Configuration,领域装配要显式带进来
class ExportApiTest {

    /**
     * 库里放多少条。
     *
     * <p>取一个<b>不像默认值</b>的数:8 是设计契约里的数,50 是时间线的默认 limit,
     * 100 是盲区的上限。24 不等于其中任何一个 —— 万一哪天导出被人接上了分页,
     * 这个数会把它照出来。
     */
    private static final int RECORD_COUNT = 24;

    /**
     * 🔴 导出的全部列 —— <b>这就是内容边界(R-06 / R-01)。</b>
     *
     * <p>每一列的值要么是我们自己算的统计,要么是用户自己录进来的东西:
     * 来源<b>名字</b>、时间、方式、考点 code 与名称、他自己填的两个整数。
     * 没有一列装得下机构的课程内容,也没有一列装得下题干。
     * <p>
     * 这张表同时钉住两件事:<b>列不许多</b>(加一列必须先来改这里,而改这里要回答值从哪来),
     * <b>块不许多</b>(见 {@code exportColumnsArePinned} 里那句「表头行恰好 6 条」)。
     */
    private static final Map<String, List<String>> PINNED_COLUMNS = pinnedColumns();

    private static Map<String, List<String>> pinnedColumns() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("meta", List.of("导出时间", "记录总数", "模块 code", "模块"));
        m.put("summary", List.of("考点总数", "已触达", "空白", "覆盖率", "整块空白的题型"));
        m.put("states", List.of("状态代码", "状态", "考点数"));
        m.put("nodes", List.of("考点 code", "考点", "题型 code", "题型", "近五年频次",
                "状态代码", "状态", "触达次数", "练了几道", "对了几道", "正确率", "最近触达", "来源"));
        m.put("archived", List.of("考点 code", "考点", "题型 code", "题型", "近五年频次", "记录数"));
        m.put("records", List.of("记录 id", "时间", "方式代码", "方式", "来源",
                "考点 code", "考点", "题型 code", "题型", "练了几道", "对了几道"));
        return Map.copyOf(m);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReadOnlyTouchStore store;

    @Autowired
    private SwappableSyllabus syllabus;

    @BeforeEach
    void seed() {
        store.reset(manyTouches());
        syllabus.reset();
    }

    // ---------------------------------------------------------------- 无删减

    @Test
    @DisplayName("🔴 无删减:三种格式里的记录条数都等于库里的条数,一条不少")
    void everyFormatCarriesEveryRecord() throws Exception {
        assertEquals(RECORD_COUNT, store.count(), "夹具自己先得对");

        String json = body("json");
        String md = body("md");
        String csv = body("csv");

        assertEquals(RECORD_COUNT, jsonRecordIds(json).size(), "json 少了记录");
        assertEquals(RECORD_COUNT, csvRowsOf(csv, "records").size(), "csv 少了记录");
        assertEquals(RECORD_COUNT, mdRowsOf(md, "记录").size(), "md 少了记录");

        // 导出方自报的那个数也必须对上 —— 它存在的意义就是让「有没有被截断」可以核对
        mockMvc.perform(get("/api/export").param("format", "json"))
                .andExpect(jsonPath("$.recordCount").value(RECORD_COUNT))
                .andExpect(jsonPath("$.records.length()").value(RECORD_COUNT));
    }

    @Test
    @DisplayName("🔴 三种格式装的是同一批记录 —— 逐条比对 id,不是比对条数")
    void theThreeFormatsCarryTheSameRecords() throws Exception {
        List<String> fromJson = jsonRecordIds(body("json"));
        List<String> fromCsv = csvRowsOf(body("csv"), "records").stream()
                .map(line -> line.split(",", 3)[1])
                .toList();
        List<String> fromMd = mdRowsOf(body("md"), "记录").stream()
                .map(row -> row.split("\\|")[1].trim())
                .toList();

        assertEquals(fromJson, fromCsv, "csv 与 json 装的不是同一批记录");
        assertEquals(fromJson, fromMd, "md 与 json 装的不是同一批记录");
    }

    @Test
    @DisplayName("🔴 归档的考点也在导出里 —— 归档退出差集,但不退出你的数据(R-49)")
    void archivedNodesAreExportedToo() throws Exception {
        syllabus.archive("growth-rate");

        String json = body("json");
        assertEquals("growth-rate", JsonPath.read(json, "$.archivedNodes[0].code"));
        assertTrue((int) (Integer) JsonPath.read(json, "$.archivedNodes[0].recordCount") > 0,
                "归档考点上原来那些记录必须还在,归档不是删除");

        assertEquals(1, csvRowsOf(body("csv"), "archived").size());
        assertEquals(1, mdRowsOf(body("md"), "已归档的考点").size());

        // 🔴 最要紧的一条:归档之后记录一条都没少。
        // R-49 说的是「把空白全归档,44% 立刻变 100%」—— 如果导出跟着覆盖度一起把归档的记录藏掉,
        // 那份导出就成了这个刷分动作的同谋。
        assertEquals(RECORD_COUNT, jsonRecordIds(body("json")).size());
        assertEquals(RECORD_COUNT, csvRowsOf(body("csv"), "records").size());
        assertEquals(RECORD_COUNT, mdRowsOf(body("md"), "记录").size());
    }

    // ---------------------------------------------------------------- 无水印

    @Test
    @DisplayName("🔴 无水印:三份导出里没有署名、没有产品名、没有「Powered by」这类尾巴")
    void noWatermarkAnywhere() throws Exception {
        List<String> banned = List.of(
                "powered by", "generated by", "exported by", "watermark",
                "kaodian", "考点工具", "水印", "由本", "本工具", "试用版", "升级");

        for (String format : List.of("md", "csv", "json")) {
            String lower = body(format).toLowerCase(Locale.ROOT);
            for (String bad : banned) {
                assertFalse(lower.contains(bad.toLowerCase(Locale.ROOT)),
                        format + " 的导出里出现了「" + bad + "」—— 01 §2.6 的承诺是完整导出,"
                                + "在用户拿走的东西上留记号是把承诺打了折");
            }
        }

        // md 以表格收尾,没有页脚。「最后一行是数据」是「没有尾巴」最直接的形状。
        assertTrue(body("md").stripTrailing().endsWith("|"),
                "md 末尾多了点什么 —— 导出的最后一行必须还是数据");
    }

    // ---------------------------------------------------------------- 不限次数

    @Test
    @DisplayName("🔴 不限次数:连着导 50 次全是 200,没有频控头,每一次都还是全量")
    void exportIsNeverRateLimited() throws Exception {
        for (int i = 1; i <= 50; i++) {
            MvcResult result = mockMvc.perform(get("/api/export").param("format", "json"))
                    .andExpect(status().isOk())
                    .andReturn();

            for (String header : List.of("Retry-After", "X-RateLimit-Limit",
                    "X-RateLimit-Remaining", "X-RateLimit-Reset", "RateLimit")) {
                assertNull(result.getResponse().getHeader(header),
                        "第 " + i + " 次导出带上了频控头 " + header
                                + " —— §6.5 写的是【不限次数】(1.3.6.1)");
            }
            assertEquals(RECORD_COUNT,
                    jsonRecordIds(result.getResponse().getContentAsString()).size(),
                    "第 " + i + " 次导出被削减了 —— 「不限次数」不只是不返回 429,是每一次都给全量");
        }
    }

    @Test
    @DisplayName("🔴 导出这条路上没有额度、没有频控的位置 —— 断言的是形状,不是某一次调用")
    void exportHasNoQuotaOrThrottleDependency() {
        List<String> forbidden = List.of("quota", "ratelimit", "limiter", "throttle",
                "billing", "subscription", "plan", "credit", "额度");

        List<String> surfaces = new ArrayList<>();
        for (Constructor<?> c : ExportController.class.getDeclaredConstructors()) {
            for (Class<?> p : c.getParameterTypes()) {
                surfaces.add("构造器参数 " + p.getSimpleName());
            }
        }
        for (Field f : ExportController.class.getDeclaredFields()) {
            surfaces.add("字段 " + f.getName() + " : " + f.getType().getSimpleName());
        }

        for (String surface : surfaces) {
            String lower = surface.toLowerCase(Locale.ROOT);
            for (String bad : forbidden) {
                assertFalse(lower.contains(bad), """
                        ExportController 依赖上出现了「%s」:%s

                        §6.7 的额度只管 ai_capture / ai_ask 两类 —— 收的是替用户花出去的模型钱。
                        导出不调用任何模型,没有可收的东西。要给导出加计数之前,
                        先回 docs/10 §6.5 把「不限次数」改掉,顺序不能反。
                        """.formatted(bad, surface));
            }
        }
        assertFalse(surfaces.isEmpty(), "一个扫不到东西的断言等于没有断言");
    }

    // ---------------------------------------------------------------- 内容边界

    @Test
    @DisplayName("🔴 R-06:导出的列被钉死 —— 六块,列名逐字一致,md 与 csv 用的是同一张表")
    void exportColumnsArePinned() throws Exception {
        syllabus.archive("growth-rate");        // 六块都非空,md 才会把六张表头都写出来

        String csv = body("csv");
        String md = body("md");

        for (Map.Entry<String, List<String>> block : PINNED_COLUMNS.entrySet()) {
            String csvHeader = "section," + String.join(",", block.getValue()) + "\n";
            assertTrue(csv.contains(csvHeader),
                    "csv 里「" + block.getKey() + "」这一块的列变了。期望表头:\n" + csvHeader + "实际:\n" + csv);

            String mdHeader = "| " + String.join(" | ", block.getValue()) + " |";
            assertTrue(md.contains(mdHeader),
                    "md 里「" + block.getKey() + "」这一块的列与 csv 对不上了。期望表头:\n"
                            + mdHeader + "\n实际:\n" + md);
        }

        assertEquals(PINNED_COLUMNS.size(), csv.lines().filter(l -> l.startsWith("section,")).count(),
                "导出多了或少了一整块。加一块之前先回答:它的每一列的值从哪个字段来?(R-06)");
    }

    @Test
    @DisplayName("🔴 来源名里的逗号/引号/竖线/换行只被转义,不被改写 —— 导出是原样交还")
    void trickySourceNamesAreEscapedNotRewritten() throws Exception {
        // csv:整格加引号,内部引号翻倍;换行留在引号里,所以【行数不变】
        String csv = body("csv");
        assertTrue(csv.contains("\"粉笔, \"\"资料\"\" | 系统班\nL12\""),
                "csv 没有按 RFC 4180 转义这一格:\n" + csv);
        assertEquals(RECORD_COUNT, csvRowsOf(csv, "records").size(),
                "带换行的来源名把 csv 的记录行数弄乱了");

        // md:竖线转义、换行压成空格,所以【表格不塌】
        String md = body("md");
        assertTrue(md.contains("粉笔, \"资料\" \\| 系统班 L12"),
                "md 没有转义这一格里的竖线/换行:\n" + md);
        assertEquals(RECORD_COUNT, mdRowsOf(md, "记录").size(), "带竖线的来源名把 md 表格劈开了");
    }

    // ---------------------------------------------------------------- 参数与响应头

    @Test
    @DisplayName("format 只认 md / csv / json 三个值,大小写不敏感;别的一律 400 且不回显原文")
    void formatIsValidated() throws Exception {
        for (String ok : List.of("md", "csv", "json", "JSON", " Md ")) {
            mockMvc.perform(get("/api/export").param("format", ok)).andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/export").param("format", "xlsx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_EXPORT_FORMAT"));

        // 🔴 format 是查询参数,没有 @Size 管得着它 —— 回声必须截断,否则它就是把一段题干
        //    写进响应体和访问日志的通道(01 §2.2 不碰内容)
        String stem = "题".repeat(500);
        MvcResult rejected = mockMvc.perform(get("/api/export").param("format", stem))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertFalse(rejected.getResponse().getContentAsString().contains(stem),
                "报错把用户送来的原文整段回显了");

        // 没有默认值:三种写法没有主次,挑一个当默认就是替用户做了决定
        mockMvc.perform(get("/api/export")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("三种格式各自的 Content-Type 与下载文件名 —— 拿走的是文件,不是一屏文本")
    void eachFormatIsDownloadable() throws Exception {
        Map<String, String> expected = Map.of(
                "md", "text/markdown;charset=UTF-8",
                "csv", "text/csv;charset=UTF-8",
                "json", "application/json;charset=UTF-8");

        for (Map.Entry<String, String> e : expected.entrySet()) {
            MvcResult result = mockMvc.perform(get("/api/export").param("format", e.getKey()))
                    .andExpect(status().isOk())
                    .andReturn();
            assertEquals(e.getValue(), result.getResponse().getContentType());

            String disposition = result.getResponse().getHeader("Content-Disposition");
            assertTrue(disposition != null && disposition.startsWith("attachment; ")
                            && disposition.endsWith("." + e.getKey() + "\""),
                    "下载头不对:" + disposition);
            assertTrue(disposition.chars().allMatch(c -> c < 128),
                    "Content-Disposition 必须是纯 ASCII —— 模块 code 来自用户能编辑的文件:" + disposition);
        }
    }

    // ---------------------------------------------------------------- 夹具与解析

    private String body(String format) throws Exception {
        return mockMvc.perform(get("/api/export").param("format", format))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static List<String> jsonRecordIds(String json) {
        return JsonPath.read(json, "$.records[*].id");
    }

    /** csv 里属于某一块的数据行。表头行以 {@code section,} 开头,与块名不会撞。 */
    private static List<String> csvRowsOf(String csv, String key) {
        return csv.lines().filter(line -> line.startsWith(key + ",")).toList();
    }

    /** md 里某一块的数据行 —— 去掉表头与分隔行那两行。 */
    private static List<String> mdRowsOf(String md, String title) {
        for (String block : md.split("\n## ")) {
            if (block.startsWith(title + "\n")) {
                List<String> rows = block.lines().filter(line -> line.startsWith("|")).toList();
                return rows.size() <= 2 ? List.of() : rows.subList(2, rows.size());
            }
        }
        throw new AssertionError("md 导出里没有「" + title + "」这一块:\n" + md);
    }

    /**
     * {@value #RECORD_COUNT} 条记录,分布在五个考点上。
     *
     * <p>最后一条的来源名里塞了逗号、引号、竖线和换行 —— 这四个字符恰好是 csv 与 md
     * 各自的分隔符。<b>它们都能合法出现在一个 60 字以内的来源名里</b>
     * (「粉笔,资料分析(强化)| L12」),所以转义不是可选项。
     */
    private static List<Touch> manyTouches() {
        Instant now = Instant.now();
        List<String> nodes = List.of("growth-rate", "share-calc", "feature-number",
                "growth-amount", "base-value");
        List<Touch> ts = new ArrayList<>();
        for (int i = 0; i < RECORD_COUNT - 1; i++) {
            boolean drill = i % 3 == 0;
            ts.add(new Touch("t-" + i, nodes.get(i % nodes.size()), "来源 " + i,
                    drill ? TouchKind.DRILL : TouchKind.VOICE,
                    now.minus(Duration.ofDays(i)),
                    drill ? new Touch.Drill(10, 7) : null));
        }
        ts.add(new Touch("t-tricky", "growth-rate", "粉笔, \"资料\" | 系统班\nL12",
                TouchKind.PASTE, now.minus(Duration.ofHours(1)), null));
        return ts;
    }

    /**
     * 行为层的读桩。
     *
     * <h2>只有 {@link #findAll} 和 {@link #count} 是真的,其余一律拒绝</h2>
     *
     * 导出<b>是一个只读端点</b>,它在 {@link TouchStore} 上只该用到这两个方法。
     * 把写侧的方法实现成「一调用就炸」,本身就是一条断言:哪天有人在导出路径上
     * 顺手 append 或 delete 了什么,这个测试会当场红,而不是安静地通过。
     * <p>
     * 🔴 这也是 docs/10 §6.5「MCP 只读」四道锁的同一条思路 ——
     * 导出是那五个只读 tool 之一,<b>只读要写进形状里,不能靠调用方自觉</b>。
     */
    static final class ReadOnlyTouchStore implements TouchStore {

        private final List<Touch> touches = new ArrayList<>();

        void reset(List<Touch> seed) {
            touches.clear();
            touches.addAll(seed);
        }

        /** 契约:按发生时间升序。导出的记录顺序直接来自这里。 */
        @Override
        public List<Touch> findAll() {
            return touches.stream().sorted(Comparator.comparing(Touch::occurredAt)).toList();
        }

        @Override
        public int count() {
            return touches.size();
        }

        @Override
        public List<Touch> findByNode(String nodeCode) {
            return touches.stream().filter(t -> t.nodeCode().equals(nodeCode)).toList();
        }

        @Override
        public Touch findByClientToken(String clientToken) {
            throw new AssertionError("导出是只读的,不该去查去重键");
        }

        @Override
        public Touch append(Touch touch) {
            throw new AssertionError("导出路径上出现了写入 —— 只读端点不该改动任何东西");
        }

        @Override
        public Touch delete(String id) {
            throw new AssertionError("导出路径上出现了删除 —— 只读端点不该改动任何东西");
        }

        @Override
        public int reassign(String fromNodeCode, String toNodeCode) {
            throw new AssertionError("导出路径上出现了改挂 —— 只读端点不该改动任何东西");
        }
    }

    /**
     * 一棵可以在测试里换掉的树。
     *
     * <p>{@code SyllabusLoader.loadDefault()} 给的是不可变的 record,没法就地归档一个考点;
     * 起真的 {@code FileSyllabusStore} 又会去写 {@code ~/.kaodian/} ——
     * 那是另一条线的事,不该被一个导出测试拖进来。
     */
    static final class SwappableSyllabus implements SyllabusSource {

        private volatile Syllabus tree = SyllabusLoader.loadDefault();

        @Override
        public Syllabus current() {
            return tree;
        }

        void reset() {
            tree = SyllabusLoader.loadDefault();
        }

        /** 把一个考点标成已归档。记录一条不动 —— 归档的语义就是这个。 */
        void archive(String nodeCode) {
            tree = new Syllabus(tree.subject(), tree.groups().stream()
                    .map(g -> new Syllabus.Group(g.code(), g.name(), g.nodes().stream()
                            .map(n -> n.code().equals(nodeCode)
                                    ? new Syllabus.Node(n.code(), n.name(), n.recent5yCount(), true)
                                    : n)
                            .toList()))
                    .toList());
        }
    }

    @TestConfiguration
    static class Fixtures {

        @Bean
        SwappableSyllabus syllabus() {
            return new SwappableSyllabus();
        }

        /** {@link CoverageReader} 是 {@code @Component},web 切片不扫它。 */
        @Bean
        CoverageReader coverageReader(SyllabusSource syllabus, TouchStore store,
                                      RecordTagStore tagStore, CoverageService coverage, Clock clock) {
            return new CoverageReader(syllabus, store, tagStore, coverage, clock);
        }

        /** 标签层。导出不打标,但覆盖度的分子要从这里出来。 */
        @Bean
        RecordTagStore recordTagStore() {
            return new InMemoryRecordTagStore();
        }

        @Bean
        ReadOnlyTouchStore touchStore() {
            return new ReadOnlyTouchStore();
        }
    }
}
