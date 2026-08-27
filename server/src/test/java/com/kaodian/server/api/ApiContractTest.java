package com.kaodian.server.api;

import com.kaodian.server.api.dto.CreateRecordRequest;
import com.kaodian.server.api.dto.UnknownFieldException;
import com.kaodian.server.collect.CaptureService;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusLoader;
import com.kaodian.server.syllabus.SyllabusSource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 把接口契约钉在与设计稿同一组数字上。
 *
 * <p>行为层用的是 {@code CoverageServiceTest} 里那份一模一样的数据契约:
 * <b>18 个考点 / 8 个有记录 / 覆盖 44% / 10 个空白 / 2 组整块空白</b>。
 * 两个测试用同一组数字不是重复 —— 领域层那个钉的是<b>算得对不对</b>,
 * 这个钉的是<b>吐出去的还是不是同一个数</b>。中间只要有人在控制器里顺手重算一次,
 * 这里立刻红。
 *
 * <h2>为什么用 {@code @WebMvcTest} 而不是整个应用</h2>
 *
 * 存储实现({@code FileTouchStore})属于另一条线,它换成什么都不该影响接口契约 ——
 * {@link TouchStore} 是接口,这里就按接口给一个内存实现。docs/10 §2.2:
 * <b>包之间只通过接口调用</b>,测试是这句话第一个受益的地方。
 */
@WebMvcTest(controllers = {
        SyllabusController.class,
        CoverageController.class,
        TimelineController.class,
        RecordController.class})
@Import(ApiBeans.class)     // web 切片不扫 @Configuration,领域装配要显式带进来
class ApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryTouchStore store;

    @BeforeEach
    void resetToContract() {
        store.reset(contractTouches());
    }

    // ---------------------------------------------------------------- 查询

    @Test
    @DisplayName("GET /api/coverage/summary —— total=18 covered=8 percent=44,与设计稿逐字一致")
    void summaryMatchesDesignContract() throws Exception {
        mockMvc.perform(get("/api/coverage/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(18))
                .andExpect(jsonPath("$.covered").value(8))
                .andExpect(jsonPath("$.percent").value(44))
                .andExpect(jsonPath("$.empty").value(10))
                .andExpect(jsonPath("$.whollyEmptyGroups").value(2));
    }

    @Test
    @DisplayName("五态分布带枚举名 + 中文 label,顺序固定 —— 前端不硬编码中文")
    void stateDistributionCarriesBothNameAndLabel() throws Exception {
        mockMvc.perform(get("/api/coverage/summary"))
                .andExpect(jsonPath("$.distribution.length()").value(5))
                .andExpect(jsonPath("$.distribution[0].state").value("EMPTY"))
                .andExpect(jsonPath("$.distribution[0].label").value("空白"))
                .andExpect(jsonPath("$.distribution[0].count").value(10))
                .andExpect(jsonPath("$.distribution[1].state").value("TOUCHED_ONLY"))
                .andExpect(jsonPath("$.distribution[1].count").value(1))
                .andExpect(jsonPath("$.distribution[2].state").value("RUSTY"))
                .andExpect(jsonPath("$.distribution[2].count").value(2))
                .andExpect(jsonPath("$.distribution[3].state").value("WEAK"))
                .andExpect(jsonPath("$.distribution[3].count").value(2))
                .andExpect(jsonPath("$.distribution[4].state").value("STABLE"))
                .andExpect(jsonPath("$.distribution[4].count").value(3));
    }

    @Test
    @DisplayName("GET /api/syllabus/tree —— 整棵树一次返回,顶上的 44% 与概览同源")
    void treeReturnsWholeModuleInOneShot() throws Exception {
        mockMvc.perform(get("/api/syllabus/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject.display").value("山东省考 · 行测 · 资料分析"))
                .andExpect(jsonPath("$.summary.percent").value(44))
                .andExpect(jsonPath("$.groups.length()").value(5))
                .andExpect(jsonPath("$.groups[0].code").value("growth"))
                .andExpect(jsonPath("$.groups[0].nodes.length()").value(7))
                // 整块空白 —— 树相对扁平清单的唯一优势,落在「倍数与比较」与「效应类」上
                .andExpect(jsonPath("$.groups[1].name").value("倍数与比较"))
                .andExpect(jsonPath("$.groups[1].whollyEmpty").value(true))
                .andExpect(jsonPath("$.groups[2].name").value("效应类"))
                .andExpect(jsonPath("$.groups[2].whollyEmpty").value(true))
                .andExpect(jsonPath("$.groups[0].whollyEmpty").value(false))
                // 状态两个字段一起给
                .andExpect(jsonPath("$.groups[0].nodes[0].state").value("STABLE"))
                .andExpect(jsonPath("$.groups[0].nodes[0].stateLabel").value("稳"));
    }

    @Test
    @DisplayName("GET /api/syllabus/nodes/{code} —— 四统计 + 我的触达,🔴 没有讲解字段")
    void nodeDetailHasNoTeachingFields() throws Exception {
        mockMvc.perform(get("/api/syllabus/nodes/growth-amount"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("增长量计算"))
                .andExpect(jsonPath("$.groupName").value("增长类"))
                .andExpect(jsonPath("$.recent5yCount").value(8))
                .andExpect(jsonPath("$.state").value("WEAK"))
                .andExpect(jsonPath("$.stateLabel").value("弱"))
                .andExpect(jsonPath("$.touchCount").value(1))
                .andExpect(jsonPath("$.practiced").value(8))
                .andExpect(jsonPath("$.correct").value(4))
                .andExpect(jsonPath("$.accuracy").value(Matchers.closeTo(0.5, 1e-9)))
                .andExpect(jsonPath("$.sources[0]").value("自己刷题 · 2023 国考真题"))
                // 🔴 讲解类字段一个都不能出现
                .andExpect(jsonPath("$.explanation").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.difficulty").doesNotExist())
                .andExpect(jsonPath("$.mastery").doesNotExist());
    }

    @Test
    @DisplayName("没练过的考点 accuracy 是 null —— 界面显示「—」,不是 0%")
    void untouchedNodeHasNullAccuracy() throws Exception {
        mockMvc.perform(get("/api/syllabus/nodes/average-calc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EMPTY"))
                .andExpect(jsonPath("$.accuracy").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.latestAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.sources.length()").value(0));
    }

    @Test
    @DisplayName("查一个树里没有的考点 → 404,不做模糊匹配、不返回最接近的")
    void unknownNodeIsNotGuessed() throws Exception {
        mockMvc.perform(get("/api/syllabus/nodes/增长率那个"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/coverage/blindspots —— Top 5 的名次、分数与设计稿一致(北极星落点)")
    void blindSpotsMatchDesignContract() throws Exception {
        mockMvc.perform(get("/api/coverage/blindspots").param("top", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedTop").value(5))
                .andExpect(jsonPath("$.returned").value(5))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].name").value("增长量计算"))
                .andExpect(jsonPath("$.items[0].blindScore").value(Matchers.closeTo(6.4, 1e-9)))
                .andExpect(jsonPath("$.items[1].name").value("平均数计算"))
                .andExpect(jsonPath("$.items[1].blindScore").value(Matchers.closeTo(6.0, 1e-9)))
                .andExpect(jsonPath("$.items[2].name").value("截位直除"))
                .andExpect(jsonPath("$.items[2].blindScore").value(Matchers.closeTo(5.6, 1e-9)))
                // 并列 5.0 —— 按树序,现期量计算在倍数计算之前
                .andExpect(jsonPath("$.items[3].name").value("现期量计算"))
                .andExpect(jsonPath("$.items[4].name").value("倍数计算"));
    }

    @Test
    @DisplayName("blindspots 的 top 越界被拒;默认 20")
    void blindSpotsTopIsValidated() throws Exception {
        mockMvc.perform(get("/api/coverage/blindspots").param("top", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/coverage/blindspots").param("top", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/coverage/blindspots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedTop").value(20));
    }

    @Test
    @DisplayName("🔴 GET /api/timeline —— 只有来源名、时间、方式、考点,没有任何内容字段")
    void timelineCarriesNoContent() throws Exception {
        mockMvc.perform(get("/api/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.returned").value(8))
                // 倒序:最近发生的在最上面(growth-rate 是「今天」那条)
                .andExpect(jsonPath("$.items[0].nodeCode").value("growth-rate"))
                .andExpect(jsonPath("$.items[0].kind").value("DRILL"))
                .andExpect(jsonPath("$.items[0].kindLabel").value("记做题"))
                .andExpect(jsonPath("$.items[0].sourceName").value("粉笔 · 资料分析系统班 L12"))
                .andExpect(jsonPath("$.items[0].nodeName").value("增长率计算"))
                .andExpect(jsonPath("$.items[0].occurredAt").isString())   // ISO-8601,不是 epoch 数字
                .andExpect(jsonPath("$.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.items[0].text").doesNotExist())
                .andExpect(jsonPath("$.items[0].transcript").doesNotExist())
                .andExpect(jsonPath("$.items[0].imageUrl").doesNotExist());
    }

    // ---------------------------------------------------------------- 采集

    @Test
    @DisplayName("POST /api/records —— 记一笔之后,那个考点的状态立刻跟着变")
    void createRecordMovesTheNode() throws Exception {
        mockMvc.perform(post("/api/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DRILL","sourceName":"自己刷题 · 2024 省考真题",
                                 "nodeCode":"average-calc","practiced":10,"correct":9}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.record.nodeCode").value("average-calc"))
                .andExpect(jsonPath("$.record.kindLabel").value("记做题"))
                .andExpect(jsonPath("$.record.id").isNotEmpty())
                .andExpect(jsonPath("$.node.state").value("STABLE"))   // 原来是 EMPTY
                .andExpect(jsonPath("$.node.practiced").value(10));

        // 覆盖度从 8/18 变成 9/18
        mockMvc.perform(get("/api/coverage/summary"))
                .andExpect(jsonPath("$.covered").value(9))
                .andExpect(jsonPath("$.percent").value(50));
    }

    @Test
    @DisplayName("🔴 R-07:POST /api/records 传自由文本标签被拒绝 —— 接口上没有这条通道")
    void freeTextTagsAreRejected() throws Exception {
        for (String field : List.of("tag", "name", "label", "nodeName", "keywords")) {
            mockMvc.perform(post("/api/records")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"kind":"MANUAL","sourceName":"粉笔 · 资料分析系统班 L12",
                                     "nodeCode":"growth-rate","%s":"我自己想的考点"}
                                    """.formatted(field)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"))
                    .andExpect(jsonPath("$.message").value(Matchers.containsString(field)));
        }
    }

    @Test
    @DisplayName("🔴 R-07:nodeCode 不在骨架树里 → 400,不猜最接近的考点(宁缺毋滥)")
    void unknownNodeCodeIsRejectedNotFuzzyMatched() throws Exception {
        mockMvc.perform(post("/api/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"MANUAL","sourceName":"粉笔 · 资料分析系统班 L12",
                                 "nodeCode":"增长率相关的那一类"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NODE_NOT_IN_SYLLABUS"));

        mockMvc.perform(get("/api/coverage/summary")).andExpect(jsonPath("$.covered").value(8));
    }

    @Test
    @DisplayName("🔴 内容字段进不来:body 里带 content/transcript/imageUrl 一律 400")
    void contentFieldsCannotBeSmuggledIn() throws Exception {
        for (String field : List.of("content", "text", "question", "transcript", "imageUrl", "note")) {
            mockMvc.perform(post("/api/records")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"kind":"PHOTO","sourceName":"粉笔 · 资料分析系统班 L12",
                                     "nodeCode":"growth-rate","%s":"2023 年全国粮食产量为..."}
                                    """.formatted(field)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        }
    }

    @Test
    @DisplayName("正确率是用户填的两个数,但对多于练要拒绝;两个数必须同进同出")
    void drillNumbersAreValidated() throws Exception {
        mockMvc.perform(post("/api/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DRILL","sourceName":"自己刷题","nodeCode":"growth-rate",
                                 "practiced":3,"correct":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 只给一个 —— 不替用户把另一个填成 0
        mockMvc.perform(post("/api/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DRILL","sourceName":"自己刷题","nodeCode":"growth-rate","practiced":3}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("来源名有长度上限 —— 挡住把题干塞进「来源名」这条绕路")
    void sourceNameIsLengthCapped() throws Exception {
        mockMvc.perform(post("/api/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"MANUAL","sourceName":"%s","nodeCode":"growth-rate"}
                                """.formatted("题".repeat(200))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("sourceName")));
    }

    @Test
    @DisplayName("必填项缺失 → 400,且错误体里只有 code/message/traceId,没有堆栈")
    void errorBodyHasNoStackTrace() throws Exception {
        mockMvc.perform(post("/api/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceName":"自己刷题","nodeCode":"growth-rate"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    // ---------------------------------------------------------------- 跨域

    @Test
    @DisplayName("CORS:放行 Vite dev server;方法白名单里没有 DELETE")
    void corsAllowsViteDevServerOnly() throws Exception {
        mockMvc.perform(options("/api/coverage/summary")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                // 方法白名单里没有 DELETE —— 要开写入/删除必须显式改配置,而「必须显式改」正是要的效果
                .andExpect(header().string("Access-Control-Allow-Methods",
                        Matchers.not(Matchers.containsString("DELETE"))));

        mockMvc.perform(options("/api/coverage/summary")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- 形状

    @Test
    @DisplayName("🔴 全部 DTO 的字段表里不存在能装课程内容的位置 —— 断言的是形状,不是某次赋值")
    void noDtoHasARoomForCourseContent() {
        List<String> forbidden = List.of(
                "content", "text", "body", "question", "stem", "transcript", "audioUrl",
                "imageUrl", "image", "answer", "explanation", "solution", "note", "rawText", "ocrText");

        List<Class<?>> dtos = dtoRecords();
        assertTrue(dtos.size() >= 10, "DTO 扫描没扫到东西,这个测试就形同虚设:" + dtos);

        for (Class<?> dto : dtos) {
            List<String> fields = Arrays.stream(dto.getRecordComponents())
                    .map(RecordComponent::getName).toList();
            for (String bad : forbidden) {
                assertFalse(fields.contains(bad),
                        dto.getSimpleName() + " 不允许出现装内容的字段(01 §2.2 不碰内容):" + bad);
            }
        }
    }

    /**
     * 🔴 这是这个文件里唯一一个<b>不走 MockMvc</b> 的红线测试,理由很具体。
     *
     * <p>其余那些 {@code UNKNOWN_FIELD} 测试跑的是应用真实配置,而真实配置里
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=true} 一直开着 —— 于是它们只能证明「两道锁至少有一道在」,
     * 证明不了「有两道」。实测把那行配置注释掉,{@code {"tag":"我自己想的考点"}} 会返回
     * <b>201 Created</b>,而整个测试套件<b>只有这一个测试会红</b>。
     *
     * <p>所以这里显式关掉那道配置锁,只留 DTO 自己那道:R-07 必须在配置被人改掉之后依然成立。
     */
    @Test
    @DisplayName("🔴 R-07 第二道锁:就算关掉 FAIL_ON_UNKNOWN_PROPERTIES,自由文本标签照样进不来")
    void unknownFieldsAreRejectedEvenWithoutTheMapperFlag() {
        JsonMapper lenient = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)   // 第一道锁,故意拆掉
                .build();

        for (String field : List.of("tag", "name", "label", "content", "transcript", "imageUrl")) {
            String body = """
                    {"kind":"MANUAL","sourceName":"粉笔 · 资料分析系统班 L12",
                     "nodeCode":"growth-rate","%s":"2023 年全国粮食产量为..."}
                    """.formatted(field);

            Exception thrown = assertThrows(Exception.class,
                    () -> lenient.readValue(body, CreateRecordRequest.class),
                    "配置锁拆掉之后 " + field + " 就进来了 —— R-07 只剩一行配置撑着");

            UnknownFieldException lock = null;
            for (Throwable t = thrown; t != null && t != t.getCause(); t = t.getCause()) {
                if (t instanceof UnknownFieldException ufe) {
                    lock = ufe;
                }
            }
            assertNotNull(lock, "拦下它的必须是 DTO 上那道锁,不是别的解析错误:" + thrown);
            assertEquals(field, lock.fieldName());
            // 🔴 值是用户送来的原文,一个字都不许进异常消息(它会一路走到日志)
            assertFalse(lock.getMessage().contains("粮食产量"),
                    "异常消息里只能有字段名,不能有字段的值");
        }
    }

    @Test
    @DisplayName("🔴 报错回声有长度上限 —— 路径变量/查询参数没有 @Size 管着,别成了写日志的通道")
    void rejectionMessagesDoNotEchoUnboundedUserInput() throws Exception {
        String pastedStem = "2023 年全国粮食总产量为 13908 亿斤,比上年增加 177 亿斤".repeat(40);

        String nodeMessage = mockMvc.perform(get("/api/syllabus/nodes/{code}", pastedStem))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(nodeMessage.length() < pastedStem.length(),
                "整段原文被原样回声了 —— 它同时会进服务端日志");

        String subjectMessage = mockMvc.perform(get("/api/syllabus/tree").param("subject", pastedStem))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_LOADED"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(subjectMessage.length() < pastedStem.length(),
                "subject 是查询参数,没有 @Size 管得着它 —— 回声必须自己截断");
    }

    @Test
    @DisplayName("🔴 写入请求体的字段表被钉死:五个,不多不少")
    void createRequestShapeIsPinned() {
        List<String> fields = Arrays.stream(CreateRecordRequest.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertEquals(List.of("kind", "sourceName", "nodeCode", "practiced", "correct"), fields,
                "「只接受 nodeCode,不接受 name」是 R-07 在接口层的实现 —— 加字段前先回去看 docs/10 §6.3");
    }

    private static List<Class<?>> dtoRecords() {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AssignableTypeFilter(Object.class));
        Set<BeanDefinition> candidates = provider.findCandidateComponents("com.kaodian.server.api.dto");
        List<Class<?>> result = new ArrayList<>();
        for (BeanDefinition bd : candidates) {
            try {
                Class<?> c = Class.forName(bd.getBeanClassName());
                if (c.isRecord()) {
                    result.add(c);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 夹具

    /**
     * 与 {@code CoverageServiceTest} 同一份数据契约:8 个考点有记录,其余 10 个一条都没有。
     *
     * <p>时间用「距现在多少天」而不是固定时刻,因为接口层的 {@code Clock} 就是系统时钟 ——
     * 这样这个测试验的是<b>真实链路</b>,不是一个被冻结的时间点。
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
        ts.add(new Touch("t-share-change", "share-change",
                "粉笔 · 资料分析系统班 L12", TouchKind.VOICE, now.minus(Duration.ofDays(5)), null));
        return ts;
    }

    private static void drill(List<Touch> ts, Instant now, String node, String source,
                              int practiced, int correct, int daysAgo) {
        ts.add(new Touch("t-" + node, node, source, TouchKind.DRILL,
                now.minus(Duration.ofDays(daysAgo)), new Touch.Drill(practiced, correct)));
    }

    /**
     * 按 {@link TouchStore} 契约实现的内存版。
     *
     * <p>不用 Mockito 打桩,是因为这里要验的恰恰是「写进去之后读出来会变」——
     * 打桩只能验控制器调没调 store,验不了覆盖度跟着动。
     */
    static final class InMemoryTouchStore implements TouchStore {

        private final List<Touch> touches = new ArrayList<>();

        void reset(List<Touch> seed) {
            touches.clear();
            touches.addAll(seed);
        }

        @Override
        public List<Touch> findAll() {
            return touches.stream().sorted(Comparator.comparing(Touch::occurredAt)).toList();
        }

        @Override
        public List<Touch> findByNode(String nodeCode) {
            return touches.stream().filter(t -> t.nodeCode().equals(nodeCode)).toList();
        }

        @Override
        public Touch append(Touch touch) {
            touches.add(touch);
            return touch;
        }

        @Override
        public int count() {
            return touches.size();
        }

        /** 契约见 {@link TouchStore#reassign} —— 搬家,不扔东西:只换 nodeCode,其余原样。 */
        @Override
        public int reassign(String fromNodeCode, String toNodeCode) {
            int moved = 0;
            for (int i = 0; i < touches.size(); i++) {
                Touch t = touches.get(i);
                if (t.nodeCode().equals(fromNodeCode)) {
                    touches.set(i, new Touch(t.id(), toNodeCode, t.sourceName(), t.kind(),
                            t.occurredAt(), t.drill()));
                    moved++;
                }
            }
            return moved;
        }
    }

    @TestConfiguration
    static class Fixtures {

        /**
         * 这个测试只验<b>查询与采集</b>的接口契约,不验考点管理,所以给一棵固定的树。
         *
         * <p>{@link Syllabus} 自己就实现了 {@link SyllabusSource}(见那个接口的说明),
         * 于是这里不必为「树不会变」的场景造一个假的 store。
         * 骨架可写之后的行为由 {@code SyllabusAdminApiTest} 用真的
         * {@code FileSyllabusStore} 去验。
         */
        @Bean
        SyllabusSource syllabus() {
            return SyllabusLoader.loadDefault();
        }

        /** {@link CoverageReader} 是 {@code @Component},web 切片不扫它。 */
        @Bean
        CoverageReader coverageReader(SyllabusSource syllabus, TouchStore store,
                                      CoverageService coverage, Clock clock) {
            return new CoverageReader(syllabus, store, coverage, clock);
        }

        /** {@link CaptureService} 同理。写入端点委托给它,不自己 new Touch。 */
        @Bean
        CaptureService captureService(TouchStore store, VisionTagger tagger,
                                      SyllabusSource syllabus, Clock clock) {
            return new CaptureService(store, tagger, syllabus, clock);
        }

        /**
         * POST /api/records 这条路压根不调用模型(用户已经从树里挑好了考点),
         * 所以这里给一个「一调用就炸」的实现:<b>它一旦被调用,测试就会红</b> ——
         * 这本身就是一条断言,钉住 docs/11 §二「手动记录永不消耗 AI 额度」。
         */
        @Bean
        VisionTagger visionTagger() {
            return (image, mimeType, candidates) -> {
                throw new AssertionError("手动记一笔不该调用识别 —— 额度用尽 ≠ 记不了(docs/11 §二)");
            };
        }

        @Bean
        InMemoryTouchStore touchStore() {
            return new InMemoryTouchStore();
        }
    }
}
