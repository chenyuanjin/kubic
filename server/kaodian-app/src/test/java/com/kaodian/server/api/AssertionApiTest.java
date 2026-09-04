package com.kaodian.server.api;

import com.kaodian.server.api.record.AssertionController;
import com.kaodian.server.api.insight.CoverageController;
import com.kaodian.server.api.syllabus.SyllabusController;
import com.kaodian.server.api.support.TaggingBeans;
import com.kaodian.server.config.DomainBeans;
import com.kaodian.server.coverage.CoverageReader;
import com.jayway.jsonpath.JsonPath;
import com.kaodian.server.api.dto.record.AssertionRequest;
import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.kaodian.server.collect.AssertionStore;
import com.kaodian.server.collect.InMemoryAssertionStore;
import com.kaodian.server.collect.InMemoryRecordTagStore;
import com.kaodian.server.collect.RecordTagStore;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.syllabus.SyllabusLoader;
import com.kaodian.server.syllabus.SyllabusSource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/technical/INDEX.md §6.4 最后一行:{@code POST/DELETE /assertions} —— 「我已掌握」/ 取消。
 *
 * <h2>🔴 这个文件验的第一件事是「那个大字没变」</h2>
 *
 * 决策记录 §5.2:<b>「『我已掌握』按钮是补丁不是解法。」</b> 所以这两个端点最重要的性质不是它们做了什么,
 * 是它们<b>没做什么</b> —— 按下去之后覆盖率一个字不动。
 * <p>
 * 「没做什么」的失败方式是无声的:把断言并进分子,接口全绿、界面更好看、用户更满意,
 * 而这个产品唯一的那个数字从此不再指向任何真实的东西。所以下面的用例是端到端地
 * <b>发一次 {@code GET /coverage/summary} → 发一次 POST → 再发一次 summary</b>,
 * 逐个字段比对,而不是只看 POST 的返回体。
 *
 * <h2>为什么把三个控制器一起装进来</h2>
 *
 * 断言的全部效果都<b>不在它自己的响应里</b>:盲区榜少一行(§6.4「排除已断言节点」)、
 * 概览多一格(§6.4「断言单列不并入」)、树上多一个时刻。
 * 只切一个 {@code AssertionController} 的话,一个「写库了但三处口径一处都没接上」的实现会全绿。
 *
 * <h2>幂等在这两个端点上是<b>契约</b>,不是实现的宽容</h2>
 *
 * 「我已掌握」在界面上是那种<b>连点会重复发请求</b>的按钮,而「取消」经常发生在
 * 用户已经在另一个标签页取消过之后。两个方向都必须无声地成功 ——
 * 报一个「你已经声明过了」的错,用户除了困惑之外什么都做不了。
 */
@WebMvcTest(controllers = {AssertionController.class, CoverageController.class, SyllabusController.class})
// web 切片不扫 @Configuration,领域装配要显式带进来;ApiTestAuth 给每个请求装上真令牌(B0-4 默认拒绝)
@Import({DomainBeans.class, TaggingBeans.class, ApiTestAuth.class})
class AssertionApiTest {

    /** 一个彻头彻尾的空白考点 —— 一条记录都没有,最容易被「按一下就算碰过」。 */
    private static final String BLANK_NODE = "average-calc";

    /** 盲区榜榜首(见 {@code ApiContractTest#blindSpotsMatchDesignContract})。 */
    private static final String TOP_BLIND_NODE = "growth-amount";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryTouchStore store;

    @Autowired
    private InMemoryAssertionStore assertions;

    @BeforeEach
    void reset() {
        store.reset(contractTouches());
        assertions.reset();
    }

    // ———————————————————— 一、覆盖率不动 ————————————————————

    /**
     * 🔴 这一条如果被删掉或改松,「我已掌握」就退化成一个刷分按钮。
     *
     * <p>断言的是「按之前 == 按之后」而不是写死的 44:一个写死的期望值,
     * 在有人把断言并进分子时会被当成过时的数字直接改掉。
     */
    @Test
    @DisplayName("🔴 POST /api/v1/assertions 之后,GET /coverage/summary 的覆盖率一个字都没变")
    void assertingDoesNotMoveTheCoverageNumber() throws Exception {
        String before = summaryBody();

        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isCreated());

        String after = summaryBody();

        assertEquals((int) JsonPath.read(before, "$.percent"), (int) JsonPath.read(after, "$.percent"),
                "🔴 覆盖率因为点了一次按钮而变了 —— docs/technical/INDEX.md §6.4:「分子 = discarded=0 的触达节点数」,"
                        + "而声明不是触达。决策记录 §5.2:「我已掌握」按钮是补丁不是解法");
        assertEquals((int) JsonPath.read(before, "$.covered"), (int) JsonPath.read(after, "$.covered"));
        assertEquals((int) JsonPath.read(before, "$.total"), (int) JsonPath.read(after, "$.total"),
                "分母不该动 —— 把考点从分母里拿掉那是【归档】,是另一件事(R-49)");
        assertEquals((int) JsonPath.read(before, "$.empty"), (int) JsonPath.read(after, "$.empty"),
                "那个考点确实还是一条记录都没有,声明改不了这件事");
        assertEquals(JsonPath.read(before, "$.distribution").toString(),
                JsonPath.read(after, "$.distribution").toString(),
                "五态是从记录推出来的,断言不该占其中任何一格");

        assertEquals(0, (int) JsonPath.read(before, "$.asserted"));
        assertEquals(1, (int) JsonPath.read(after, "$.asserted"),
                "唯一该变的就是这一格(docs/technical/INDEX.md §6.4:断言单列不并入)");
    }

    /**
     * 端点自己的响应里也得说同一件事。
     *
     * <p>把概览一起带回来,是为了让界面能在同一次交互里说清楚发生了什么:
     * <b>盲区榜少了一行,已声明多了一个,覆盖率一个字没动</b>。
     * 少了这份数据,界面只能沉默,而沉默会被读成「没生效」。
     */
    @Test
    @DisplayName("响应体里带着写完之后的概览,而它的 percent 与写之前相同")
    void theResponseCarriesTheUnchangedSummary() throws Exception {
        int percentBefore = JsonPath.read(summaryBody(), "$.percent");

        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.asserted").value(true))
                .andExpect(jsonPath("$.assertedAt").isNotEmpty())
                .andExpect(jsonPath("$.assertedTotal").value(1))
                .andExpect(jsonPath("$.summary.asserted").value(1))
                .andExpect(jsonPath("$.summary.percent").value(percentBefore))
                // 🔴 这个考点自己身上的数一个都没动 —— 声明不是一次触达
                .andExpect(jsonPath("$.node.code").value(BLANK_NODE))
                .andExpect(jsonPath("$.node.state").value("EMPTY"))
                .andExpect(jsonPath("$.node.touchCount").value(0))
                .andExpect(jsonPath("$.node.accuracy").value(Matchers.nullValue()));
    }

    // ———————————————————— 二、盲区榜排除 ————————————————————

    @Test
    @DisplayName("🔴 声明掌握之后,那个考点从 /coverage/blindspots 上消失(§6.4:排除已断言节点)")
    void assertedNodeLeavesTheBlindSpotList() throws Exception {
        mockMvc.perform(get("/api/v1/coverage/blindspots").param("top", "5"))
                .andExpect(jsonPath("$.items[0].code").value(TOP_BLIND_NODE));

        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + TOP_BLIND_NODE + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/coverage/blindspots").param("top", "5"))
                .andExpect(status().isOk())
                // 要 5 个还是给 5 个:过滤排在 limit 之前,下一名顶上来,榜不会越按越短
                .andExpect(jsonPath("$.returned").value(5))
                .andExpect(jsonPath("$.items[*].code",
                        Matchers.not(Matchers.hasItem(TOP_BLIND_NODE))));
    }

    @Test
    @DisplayName("取消之后它回到盲区榜上 —— 这个按钮的每一个效果都必须能撤回")
    void cancellingBringsItBack() throws Exception {
        assertMastery(TOP_BLIND_NODE);
        cancel(TOP_BLIND_NODE);

        mockMvc.perform(get("/api/v1/coverage/blindspots").param("top", "5"))
                .andExpect(jsonPath("$.items[0].code").value(TOP_BLIND_NODE));
    }

    @Test
    @DisplayName("树上那一格带着 assertedAt —— 否则用户没有任何地方能看到自己按过什么")
    void theTreeShowsWhenItWasAsserted() throws Exception {
        mockMvc.perform(get("/api/v1/syllabus/tree"))
                .andExpect(jsonPath("$.groups[*].nodes[?(@.code == '" + BLANK_NODE + "')].assertedAt",
                        Matchers.contains(Matchers.nullValue())));

        assertMastery(BLANK_NODE);

        mockMvc.perform(get("/api/v1/syllabus/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[*].nodes[?(@.code == '" + BLANK_NODE + "')].assertedAt",
                        Matchers.contains(Matchers.notNullValue())))
                // 🔴 状态还是空白 —— 断言是独立状态,不是第六态(docs/technical/INDEX.md §5.2)
                .andExpect(jsonPath("$.groups[*].nodes[?(@.code == '" + BLANK_NODE + "')].state",
                        Matchers.contains("EMPTY")));
    }

    // ———————————————————— 三、幂等 ————————————————————

    /**
     * 🔴 201 与 200 的区别是「新声明了没有」,不是「成功了没有」。
     *
     * <p>同一个考点声明第二次,服务端什么都没新建,这时候还回 201 是在说谎 ——
     * 而说谎在这里有具体后果:界面按 201 弹一次「已记下」的动效,
     * 用户会以为刚才那一下没生效,于是再点一次。
     */
    @Test
    @DisplayName("🔴 幂等:重复声明同一个考点不报错、不重复落行,第二次是 200 不是 201")
    void assertingTwiceIsIdempotent() throws Exception {
        String first = mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asserted").value(true))
                .andExpect(jsonPath("$.assertedTotal").value(1))
                .andReturn().getResponse().getContentAsString();

        assertEquals(1, assertions.count(ApiTestAuth.USER_ID),
                "🔴 库里落了两行 —— 「已声明 N 个」从此开始说谎");
        assertEquals(JsonPath.read(first, "$.assertedAt").toString(),
                JsonPath.read(second, "$.assertedAt").toString(),
                "重复声明不该刷新时刻 —— 连点两下不该改写「你在 X 月 X 日说过你会了」这句话");
    }

    @Test
    @DisplayName("🔴 幂等:取消一个没声明过的考点同样不报错,回 200")
    void cancellingSomethingNeverAssertedIsNotAnError() throws Exception {
        mockMvc.perform(delete("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asserted").value(false))
                .andExpect(jsonPath("$.assertedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.assertedTotal").value(0));

        assertEquals(0, assertions.count(ApiTestAuth.USER_ID));
    }

    @Test
    @DisplayName("取消之后再取消一次,还是 200 —— 用户要的结果早就成立了")
    void cancellingTwiceIsIdempotent() throws Exception {
        assertMastery(BLANK_NODE);
        cancel(BLANK_NODE);

        mockMvc.perform(delete("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assertedTotal").value(0));
    }

    @Test
    @DisplayName("取消之后再声明一次:是一次新的声明,时刻会重新计")
    void reAssertingAfterCancelStartsOver() throws Exception {
        assertMastery(BLANK_NODE);
        cancel(BLANK_NODE);

        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assertedAt").isNotEmpty());
    }

    // ———————————————————— 四、body 只接受 nodeCode ————————————————————

    /**
     * 🔴 R-07 在这个端点上的形状:<b>没有一个能装下自由文本的位置</b>。
     *
     * <p>静默忽略比报错危险:双方都以为红线没被碰过。
     * 「我已掌握」是一个布尔事实,不是一条笔记 —— 给它配个 {@code note} 字段,
     * 那个字段一年后装的就是题干(R-01)。
     */
    @Test
    @DisplayName("🔴 body 只接受 nodeCode:多一个键就是 400,不是被静默忽略")
    void bodyAcceptsNothingButNodeCode() throws Exception {
        for (String body : new String[]{
                "{\"nodeCode\":\"" + BLANK_NODE + "\",\"note\":\"这题我在抖音看过\"}",
                "{\"nodeCode\":\"" + BLANK_NODE + "\",\"name\":\"我自己想的考点\"}",
                "{\"nodeCode\":\"" + BLANK_NODE + "\",\"reason\":\"因为我会了\"}"}) {

            mockMvc.perform(post("/api/v1/assertions")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));

            mockMvc.perform(delete("/api/v1/assertions")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        }
        assertEquals(0, assertions.count(ApiTestAuth.USER_ID), "被拒的请求不该留下任何一行");
    }

    /**
     * 🔴 第二道锁 —— <b>关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES} 之后照样进不来</b>。
     *
     * <p>上面那条走的是真实配置,而真实配置里那行开关一直开着,于是它只能证明
     * 「两道锁<b>至少有一道</b>在」,证明不了「有两道」。实测:把 {@link AssertionRequest}
     * 上的 {@code @JsonAnySetter} 拿掉,整个套件<b>一条都不红</b>。
     * <p>
     * 做法与 {@code TagApiTest#unknownFieldsAreRejectedEvenWithoutTheMapperFlag} 同一份 ——
     * R-07 必须在配置被人改掉之后依然成立。
     */
    @Test
    @DisplayName("🔴 第二道锁:关掉 FAIL_ON_UNKNOWN_PROPERTIES,自由文本照样进不了这个请求体")
    void unknownFieldsAreRejectedEvenWithoutTheMapperFlag() {
        JsonMapper lenient = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)   // 第一道锁,故意拆掉
                .build();

        for (String field : List.of("note", "name", "label", "reason", "text")) {
            String body = """
                    {"nodeCode":"growth-rate","%s":"某年某省考资料分析材料第一段……"}
                    """.formatted(field);

            Exception thrown = assertThrows(Exception.class,
                    () -> lenient.readValue(body, AssertionRequest.class),
                    "配置锁拆掉之后 " + field + " 就进 AssertionRequest 了 —— 只剩一行配置撑着");

            UnknownFieldException lock = null;
            for (Throwable t = thrown; t != null && t != t.getCause(); t = t.getCause()) {
                if (t instanceof UnknownFieldException ufe) {
                    lock = ufe;
                }
            }
            assertNotNull(lock, "拒是拒了,但不是 DTO 那道锁拒的(" + field + "):" + thrown);
        }
    }

    @Test
    @DisplayName("nodeCode 缺失或空白 → 400")
    void nodeCodeIsRequired() throws Exception {
        for (String body : new String[]{"{}", "{\"nodeCode\":\"\"}", "{\"nodeCode\":\"   \"}"}) {
            mockMvc.perform(post("/api/v1/assertions")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("🔴 R-07:nodeCode 不在骨架树里 → 400,不猜最接近的考点(只能从树里选,不能新建)")
    void unknownNodeIsRejectedNotGuessed() throws Exception {
        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"增长率那个\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NODE_NOT_IN_SYLLABUS"));

        assertEquals(0, assertions.count(ApiTestAuth.USER_ID));
    }

    /**
     * 🔴 报错里不回显整段原文。
     *
     * <p>请求体上的 {@code nodeCode} 有 {@code @Size(max = 64)} 兜着,所以超长的那个先被
     * 校验拦下;但这条断言守的是<b>无论走哪一支,那 300 字都不会原样出现在响应体和日志里</b>
     * (与 {@code ApiContractTest#rejectionMessagesDoNotEchoUnboundedUserInput} 同一条)。
     */
    @Test
    @DisplayName("🔴 拒绝的时候不回显用户送来的整段原文 —— 那可能就是一整道题")
    void rejectionDoesNotEchoTheWholeInput() throws Exception {
        String stem = "某市 2023 年全年实现地区生产总值 12345.6 亿元,比上年增长 5.4%".repeat(6);

        String body = mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + stem + "\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(stem), "整段原文回到了响应体里 —— 它同时也进了服务端日志");
    }

    // ———————————————————— 五、跨域 ————————————————————

    /**
     * {@code DELETE} 是逐条路径开的({@code ApiCorsConfig} 类注释)。
     *
     * <p>不开这一条的话,浏览器的预检会失败,而表现是<b>「取消」这个按钮在浏览器里静默失灵,
     * 服务端日志一条都看不到</b>。
     */
    @Test
    @DisplayName("CORS:DELETE /api/v1/assertions 放行,而全局白名单里照旧没有 DELETE")
    void corsOpensDeleteForAssertionsOnly() throws Exception {
        mockMvc.perform(options("/api/v1/assertions")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods",
                        Matchers.containsString("DELETE")));

        mockMvc.perform(options("/api/v1/coverage/summary")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- 夹具

    private void assertMastery(String nodeCode) throws Exception {
        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + nodeCode + "\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    private void cancel(String nodeCode) throws Exception {
        mockMvc.perform(delete("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + nodeCode + "\"}"))
                .andExpect(status().isOk());
    }

    private String summaryBody() throws Exception {
        String body = mockMvc.perform(get("/api/v1/coverage/summary"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNotNull(body);
        return body;
    }

    /**
     * 与 {@code ApiContractTest.contractTouches} 同一份数据契约:8 个考点有记录,覆盖 44%。
     *
     * <p>全部挂在 {@link ApiTestAuth#USER_ID} 名下 —— 令牌里的人和夹具里的人必须是同一个,
     * 否则按用户过滤之后一条都读不到,而失败会以「覆盖率怎么是 0」的形式出现。
     */
    private static List<Touch> contractTouches() {
        Instant now = Instant.now();
        List<Touch> ts = new ArrayList<>();
        drill(ts, now, "growth-rate", "粉笔 · 资料分析系统班 L12", 12, 10, 0);
        drill(ts, now, "share-calc", "华图 · 资料速算网课", 9, 8, 1);
        drill(ts, now, "feature-number", "自己刷题 · 2023 国考真题", 7, 6, 3);
        drill(ts, now, "growth-amount", "自己刷题 · 2023 国考真题", 8, 4, 2);
        drill(ts, now, "truncate-divide", "B站 · 资料分析技巧", 6, 2, 4);
        drill(ts, now, "base-value", "中公 · 资料分析专项", 5, 4, 32);
        drill(ts, now, "interval-growth", "中公 · 资料分析专项", 3, 2, 33);
        ts.add(new Touch("t-share-change", ApiTestAuth.USER_ID, "share-change",
                "粉笔 · 资料分析系统班 L12", TouchKind.VOICE, now.minus(Duration.ofDays(5)), null, null));
        return ts;
    }

    private static void drill(List<Touch> ts, Instant now, String node, String source,
                              int practiced, int correct, int daysAgo) {
        ts.add(new Touch("t-" + node, ApiTestAuth.USER_ID, node, source, TouchKind.DRILL,
                now.minus(Duration.ofDays(daysAgo)), new Touch.Drill(practiced, correct), null));
    }

    /**
     * 行为层的只读桩 —— 这三个端点一条记录都不该写。
     *
     * <p>把写侧实现成「一调用就炸」本身就是一条断言:哪天有人让「我已掌握」<b>顺手记一条记录</b>
     * (那是让覆盖率上升的另一条路,而且更隐蔽),这个测试会当场红。
     */
    static final class InMemoryTouchStore implements TouchStore {

        private final List<Touch> touches = new ArrayList<>();

        void reset(List<Touch> seed) {
            touches.clear();
            touches.addAll(seed);
        }

        @Override
        public List<Touch> findAll(long userId) {
            return touches.stream()
                    .filter(t -> t.userId() == userId)
                    .sorted(Comparator.comparing(Touch::occurredAt))
                    .toList();
        }

        @Override
        public List<Touch> findAllAcrossUsers() {
            return touches.stream().sorted(Comparator.comparing(Touch::occurredAt)).toList();
        }

        @Override
        public int countByNodeAcrossUsers(String nodeCode) {
            return (int) touches.stream().filter(t -> t.nodeCode().equals(nodeCode)).count();
        }

        @Override
        public int count(long userId) {
            return (int) touches.stream().filter(t -> t.userId() == userId).count();
        }

        @Override
        public Touch findByClientToken(long userId, String clientToken) {
            throw new AssertionError("「我已掌握」不写记录,不该去查去重键");
        }

        @Override
        public Touch append(Touch touch) {
            throw new AssertionError("🔴 「我已掌握」写了一条记录 —— 那是让覆盖率上升的另一条路,"
                    + "而它比直接改分子更难被发现(决策记录 §5.2:补丁不是解法)");
        }

        @Override
        public Touch delete(long userId, String id) {
            throw new AssertionError("「我已掌握」不删记录");
        }

        @Override
        public int reassign(String fromNodeCode, String toNodeCode) {
            throw new AssertionError("「我已掌握」不改挂记录");
        }
    }

    @TestConfiguration
    static class Fixtures {

        @Bean
        SyllabusSource syllabus() {
            return SyllabusLoader.loadDefault();
        }

        @Bean
        InMemoryTouchStore touchStore() {
            return new InMemoryTouchStore();
        }

        @Bean
        RecordTagStore recordTagStore() {
            return new InMemoryRecordTagStore();
        }

        @Bean
        InMemoryAssertionStore assertionStore() {
            return new InMemoryAssertionStore();
        }

        @Bean
        CoverageReader coverageReader(SyllabusSource syllabus, TouchStore store,
                                      RecordTagStore tagStore, AssertionStore assertionStore,
                                      CoverageService coverage, Clock clock) {
            return new CoverageReader(syllabus, store, tagStore, assertionStore, coverage, clock);
        }
    }
}
