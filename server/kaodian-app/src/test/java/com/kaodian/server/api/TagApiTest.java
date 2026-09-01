package com.kaodian.server.api;

import com.kaodian.server.api.record.TagController;
import com.kaodian.server.config.DomainBeans;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.api.dto.record.MountTagRequest;
import com.kaodian.server.api.dto.record.SuggestTagRequest;
import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.kaodian.server.collect.CandidateRecall;
import com.kaodian.server.collect.InMemoryRecordTagStore;
import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.AssertionStore;
import com.kaodian.server.collect.InMemoryAssertionStore;
import com.kaodian.server.collect.RecordTagStore;
import com.kaodian.server.collect.TagOrigin;
import com.kaodian.server.collect.TaggingService;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.recognize.StubVisionTagger;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.SyllabusLoader;
import com.kaodian.server.syllabus.SyllabusSource;
import com.jayway.jsonpath.JsonPath;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/technical/INDEX.md §6.3 打标那四个端点的接口契约。
 *
 * <h2>这个文件验的是「送不进去」,不是「功能可用」</h2>
 *
 * §6.3 那张表上的红线只有两条,而两条都是关于<b>入口形状</b>的:
 * <ul>
 *   <li>{@code suggest} —— <b>请求体不接受调用方指定标签文本</b>,候选由服务端召回</li>
 *   <li>{@code POST /tags} —— body <b>只接受 {@code nodeId}</b>,不接受 {@code name}。
 *       「只要 API 上没有传入自由文本标签的通道,自由生成的考点就进不了库 ——
 *       <b>无论模型输出什么</b>」</li>
 * </ul>
 * 加上 {@code confirm} 那句「<b>不改 origin</b>」。所以下面一半的用例长得像「发一个坏请求」——
 * 那正是它们该有的样子:红线是靠<b>拒绝</b>成立的,而一条从没拒绝过东西的守卫,
 * 和一条被注释掉的守卫,外观是一样的。
 *
 * <h2>覆盖度跟着一起验</h2>
 *
 * 丢弃一条标签唯一的可见后果就是覆盖度掉一格。只验「返回了 200 且 discarded=true」的话,
 * 一个把丢弃写成「只改标志、不进差集」的实现会全绿,而那正是 {@code P1-7} 要的那件事没做。
 */
@WebMvcTest(controllers = TagController.class)
@Import(DomainBeans.class)     // web 切片不扫 @Configuration,领域装配要显式带进来
class TagApiTest {

    /** 这个来源名召回得出候选(见 {@code CandidateRecallTest}),用来验 suggest 走到第 ② 段。 */
    private static final String RECALLING_SOURCE = "自己刷题 · 增长率专项";

    /** 种子里真实存在的来源名,一个候选都召回不出来。 */
    private static final String SILENT_SOURCE = "粉笔 · 资料分析系统班 L12";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryTouchStore store;

    @Autowired
    private RecordTagStore tags;

    @BeforeEach
    void reset() {
        // 时钟用 DomainBeans 那个 systemUTC(不在这里覆盖:两个 Clock bean 会撞名,
        // 而给它加 @Primary 只会让「到底用了哪个时钟」变成一件要翻装配才知道的事)。
        // 所以记录时刻相对「现在」构造 —— 这个文件验的是接口形状,不验五态的时间边界,
        // 那是 CoverageServiceTest 的活。
        Instant now = Instant.now();
        store.reset(List.of(
                new Touch("t-1", "growth-rate", SILENT_SOURCE, TouchKind.DRILL,
                        now.minusSeconds(3600), new Touch.Drill(10, 8)),
                new Touch("t-2", "share-calc", RECALLING_SOURCE, TouchKind.PHOTO,
                        now.minusSeconds(7200), null)));
        tags.findAll().forEach(t -> tags.deleteByRecord(t.recordId()));
    }

    // ———————————————————— 一、suggest ————————————————————

    @Test
    @DisplayName("🔴 POST /tags/suggest 的请求体里塞一个标签名 → 400,不是被静默忽略")
    void suggestRejectsAnyFieldInTheBody() throws Exception {
        // 静默忽略比报错危险:双方都以为红线没被碰过。这条路上没有「后端反正不看」这种安全感。
        for (String body : new String[]{
                "{\"name\":\"我自己起的考点\"}",
                "{\"label\":\"增长率速算\"}",
                "{\"tag\":\"资料分析·增长率\"}",
                "{\"candidates\":[\"growth-rate\"]}",
                "{\"nodeCode\":\"growth-rate\"}"}) {
            mockMvc.perform(post("/api/records/t-1/tags/suggest")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        }
        assertTrue(tags.findAll().isEmpty(), "被拒的请求一条标签都不该落");
    }

    @Test
    @DisplayName("🔴 请求体的形状里根本没有能装下标签文本的分量 —— 断言的是形状,不是某次拒绝")
    void theSuggestRequestRecordHasNoComponents() {
        // 上面那条验的是「这些键会被拒」。这一条验的是「压根没有键」——
        // 前者会被一次「加个可选字段」推翻,后者要推翻得先改 record 的分量表,而那是一次显式决定。
        assertTrue(SuggestTagRequest.class.getRecordComponents().length == 0,
                "suggest 的请求体一旦有了分量,「候选由服务端召回」这句话就不成立了");
    }

    @Test
    @DisplayName("召回为空 → 200 + NOT_RECALLED,而不是 4xx:记录早就落地了,补标失败什么都没损坏")
    void suggestWithoutRecallIsStillTwoHundred() throws Exception {
        mockMvc.perform(post("/api/records/t-1/tags/suggest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_RECALLED"))
                .andExpect(jsonPath("$.candidateCount").value(0))
                .andExpect(jsonPath("$.tag").doesNotExist())
                // 补标失败不该动覆盖度:两条记录,两个考点,还是碰过的
                .andExpect(jsonPath("$.summary.covered").value(2));
    }

    @Test
    @DisplayName("召回出了候选、但服务端没有素材 → NO_MATERIAL,与「没认出来」分得开")
    void suggestWithoutMaterialSaysSo() throws Exception {
        // 这是今天这个端点的常态,而它诚实地说明原因:原图与转写都不留存(决策记录 §2.3 / 决策记录 §2.2),
        // 服务端手里没有可再看一遍的东西。伪装成「模型没认出来」会让人去查模型,而模型没被调用过。
        mockMvc.perform(post("/api/records/t-2/tags/suggest").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NO_MATERIAL"))
                .andExpect(jsonPath("$.candidateCount").value(6))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("suggest 的响应里带着这条记录当前的全部标签 —— 包括那条没有落库的主标签")
    void suggestReturnsTheCurrentTagList() throws Exception {
        mockMvc.perform(post("/api/records/t-1/tags/suggest"))
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0].id").value("primary-t-1"))
                .andExpect(jsonPath("$.tags[0].primary").value(true))
                .andExpect(jsonPath("$.tags[0].nodeCode").value("growth-rate"))
                .andExpect(jsonPath("$.tags[0].nodeName").value("增长率计算"))
                .andExpect(jsonPath("$.tags[0].origin").value("manual"))
                .andExpect(jsonPath("$.tags[0].countsInCoverage").value(true));
    }

    // ———————————————————— 二、手动挂载 ————————————————————

    @Test
    @DisplayName("🔴 POST /tags 的 body 里给 name 而不是 nodeCode → 400。从树里选,不能新建(R-07)")
    void mountRejectsFreeTextTagNames() throws Exception {
        for (String body : new String[]{
                "{\"name\":\"我自己起的考点\"}",
                "{\"nodeCode\":\"growth-rate\",\"name\":\"增长率速算\"}",
                "{\"nodeCode\":\"growth-rate\",\"label\":\"【某机构】增长率的三种秒杀技巧\"}"}) {
            mockMvc.perform(post("/api/records/t-1/tags")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        }
        assertTrue(tags.findAll().isEmpty());
    }

    /**
     * 🔴 上面两条 {@code UNKNOWN_FIELD} 用例跑的是应用真实配置,而真实配置里
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=true} 一直开着 —— 于是它们只能证明
     * 「两道锁<b>至少有一道</b>在」,证明不了「有两道」。
     *
     * <p>实测:把 {@code MountTagRequest} 上的 {@code @JsonAnySetter} 拿掉,
     * 整个套件<b>一条都不红</b>。所以这里显式关掉那道配置锁,只留 DTO 自己那道。
     * 做法与 {@code ApiContractTest#unknownFieldsAreRejectedEvenWithoutTheMapperFlag} 同一份 ——
     * R-07 必须在配置被人改掉之后依然成立。
     */
    @Test
    @DisplayName("🔴 R-07 第二道锁:关掉 FAIL_ON_UNKNOWN_PROPERTIES,标签文本照样进不来")
    void unknownFieldsAreRejectedEvenWithoutTheMapperFlag() {
        JsonMapper lenient = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)   // 第一道锁,故意拆掉
                .build();

        for (String field : List.of("name", "label", "tag", "tagName", "text")) {
            String mountBody = """
                    {"nodeCode":"growth-rate","%s":"【某机构】增长率的三种秒杀技巧"}
                    """.formatted(field);
            assertUnknownFieldLock(lenient, mountBody, MountTagRequest.class, field);

            // suggest 的请求体一个分量都没有,所以它连「合法字段」都没有 —— 任何键都该被这道锁挡住
            String suggestBody = """
                    {"%s":"【某机构】增长率的三种秒杀技巧"}
                    """.formatted(field);
            assertUnknownFieldLock(lenient, suggestBody, SuggestTagRequest.class, field);
        }
    }

    private static void assertUnknownFieldLock(JsonMapper lenient, String body, Class<?> type, String field) {
        Exception thrown = assertThrows(Exception.class, () -> lenient.readValue(body, type),
                "配置锁拆掉之后 " + field + " 就进 " + type.getSimpleName() + " 了 —— R-07 只剩一行配置撑着");

        UnknownFieldException lock = null;
        for (Throwable t = thrown; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof UnknownFieldException ufe) {
                lock = ufe;
            }
        }
        assertTrue(lock != null,
                "拒是拒了,但不是 DTO 那道锁拒的(" + type.getSimpleName() + "#" + field + "):" + thrown);
    }

    @Test
    @DisplayName("🔴 树外的 code → 400 NODE_NOT_IN_SYLLABUS,不模糊匹配、不新建节点")
    void mountRejectsCodesOutsideTheTree() throws Exception {
        mockMvc.perform(post("/api/records/t-1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"资料分析·增长率\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NODE_NOT_IN_SYLLABUS"));

        mockMvc.perform(post("/api/records/t-1/tags")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nodeCode\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertTrue(tags.findAll().isEmpty());
    }

    @Test
    @DisplayName("挂载成功 → 201 + origin=manual + 覆盖度 +1;同一个考点再挂一次 → 200,不新建")
    void mountingIsIdempotentAndMovesCoverage() throws Exception {
        String created = mockMvc.perform(post("/api/records/t-1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"average-calc\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordId").value("t-1"))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.node.code").value("average-calc"))
                .andExpect(jsonPath("$.summary.covered").value(3))   // 基线 2 → 3
                .andReturn().getResponse().getContentAsString();

        String tagId = JsonPath.read(created, "$.tags[1].id");
        assertFalse(tagId.startsWith("primary-"), "加挂的标签不该占用主标签那个 id");

        mockMvc.perform(post("/api/records/t-1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"average-calc\"}"))
                .andExpect(status().isOk())     // 200,不是 201 —— 服务端什么都没新建
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.summary.covered").value(3));
    }

    // ———————————————————— 三、确认 ————————————————————

    @Test
    @DisplayName("🔴 确认一条自动标签 → 写 confirmedAt,origin 仍然是 auto(docs/technical/INDEX.md §6.3)")
    void confirmDoesNotRewriteOrigin() throws Exception {
        // 直接往库里放一条 auto 标签:今天没有任何端点能造出 auto(suggest 拿不到素材),
        // 而 origin 不可变这条恰恰只有在 auto 上才验得出来。
        tags.put(new RecordTag("tag-auto", "t-1", "average-calc", 0.91, TagOrigin.AUTO, null, false));

        mockMvc.perform(post("/api/records/t-1/tags/tag-auto/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[1].id").value("tag-auto"))
                .andExpect(jsonPath("$.tags[1].origin").value("auto"))
                .andExpect(jsonPath("$.tags[1].confirmedAt").exists());

        // 出接口那一份对了不算数,库里那一行才算:响应体可以是从一个改过的副本里渲染出来的。
        assertTrue(tags.find("tag-auto").origin() == TagOrigin.AUTO,
                "确认把 auto 改成了 manual —— 准确率口径(标对的/标了的)从此算不出来");
    }

    @Test
    @DisplayName("确认不改变覆盖度 —— 判据只有 discarded,「计入覆盖度」说的是它不会掉出去")
    void confirmDoesNotChangeCoverage() throws Exception {
        tags.put(new RecordTag("tag-auto", "t-1", "average-calc", 0.91, TagOrigin.AUTO, null, false));

        mockMvc.perform(post("/api/records/t-1/tags/tag-auto/confirm"))
                .andExpect(jsonPath("$.summary.covered").value(3))
                .andExpect(jsonPath("$.tags[1].countsInCoverage").value(true));
    }

    @Test
    @DisplayName("确认主标签也走得通 —— 它本来不占行,确认之后才需要一行来记住这个状态")
    void confirmingThePrimaryTagWorks() throws Exception {
        mockMvc.perform(post("/api/records/t-1/tags/primary-t-1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0].primary").value(true))
                .andExpect(jsonPath("$.tags[0].origin").value("manual"));
    }

    // ———————————————————— 四、丢弃 ————————————————————

    @Test
    @DisplayName("🔴 丢弃 → 标签还在列表上(可见),但覆盖度掉下去(不计覆盖度)—— P1-7 的两半")
    void discardKeepsTheTagVisibleAndLowersCoverage() throws Exception {
        mockMvc.perform(post("/api/records/t-1/tags/primary-t-1/discard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))                 // 可见
                .andExpect(jsonPath("$.tags[0].discarded").value(true))
                .andExpect(jsonPath("$.tags[0].countsInCoverage").value(false))
                .andExpect(jsonPath("$.tags[0].origin").value("manual"))         // 丢弃同样不动 origin
                .andExpect(jsonPath("$.summary.covered").value(1))               // 基线 2 → 1
                .andExpect(jsonPath("$.node.state").value("EMPTY"))              // 那个考点回到盲区
                .andExpect(jsonPath("$.node.touchCount").value(0));
    }

    @Test
    @DisplayName("丢弃标签不等于删记录 —— 记录一条都没少")
    void discardingATagDoesNotDeleteTheRecord() throws Exception {
        mockMvc.perform(post("/api/records/t-1/tags/primary-t-1/discard"))
                .andExpect(status().isOk());

        // 错的只是它挂在哪儿,不该把「我那天学过东西」一起抹掉。
        assertTrue(store.findAll().stream().anyMatch(t -> t.id().equals("t-1")));
    }

    // ———————————————————— 五、找不到 ————————————————————

    @Test
    @DisplayName("🔴 记录不存在 → 404,而且报错消息里不回显那个 id")
    void anUnknownRecordIsFourOhFourWithoutEchoingTheId() throws Exception {
        // 路径变量没有任何长度上限,能塞满一整个请求行;而报错消息会同时进响应体和服务端日志。
        // 那个 id 是客户端自己刚发过来的,回显给它一个字的信息都不增加。
        String stem = "某年某省考资料分析材料第一段" + "占位".repeat(200);

        String body = mockMvc.perform(post("/api/records/" + stem + "/tags/suggest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("占位占位"), "报错消息把用户送来的那一串回显了出来:" + body);
    }

    @Test
    @DisplayName("标签不属于这条记录 → 404 TAG_NOT_FOUND,与「记录不存在」用不同的 code")
    void aTagFromAnotherRecordIsFourOhFour() throws Exception {
        // 合并成一个 404 的话,前端分不清该刷新时间线还是该刷新这条记录的标签列表。
        for (String path : new String[]{
                "/api/records/t-1/tags/primary-t-2/confirm",     // 是别人的主标签
                "/api/records/t-1/tags/tag-不存在/discard"}) {
            mockMvc.perform(post(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TAG_NOT_FOUND"));
        }
        assertTrue(tags.findAll().isEmpty(), "被拒的请求一行都不该落");
    }

    // ---------------------------------------------------------------- 装配

    /** 按 {@link TouchStore} 契约实现的内存版 —— 这个文件只关心标签,记录怎么落地是另一条线。 */
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
        public Touch findByClientToken(String clientToken) {
            return null;
        }

        @Override
        public Touch append(Touch touch) {
            touches.add(touch);
            return touch;
        }

        @Override
        public Touch delete(String id) {
            return touches.stream().filter(t -> t.id().equals(id)).findFirst()
                    .map(t -> {
                        touches.remove(t);
                        return t;
                    }).orElse(null);
        }

        @Override
        public int count() {
            return touches.size();
        }

        @Override
        public int reassign(String fromNodeCode, String toNodeCode) {
            throw new UnsupportedOperationException("打标不改挂记录");
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
        CoverageReader coverageReader(SyllabusSource syllabus, TouchStore store,
                                      RecordTagStore tagStore, AssertionStore assertionStore,
                                      CoverageService coverage, Clock clock) {
            return new CoverageReader(syllabus, store, tagStore, assertionStore, coverage, clock);
        }

        /** 「我已掌握」。它不进覆盖度的分子(决策记录 §5.2:补丁不是解法),但 CoverageReader 要读它。 */
        @Bean
        AssertionStore assertionStore() {
            return new InMemoryAssertionStore();
        }

        @Bean
        TaggingService taggingService(TouchStore store, RecordTagStore tagStore, SyllabusSource syllabus,
                                      CandidateRecall recall, VisionTagger tagger, Clock clock) {
            return new TaggingService(store, tagStore, syllabus, recall, tagger, clock);
        }

        /**
         * 诚实失败的占位实现 —— <b>一律 NO_MATCH</b>。
         *
         * <p>这里不用「一调用就炸」的替身:{@code /tags/suggest} 今天走不到模型那一步
         * (服务端没有素材),而那件事由 {@code TaggingServiceTest} 用会炸的替身钉着。
         * 在这一层再钉一遍,会把「接口契约」和「管线顺序」两件事绑在一起,
         * 以后接上图片端点时这个文件会因为一件与它无关的事情而红。
         */
        @Bean
        VisionTagger visionTagger() {
            return new StubVisionTagger();
        }
    }
}
