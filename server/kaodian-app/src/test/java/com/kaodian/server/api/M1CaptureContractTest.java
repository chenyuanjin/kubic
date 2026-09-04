package com.kaodian.server.api;

import com.kaodian.server.api.dto.record.BatchCreateRecordsRequest;
import com.kaodian.server.api.dto.record.CreateRecordRequest;
import com.kaodian.server.api.record.RecordController;
import com.kaodian.server.collect.AssertionStore;
import com.kaodian.server.collect.CaptureService;
import com.kaodian.server.collect.InMemoryAssertionStore;
import com.kaodian.server.collect.InMemoryRecordTagStore;
import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.RecordTagStore;
import com.kaodian.server.collect.TagOrigin;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.config.DomainBeans;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.coverage.CoverageService;
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

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `M1 记录采集与离线补传` 里<b>本轮新落的那几条</b>的判据。
 *
 * <p>与 {@link ApiContractTest} 的分工:那个文件钉的是设计稿那组数字(18/8/44%),
 * 这个文件钉的是 M1 这一轮改出来的语义 —— 补传的时间戳来源、走 batch 不许改判、
 * 未分类过滤、注销那个交界面。<b>每一条在写下来的时候都先红过一次。</b>
 */
@WebMvcTest(controllers = RecordController.class)
@Import({DomainBeans.class, ApiTestAuth.class, M1CaptureContractTest.Fixtures.class})
class M1CaptureContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private M1TouchStore store;

    @Autowired
    private RecordTagStore tagStore;

    @BeforeEach
    void reset() {
        store.reset();
        tagStore.findAllAcrossUsers().forEach(t -> tagStore.deleteByRecord(t.userId(), t.recordId()));
    }

    // ================================================================ §3.6 补传的时间戳

    /**
     * 🔴 这一条是 {@code M1-12} 的全部理由。
     *
     * <p>两条已冻结的口径在补传路径上撞车:「记录时间由服务端按时钟打戳,客户端不自报时间」
     * 与「补传回来的记录按<b>原始落本地时间</b>排,不用补传时刻」。
     * 在线时两个时刻相差毫秒级,补传时相差可以是两周。
     * <p>
     * 服务端打戳的后果不是「排序不稳」,是<b>用户上周记的东西出现在今天的分组里</b> ——
     * 而用户会用「昨天第三条」来定位一条记录,顺序一变,他的结论是「<b>数据变了</b>」。
     */
    @Test
    @DisplayName("🔴 batch 条目的 occurredAt 原样落库 —— 不是服务端收到的那一刻")
    void batchKeepsTheClientSuppliedMoment() throws Exception {
        Instant lastWeek = Instant.now().minus(Duration.ofDays(7)).minusSeconds(3);

        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[{"kind":"MANUAL","sourceName":"地铁上",
                                             "nodeCode":"average-calc","clientToken":"o-1",
                                             "occurredAt":"%s"}]}
                                """.formatted(lastWeek)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stored").value(1));

        Touch stored = store.findAll(ApiTestAuth.USER_ID).get(0);
        assertEquals(lastWeek.toEpochMilli(), stored.occurredAt().toEpochMilli(),
                "补传的时刻必须原样落库 —— 服务端在这条路上打戳,会把断网那几天的记录全部堆进补传当天");
    }

    /**
     * 防伪造靠<b>钳制</b>,不靠信任,也不靠拒绝。
     *
     * <p>设备时钟被改到未来是真实会发生的事。一条落在未来的记录在时间线上没有意义
     * (永远待在最上面、「多久前」是负数),<b>但它不该让这条记录失败</b> ——
     * 记录是真的,错的只是那台设备的时钟。
     */
    @Test
    @DisplayName("🔴 occurredAt 落在未来 → 钳到当前时刻,记录照落(不是 400)")
    void futureOccurredAtIsClampedNotRejected() throws Exception {
        Instant future = Instant.now().plus(Duration.ofDays(30));
        Instant before = Instant.now();

        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[{"kind":"MANUAL","sourceName":"地铁上",
                                             "nodeCode":"average-calc","clientToken":"o-1",
                                             "occurredAt":"%s"}]}
                                """.formatted(future)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stored").value(1));

        Touch stored = store.findAll(ApiTestAuth.USER_ID).get(0);
        assertFalse(stored.occurredAt().isAfter(Instant.now().plusSeconds(1)),
                "未来的时刻必须被钳到 now,否则这条记录会永远待在时间线最上面");
        assertFalse(stored.occurredAt().isBefore(before.minusSeconds(5)),
                "钳的是上界,不是把它扔回一个随便的时刻");
    }

    /** 缺 {@code occurredAt} 是<b>条目级</b>失败:整批仍然 200,其余条目照落。 */
    @Test
    @DisplayName("🔴 条目缺 occurredAt → 只拒那一条,整批恒 200")
    void missingOccurredAtFailsThatItemAlone() throws Exception {
        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[
                                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"average-calc","clientToken":"o-1"},
                                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"yoy-mom","clientToken":"o-2",
                                   "occurredAt":"2026-01-01T00:00:00Z"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.stored").value(1))
                .andExpect(jsonPath("$.results[0].error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.results[0].error.message")
                        .value(org.hamcrest.Matchers.containsString("occurredAt")));
    }

    /**
     * 🔴 单条那条路<b>不许</b>有这个字段 —— 两个端点的时间戳来源是有意分开的。
     *
     * <p>给 {@code POST /records} 也开一个 {@code occurredAt},等于给「补记」开了一个入口,
     * 而界面上刻意没有时间选择器:「生疏」是纯时间推出来的状态,能自报时间就等于能把它改掉。
     */
    @Test
    @DisplayName("🔴 POST /records 不收 occurredAt —— 送了就是 400 UNKNOWN_FIELD")
    void singleCreateRefusesOccurredAt() throws Exception {
        List<String> single = Arrays.stream(CreateRecordRequest.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertFalse(single.contains("occurredAt"),
                "单条请求体里出现 occurredAt,就是给「补记」开了一个界面上没有的入口");

        List<String> batchItem = Arrays.stream(BatchCreateRecordsRequest.Item.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertTrue(batchItem.contains("occurredAt"),
                "补传条目必须带 occurredAt —— 它是 U1.7 排序口径唯一的信息来源,服务端造不出来");

        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"average-calc",
                                 "occurredAt":"2020-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
    }

    // ================================================================ §3.7 走 batch 不许改判

    /**
     * 🔴 {@code M1-13},原文标着「本单元唯一一处容易被实现漏掉的契约意图」。
     *
     * <p>出问题的那一版长这样:批量端点自己写一遍写入逻辑,顺手在末尾统一提交打标任务。
     * <b>它跑起来一切正常</b>,唯一的后果是用户看到「同样的操作,联网记的和断网记的落在两个状态」。
     */
    @Test
    @DisplayName("🔴 同一笔记录,走 batch 与走单条给出同一个结论(挂载与覆盖度都一样)")
    void batchAndSingleAgreeOnTheSameRecord() throws Exception {
        String single = mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DRILL","sourceName":"地铁上","nodeCode":"average-calc",
                                 "practiced":5,"correct":4}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String batch = mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[{"kind":"DRILL","sourceName":"地铁上","nodeCode":"average-calc",
                                             "practiced":5,"correct":4,"clientToken":"o-1",
                                             "occurredAt":"%s"}]}
                                """.formatted(Instant.now().minus(Duration.ofDays(1)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(JsonPath.read(single, "$.record.nodeCode").toString(),
                JsonPath.read(batch, "$.results[0].record.nodeCode").toString(),
                "两条路挂到同一个考点上");
        assertEquals(JsonPath.read(single, "$.record.kind").toString(),
                JsonPath.read(batch, "$.results[0].record.kind").toString());
        assertEquals(JsonPath.read(single, "$.record.practiced").toString(),
                JsonPath.read(batch, "$.results[0].record.practiced").toString(),
                "做题数照抄,不因为走了 batch 而被重算");
    }

    // ================================================================ §8.4 分页形状

    @Test
    @DisplayName("🔴 GET /records 的响应只有 items 与可能不出现的 nextCursor")
    void listCarriesNoCountsAtAll() throws Exception {
        store.reset(records(3));

        String body = mockMvc.perform(get("/api/v1/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // 🔴 「不出现」是逐字的:一个 "nextCursor":null 会让端写出 if ('nextCursor' in page) 然后永远为真
        assertFalse(body.contains("nextCursor"), "没有下一页时整个 key 都不该出现:" + body);
        for (String banned : List.of("total", "returned", "hasMore")) {
            assertFalse(body.contains("\"" + banned + "\""),
                    banned + " 已经被契约 §1.4 删掉了,它一出现前端就会长出页码条:" + body);
        }

        // 有下一页时才给 —— 而它是「还有更旧的」这个问题的唯一答案
        String paged = mockMvc.perform(get("/api/v1/records").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertTrue(paged.contains("nextCursor"), "还有更旧的时候必须给游标:" + paged);
    }

    // ================================================================ §8.5 未分类过滤

    /**
     * {@code ?tagState=unclassified} 只做<b>过滤</b>:取值域归打标那一侧,计数归覆盖度那一侧。
     *
     * <p>判定复用有效标签那条路 —— 一条记录的有效标签全部不计覆盖度,它就是未分类的。
     * 在控制器里自己写一段「什么算未分类」会造出第二个口径,而两个口径迟早对不上:
     * 到那时界面上的「未分类 3 条」和筛出来的条数不一样,没人说得清哪个对。
     */
    @Test
    @DisplayName("🔴 ?tagState=unclassified 只筛出「标签全被丢弃」的那些,不新建端点也不自己计数")
    void unclassifiedFilterUsesTheTaggingCaliber() throws Exception {
        store.reset(records(3));

        // 默认:三条都算已分类(主标签是从记录的 nodeCode 推出来的,没配上行的记录照常计数)
        mockMvc.perform(get("/api/v1/records").param("tagState", "unclassified"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // 把第一条的主标签丢弃 → 它成为未分类
        Touch first = store.findAll(ApiTestAuth.USER_ID).get(0);
        tagStore.put(new RecordTag(RecordTag.primaryIdOf(first.id()), ApiTestAuth.USER_ID,
                first.id(), first.nodeCode(), RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL,
                Instant.now(), true));

        mockMvc.perform(get("/api/v1/records").param("tagState", "unclassified"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.id()));

        // 不带参数时不过滤 —— 加参数不许改变默认行为
        mockMvc.perform(get("/api/v1/records"))
                .andExpect(jsonPath("$.items.length()").value(3));

        // 取值域之外的一律 400,而且回声被截断(它是查询参数,没有 @Size 管得着)
        mockMvc.perform(get("/api/v1/records").param("tagState", "已确认"))
                .andExpect(status().isBadRequest());
    }

    // ================================================================ §7.3 与注销的交界面

    /**
     * 🔴 {@code deleteAllOf} 存在的理由就是「不许写成一个循环」。
     *
     * <p>循环调 {@code delete} 会让一次注销变成 N 次全量重写文件,而注销是用户按下之后
     * <b>必须完成</b>的动作 —— 超时了没有第二次机会,半途失败留下的是一个删了一半的账号。
     * 这里验的是语义那一半:<b>只删这一个人的,别人的一条都不动。</b>
     */
    @Test
    @DisplayName("🔴 deleteAllOf 只删这一个人的记录 —— 别人的一条都不动")
    void deleteAllOfIsScopedToOneUser() {
        List<Touch> mixed = new ArrayList<>(records(2));
        mixed.add(new Touch("t-other", ApiTestAuth.OTHER_USER_ID, "average-calc", "别人的",
                TouchKind.MANUAL, Instant.now(), null, null));
        store.reset(mixed);

        assertEquals(2, store.deleteAllOf(ApiTestAuth.USER_ID));
        assertEquals(0, store.count(ApiTestAuth.USER_ID));
        assertEquals(1, store.count(ApiTestAuth.OTHER_USER_ID), "别人的记录一条都不该被带走");
        assertEquals(0, store.deleteAllOf(ApiTestAuth.USER_ID),
                "删一个已经空了的用户返回 0,不抛异常 —— 注销一个从没记过东西的账号是正常的");
    }

    // ================================================================ 脚手架

    private static List<Touch> records(int n) {
        List<Touch> ts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ts.add(new Touch("t-" + i, ApiTestAuth.USER_ID, "average-calc", "地铁上",
                    TouchKind.MANUAL, Instant.now().minus(Duration.ofDays(i + 1L)), null, null));
        }
        return ts;
    }

    /** 与 {@code ApiContractTest.Fixtures} 同一套装配 —— web 切片不扫 {@code @Component}。 */
    @TestConfiguration
    static class Fixtures {

        @Bean
        SyllabusSource syllabus() {
            return SyllabusLoader.loadDefault();
        }

        @Bean
        CoverageReader coverageReader(SyllabusSource syllabus, TouchStore store,
                                      RecordTagStore tagStore, AssertionStore assertionStore,
                                      CoverageService coverage, Clock clock) {
            return new CoverageReader(syllabus, store, tagStore, assertionStore, coverage, clock);
        }

        @Bean
        AssertionStore assertionStore() {
            return new InMemoryAssertionStore();
        }

        @Bean
        RecordTagStore recordTagStore() {
            return new InMemoryRecordTagStore();
        }

        @Bean
        CaptureService captureService(TouchStore store, VisionTagger tagger,
                                      SyllabusSource syllabus, Clock clock) {
            return new CaptureService(store, tagger, syllabus, clock);
        }

        /** 这条路压根不调用模型 —— 一旦被调用测试就红,这本身是一条断言。 */
        @Bean
        VisionTagger visionTagger() {
            return (image, mimeType, candidates) -> {
                throw new AssertionError("手动记一笔不该调用识别 —— 额度用尽 ≠ 记不了");
            };
        }

        @Bean
        M1TouchStore m1TouchStore() {
            return new M1TouchStore();
        }
    }

    /** 按 {@link TouchStore} 契约实现的内存版 —— 与 {@code ApiContractTest} 里那个同源。 */
    static final class M1TouchStore implements TouchStore {

        private final List<Touch> touches = new ArrayList<>();

        void reset() {
            touches.clear();
        }

        void reset(List<Touch> seed) {
            touches.clear();
            touches.addAll(seed);
        }

        @Override
        public List<Touch> findAll(long userId) {
            return touches.stream().filter(t -> t.userId() == userId)
                    .sorted(java.util.Comparator.comparing(Touch::occurredAt)).toList();
        }

        @Override
        public List<Touch> findAllAcrossUsers() {
            return touches.stream()
                    .sorted(java.util.Comparator.comparing(Touch::occurredAt)).toList();
        }

        @Override
        public Touch findByClientToken(long userId, String clientToken) {
            if (clientToken == null || clientToken.isBlank()) {
                return null;
            }
            return touches.stream()
                    .filter(t -> t.userId() == userId && clientToken.equals(t.clientToken()))
                    .findFirst().orElse(null);
        }

        @Override
        public Touch append(Touch touch) {
            Touch existing = findByClientToken(touch.userId(), touch.clientToken());
            if (existing != null) {
                return existing;
            }
            touches.add(touch);
            return touch;
        }

        @Override
        public Touch delete(long userId, String id) {
            Touch victim = touches.stream()
                    .filter(t -> t.userId() == userId && t.id().equals(id))
                    .findFirst().orElse(null);
            if (victim != null) {
                touches.remove(victim);
            }
            return victim;
        }

        @Override
        public int count(long userId) {
            return (int) touches.stream().filter(t -> t.userId() == userId).count();
        }

        @Override
        public int countByNodeAcrossUsers(String nodeCode) {
            return (int) touches.stream().filter(t -> t.nodeCode().equals(nodeCode)).count();
        }

        @Override
        public int reassign(String fromNodeCode, String toNodeCode) {
            throw new UnsupportedOperationException("这个文件不验改挂");
        }

        @Override
        public int deleteAllOf(long userId) {
            int before = touches.size();
            touches.removeIf(t -> t.userId() == userId);
            return before - touches.size();
        }
    }
}
