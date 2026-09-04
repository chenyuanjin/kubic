package com.kaodian.server.api;

import com.kaodian.server.api.insight.CoverageController;
import com.kaodian.server.api.record.RecordController;
import com.kaodian.server.api.syllabus.SyllabusController;
import com.kaodian.server.api.insight.TimelineController;
import com.kaodian.server.config.DomainBeans;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.api.dto.record.BatchCreateRecordsRequest;
import com.kaodian.server.api.dto.record.CreateRecordRequest;
import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.kaodian.server.collect.CaptureService;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.InMemoryRecordTagStore;
import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.TagOrigin;
import com.kaodian.server.collect.AssertionStore;
import com.kaodian.server.collect.InMemoryAssertionStore;
import com.kaodian.server.collect.RecordTagStore;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusLoader;
import com.kaodian.server.syllabus.SyllabusSource;
import com.jayway.jsonpath.JsonPath;
import jakarta.validation.constraints.Size;
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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * {@link TouchStore} 是接口,这里就按接口给一个内存实现。docs/technical/INDEX.md §2.2:
 * <b>包之间只通过接口调用</b>,测试是这句话第一个受益的地方。
 */
@WebMvcTest(controllers = {
        SyllabusController.class,
        CoverageController.class,
        TimelineController.class,
        RecordController.class})
@Import(DomainBeans.class)     // web 切片不扫 @Configuration,领域装配要显式带进来
class ApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryTouchStore store;

    /** 标签层。这个文件不打标,只用它验一件事:删记录时那句<b>级联删标签</b>真的发生了。 */
    @Autowired
    private RecordTagStore tagStore;

    /**
     * 时间线分桶用的时区 —— 与 {@code kaodian.api.timeline.zone} 的默认值同一个。
     *
     * <p>这里<b>写死</b>而不是从配置读:这个常量正是被验的那件事。
     * 从配置读的话,有人把配置改成 UTC 之后,测试会跟着改口径然后照样绿 ——
     * 一条跟着被测对象一起走的断言,和一条被注释掉的断言,外观是一样的。
     */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    void resetToContract() {
        store.reset(contractTouches());
        // 标签层也得跟着清干净:@WebMvcTest 的上下文在一个类里是复用的,
        // 一条留下来的标签会让后面每一个断言覆盖率的用例都多算一格 ——
        // 而它是不是留下来了,取决于方法执行顺序,那是最难查的一种红。
        tagStore.findAll().forEach(t -> tagStore.deleteByRecord(t.recordId()));
    }

    // ---------------------------------------------------------------- 查询

    @Test
    @DisplayName("GET /api/v1/coverage/summary —— total=18 covered=8 percent=44,与设计稿逐字一致")
    void summaryMatchesDesignContract() throws Exception {
        mockMvc.perform(get("/api/v1/coverage/summary"))
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
        mockMvc.perform(get("/api/v1/coverage/summary"))
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
    @DisplayName("GET /api/v1/syllabus/tree —— 整棵树一次返回,顶上的 44% 与概览同源")
    void treeReturnsWholeModuleInOneShot() throws Exception {
        mockMvc.perform(get("/api/v1/syllabus/tree"))
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
    @DisplayName("GET /api/v1/syllabus/nodes/{code} —— 四统计 + 我的触达,🔴 没有讲解字段")
    void nodeDetailHasNoTeachingFields() throws Exception {
        mockMvc.perform(get("/api/v1/syllabus/nodes/growth-amount"))
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
        mockMvc.perform(get("/api/v1/syllabus/nodes/average-calc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EMPTY"))
                .andExpect(jsonPath("$.accuracy").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.latestAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.sources.length()").value(0));
    }

    @Test
    @DisplayName("查一个树里没有的考点 → 404,不做模糊匹配、不返回最接近的")
    void unknownNodeIsNotGuessed() throws Exception {
        mockMvc.perform(get("/api/v1/syllabus/nodes/增长率那个"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/coverage/blindspots —— Top 5 的名次、分数与设计稿一致(北极星落点)")
    void blindSpotsMatchDesignContract() throws Exception {
        mockMvc.perform(get("/api/v1/coverage/blindspots").param("top", "5"))
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
        mockMvc.perform(get("/api/v1/coverage/blindspots").param("top", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/coverage/blindspots").param("top", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/coverage/blindspots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedTop").value(20));
    }

    // ---------------------------------------------------------------- 时间线聚合(§6.4)

    /**
     * 契约 §6.4 那一行是「时间线聚合 · 按天/周聚合触达」。
     *
     * <p>这一条钉的是<b>它不再是平铺的最近 N 条</b> —— 那是 {@code GET /api/v1/records} 的活
     * (§6.2),两个端点做同一件事的时候,没做的那件事没有任何测试会提起。
     */
    @Test
    @DisplayName("GET /api/v1/timeline —— 出的是格子不是条目,默认按天 30 格")
    void timelineAggregatesIntoBucketsInsteadOfListingRecords() throws Exception {
        mockMvc.perform(get("/api/v1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("DAY"))
                .andExpect(jsonPath("$.granularityLabel").value("按天"))
                .andExpect(jsonPath("$.zone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.buckets.length()").value(30))
                // 🔴 一条 items 都没有:这个端点不再是 /api/v1/records 的第二份实现
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.returned").doesNotExist())
                // 8 条记录里,32 天前与 33 天前那两条落在 30 格窗口之外 ——
                // 它们仍然计进 total,只是不属于任何一格
                .andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.counted").value(6))
                // 最后一格是今天(升序:旧 → 新)。growth-rate 那条就记在今天
                .andExpect(jsonPath("$.buckets[29].start").value(LocalDate.now(BEIJING).toString()))
                .andExpect(jsonPath("$.buckets[29].end").value(LocalDate.now(BEIJING).toString()))
                .andExpect(jsonPath("$.buckets[29].touchCount").value(1))
                .andExpect(jsonPath("$.buckets[29].nodeCount").value(1))
                .andExpect(jsonPath("$.buckets[29].sources.length()").value(1))
                .andExpect(jsonPath("$.buckets[29].sources[0].sourceName")
                        .value("粉笔 · 资料分析系统班 L12"))
                .andExpect(jsonPath("$.buckets[29].sources[0].touchCount").value(1))
                // 🔴 6 天前那一格是空的,而它照样在数组里:
                // 只吐有记录的格子,会让「连着几天没记」和「每天都记」在图上长得一模一样
                .andExpect(jsonPath("$.buckets[23].start")
                        .value(LocalDate.now(BEIJING).minusDays(6).toString()))
                .andExpect(jsonPath("$.buckets[23].touchCount").value(0))
                .andExpect(jsonPath("$.buckets[23].nodeCount").value(0))
                .andExpect(jsonPath("$.buckets[23].sources.length()").value(0));
    }

    /**
     * 🔴 {@code R-05} 在聚合视图上的形状 —— <b>只做统计,不做判断</b>。
     *
     * <p>聚合是这条红线最容易失守的地方:相邻两格的数一旦并排摆着,
     * 「这周比上周多了 40%」只差一次减法。<b>字段名就是措辞</b> ——
     * 契约里出现 {@code improved} 的那天,前端不写「进步了」反倒成了额外的克制。
     * <p>
     * 顺带把 {@code R-01} 也钉上:整份响应体里连一个能装下内容的字段都没有。
     */
    @Test
    @DisplayName("🔴 R-05:每一格只有三个数,没有趋势、没有正确率、没有一个字的评价")
    void timelineBucketsCarryStatisticsOnlyNeverAJudgement() throws Exception {
        String body = mockMvc.perform(get("/api/v1/timeline"))
                .andExpect(status().isOk())
                // 判断类字段:一个都不许有
                .andExpect(jsonPath("$.buckets[29].trend").doesNotExist())
                .andExpect(jsonPath("$.buckets[29].changeVsPrevious").doesNotExist())
                .andExpect(jsonPath("$.buckets[29].streak").doesNotExist())
                .andExpect(jsonPath("$.buckets[29].improved").doesNotExist())
                .andExpect(jsonPath("$.buckets[29].best").doesNotExist())
                .andExpect(jsonPath("$.buckets[29].worst").doesNotExist())
                // 「对不对」那一侧:正确率进了桶,「这周退步了」就只差一次减法
                // (docs/technical/INDEX.md §5.2 撤掉 practice_log 时给的就是这条理由)
                .andExpect(jsonPath("$.buckets[29].accuracy").doesNotExist())
                .andExpect(jsonPath("$.buckets[29].practiced").doesNotExist())
                .andExpect(jsonPath("$.buckets[29].correct").doesNotExist())
                // 整份响应上也没有平均数 —— 它看着中立,唯一的用途是拿今天去和它比
                .andExpect(jsonPath("$.averagePerDay").doesNotExist())
                .andExpect(jsonPath("$.mostActiveBucket").doesNotExist())
                // 🔴 R-01:没有任何能装下内容的字段
                .andExpect(jsonPath("$.buckets[29].content").doesNotExist())
                .andExpect(jsonPath("$.buckets[29].transcript").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        for (String judgement : List.of("进步", "退步", "薄弱", "坚持", "打卡", "掌握", "建议")) {
            assertFalse(body.contains(judgement),
                    "聚合视图里出现了「" + judgement + "」—— 产品只报「有没有、几次、多久前」(R-05)");
        }
    }

    /**
     * 🔴 时区那条决定的落点。
     *
     * <p>用户说的「今天」是北京时间的今天。按 UTC 切,<b>北京时间 0 点到 8 点记的那几笔
     * 会被算进「昨天」</b> —— 用户刚记完就翻开时间线,看到的是「昨天 1 条、今天 0 条」。
     * 这与短信频控里「日按哪个时区算」是同一条决定
     * ({@code kaodian.auth.sms.zone}:「用户说的『明天』是北京时间的明天」)。
     */
    @Test
    @DisplayName("🔴 按北京时间切「一天」:凌晨 0:30 记的那笔算今天,不算昨天")
    void dayBucketsFollowBeijingMidnightNotUtc() throws Exception {
        LocalDate today = LocalDate.now(BEIJING);
        ZonedDateTime justAfterMidnight = today.atStartOfDay(BEIJING).plusMinutes(30);

        // 这一句是上面那个断言的前提:这个时刻在 UTC 眼里【确实是昨天】。
        // 前提一旦不成立(比如有人把 BEIJING 改成 UTC),下面那条断言就会安安静静地假过。
        assertNotEquals(today, LocalDate.ofInstant(justAfterMidnight.toInstant(), ZoneOffset.UTC),
                "北京时间 0:30 在 UTC 下必须落在前一天,否则这个测试什么都没验");

        store.reset(List.of(at("t-midnight", "growth-rate", "地铁上", justAfterMidnight)));

        mockMvc.perform(get("/api/v1/timeline").param("buckets", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(2))
                .andExpect(jsonPath("$.buckets[1].start").value(today.toString()))
                .andExpect(jsonPath("$.buckets[1].touchCount").value(1))
                .andExpect(jsonPath("$.buckets[0].start").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.buckets[0].touchCount").value(0))
                .andExpect(jsonPath("$.counted").value(1));
    }

    /**
     * 🔴 周从周一起算,而且是<b>日历周</b>不是「最近 7 天」。
     *
     * <p>滑动窗口每天都在挪,于是同一段历史今天看和明天看是两组不同的数 ——
     * 而「几次」必须是一个问两遍答案一样的数(决策记录 §2.2)。
     */
    @Test
    @DisplayName("granularity=week —— 周一起算,周日那条落在上一格")
    void weekBucketsAreCalendarWeeksStartingMonday() throws Exception {
        LocalDate thisMonday = LocalDate.now(BEIJING).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        store.reset(List.of(
                at("t-mon", "growth-rate", "粉笔 · 资料分析系统班 L12",
                        thisMonday.atStartOfDay(BEIJING).plusHours(9)),
                at("t-sun", "average-calc", "中公 · 资料分析专项",
                        thisMonday.minusDays(1).atStartOfDay(BEIJING).plusHours(23))));

        mockMvc.perform(get("/api/v1/timeline").param("granularity", "week").param("buckets", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("WEEK"))
                .andExpect(jsonPath("$.granularityLabel").value("按周"))
                .andExpect(jsonPath("$.buckets.length()").value(4))
                .andExpect(jsonPath("$.from").value(thisMonday.minusWeeks(3).toString()))
                // ⚠ to 可能是未来的日期:本周还没过完。截到今天会让最后一格比别的格子窄,
                // 而一根宽度不等的柱子在图上直接就是误读
                .andExpect(jsonPath("$.to").value(thisMonday.plusDays(6).toString()))
                .andExpect(jsonPath("$.buckets[3].start").value(thisMonday.toString()))
                .andExpect(jsonPath("$.buckets[3].end").value(thisMonday.plusDays(6).toString()))
                .andExpect(jsonPath("$.buckets[3].touchCount").value(1))
                // 上周日 23:00 那条 —— 只差一小时,但它是上一格
                .andExpect(jsonPath("$.buckets[2].start").value(thisMonday.minusWeeks(1).toString()))
                .andExpect(jsonPath("$.buckets[2].touchCount").value(1))
                .andExpect(jsonPath("$.counted").value(2));
    }

    @Test
    @DisplayName("一格里数的是「几条」和「几个不同考点」,两个数不是一回事")
    void bucketCountsDistinctNodesSeparatelyFromRecords() throws Exception {
        ZonedDateTime todayNoon = LocalDate.now(BEIJING).atStartOfDay(BEIJING).plusHours(12);
        store.reset(List.of(
                at("t-a", "growth-rate", "粉笔 · 资料分析系统班 L12", todayNoon),
                at("t-b", "growth-rate", "粉笔 · 资料分析系统班 L12", todayNoon.plusMinutes(10)),
                at("t-c", "average-calc", "粉笔 · 资料分析系统班 L12", todayNoon.plusMinutes(20))));

        mockMvc.perform(get("/api/v1/timeline").param("buckets", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets[0].touchCount").value(3))
                .andExpect(jsonPath("$.buckets[0].nodeCount").value(2));
    }

    /**
     * 来源分布的顺序必须是<b>确定的</b>。
     *
     * <p>它是从一个 {@code HashMap} 折出来的,而 {@code HashMap} 的迭代顺序不是承诺 ——
     * 不显式排序的话,同一份数据两次请求可以给出两种顺序,前端的图会自己抖起来。
     * 并列也必须打破:只按条数排,两个 1 条的来源仍然是随机序。
     */
    @Test
    @DisplayName("来源分布按条数倒序,并列按名字 —— 同一份数据两次请求同序")
    void sourceBreakdownIsOrderedByCountThenName() throws Exception {
        ZonedDateTime todayNoon = LocalDate.now(BEIJING).atStartOfDay(BEIJING).plusHours(12);
        List<Touch> seed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            seed.add(at("t-fb-" + i, "growth-rate", "粉笔 · 资料分析系统班 L12", todayNoon.plusMinutes(i)));
        }
        seed.add(at("t-zg", "average-calc", "中公 · 资料分析专项", todayNoon.plusHours(1)));
        seed.add(at("t-bz", "average-calc", "B站 · 资料分析技巧", todayNoon.plusHours(2)));
        store.reset(seed);

        mockMvc.perform(get("/api/v1/timeline").param("buckets", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets[0].sources.length()").value(3))
                .andExpect(jsonPath("$.buckets[0].sources[0].sourceName")
                        .value("粉笔 · 资料分析系统班 L12"))
                .andExpect(jsonPath("$.buckets[0].sources[0].touchCount").value(3))
                // 并列 1 条 —— 按名字排,'B'(0x42)在 '中'(0x4E2D)前面
                .andExpect(jsonPath("$.buckets[0].sources[1].sourceName").value("B站 · 资料分析技巧"))
                .andExpect(jsonPath("$.buckets[0].sources[2].sourceName").value("中公 · 资料分析专项"));
    }

    @Test
    @DisplayName("🔴 不认识的 granularity → 400,而且报错里不回显整段原文")
    void unknownGranularityIsRejectedWithoutEchoingIt() throws Exception {
        mockMvc.perform(get("/api/v1/timeline").param("granularity", "month"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_GRANULARITY"));

        String pastedStem = "2023 年全国粮食总产量为 13908 亿斤,比上年增加 177 亿斤".repeat(40);
        String body = mockMvc.perform(get("/api/v1/timeline").param("granularity", pastedStem))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_GRANULARITY"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.length() < pastedStem.length(),
                "granularity 是查询参数,没有 @Size 管得着它 —— 回声必须自己截断");

        // 大小写不敏感:一次大小写打错变成 400 没有任何好处(与 /api/v1/export 的 format 同一条)
        mockMvc.perform(get("/api/v1/timeline").param("granularity", "Day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("DAY"));
        mockMvc.perform(get("/api/v1/timeline").param("granularity", "WEEK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("WEEK"));
    }

    @Test
    @DisplayName("buckets 越界被拒;默认 30")
    void timelineBucketsIsValidated() throws Exception {
        mockMvc.perform(get("/api/v1/timeline").param("buckets", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/timeline").param("buckets", "367"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(30));
    }

    // ---------------------------------------------------------------- 采集

    @Test
    @DisplayName("POST /api/v1/records —— 记一笔之后,那个考点的状态立刻跟着变")
    void createRecordMovesTheNode() throws Exception {
        mockMvc.perform(post("/api/v1/records")
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
        mockMvc.perform(get("/api/v1/coverage/summary"))
                .andExpect(jsonPath("$.covered").value(9))
                .andExpect(jsonPath("$.percent").value(50));
    }

    @Test
    @DisplayName("🔴 R-07:POST /api/v1/records 传自由文本标签被拒绝 —— 接口上没有这条通道")
    void freeTextTagsAreRejected() throws Exception {
        for (String field : List.of("tag", "name", "label", "nodeName", "keywords")) {
            mockMvc.perform(post("/api/v1/records")
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
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"MANUAL","sourceName":"粉笔 · 资料分析系统班 L12",
                                 "nodeCode":"增长率相关的那一类"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NODE_NOT_IN_SYLLABUS"));

        mockMvc.perform(get("/api/v1/coverage/summary")).andExpect(jsonPath("$.covered").value(8));
    }

    @Test
    @DisplayName("🔴 内容字段进不来:body 里带 content/transcript/imageUrl 一律 400")
    void contentFieldsCannotBeSmuggledIn() throws Exception {
        for (String field : List.of("content", "text", "question", "transcript", "imageUrl", "note")) {
            mockMvc.perform(post("/api/v1/records")
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
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DRILL","sourceName":"自己刷题","nodeCode":"growth-rate",
                                 "practiced":3,"correct":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 只给一个 —— 不替用户把另一个填成 0
        mockMvc.perform(post("/api/v1/records")
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
        mockMvc.perform(post("/api/v1/records")
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
        mockMvc.perform(post("/api/v1/records")
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

    // ---------------------------------------------------------------- 幂等

    @Test
    @DisplayName("🔴 clientToken 幂等:同一个键提交两次 → 第二次 200 + 原来那条,库里只多一条")
    void sameClientTokenIsStoredOnlyOnce() throws Exception {
        String body = """
                {"kind":"MANUAL","sourceName":"粉笔 · 资料分析系统班 L12",
                 "nodeCode":"average-calc","clientToken":"offline-2026-08-27-001"}
                """;

        String firstId = mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(9, store.count(), "第一次:种子 8 条 + 这条");

        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                // 🔴 200 不是 201:服务端什么都没新建,回 Created 是在说谎
                .andExpect(status().isOk())
                // 返回的必须是【原来那条】—— id 与 occurredAt 都是第一次的
                .andExpect(jsonPath("$.record.id").value(
                        JsonPath.read(firstId, "$.record.id").toString()))
                .andExpect(jsonPath("$.record.occurredAt").value(
                        JsonPath.read(firstId, "$.record.occurredAt").toString()));

        assertEquals(9, store.count(), "第二次一条都不该多 —— 多一条就等于覆盖度的分子被数了两次");
    }

    @Test
    @DisplayName("幂等只对同一个键成立:两个不同的 clientToken 是两条记录")
    void differentClientTokensAreDifferentRecords() throws Exception {
        for (String token : List.of("q-1", "q-2")) {
            mockMvc.perform(post("/api/v1/records")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"kind":"MANUAL","sourceName":"自己刷题","nodeCode":"average-calc",
                                     "clientToken":"%s"}
                                    """.formatted(token)))
                    .andExpect(status().isCreated());
        }
        assertEquals(10, store.count());
    }

    @Test
    @DisplayName("🔴 不带 clientToken 的两次提交是两条记录 —— 空的去重键不是一个能互相匹配的值")
    void recordsWithoutTokenAreNeverDeduplicated() throws Exception {
        String body = """
                {"kind":"MANUAL","sourceName":"自己刷题","nodeCode":"average-calc"}
                """;
        mockMvc.perform(post("/api/v1/records").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/records").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // 判重的失败方向只能是「多一条」:多一条用户看得见、删得掉;
        // 少一条是他记了却没记上,而他不会知道。
        assertEquals(10, store.count(), "两条都要在 —— 它们只是长得一样,不是同一条");
    }

    @Test
    @DisplayName("clientToken 有长度上限 —— 超了 400,挡住把题干塞进去重键这条绕路")
    void clientTokenIsLengthCapped() throws Exception {
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"MANUAL","sourceName":"自己刷题","nodeCode":"growth-rate",
                                 "clientToken":"%s"}
                                """.formatted("题".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("clientToken")));
        assertEquals(8, store.count());
    }

    // ---------------------------------------------------------------- 批量补传

    @Test
    @DisplayName("POST /api/v1/records/batch —— 三条落库,覆盖度跟着动")
    void batchStoresEveryItem() throws Exception {
        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[
                                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"average-calc","clientToken":"o-1"},
                                  {"kind":"DRILL","sourceName":"地铁上","nodeCode":"yoy-mom","practiced":5,"correct":4,"clientToken":"o-2"},
                                  {"kind":"PASTE","sourceName":"地铁上","nodeCode":"multiple-calc","clientToken":"o-3"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(3))
                .andExpect(jsonPath("$.stored").value(3))
                .andExpect(jsonPath("$.duplicated").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.results[0].index").value(0))
                .andExpect(jsonPath("$.results[0].status").value("STORED"))
                .andExpect(jsonPath("$.results[0].clientToken").value("o-1"))
                .andExpect(jsonPath("$.results[1].record.practiced").value(5))
                .andExpect(jsonPath("$.results[2].error").doesNotExist());

        assertEquals(11, store.count());
        mockMvc.perform(get("/api/v1/coverage/summary")).andExpect(jsonPath("$.covered").value(11));
    }

    /**
     * 🔴 这是整个批量端点存在的理由,也是它最容易被写错的一条。
     *
     * <p>「一条不合法 → 整批回滚」是最省事的写法,而在补传这个场景下它意味着:
     * 用户断网记了一天,第二条挂着一个他自己后来删掉的考点,<b>那一天全部白记</b>。
     * 而且客户端拿到 400 之后除了重试没有第二个动作,重试还会再撞同一条 —— 队列永远吐不完。
     */
    @Test
    @DisplayName("🔴 部分成功:一条挂在树外的考点上,其余照落,不整批回滚")
    void batchIsPartiallySuccessful() throws Exception {
        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[
                                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"average-calc","clientToken":"o-1"},
                                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"我自己起的考点","clientToken":"o-2"},
                                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"yoy-mom","clientToken":"o-3"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stored").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.results[0].status").value("STORED"))
                .andExpect(jsonPath("$.results[1].status").value("FAILED"))
                .andExpect(jsonPath("$.results[1].error.code").value("NODE_NOT_IN_SYLLABUS"))
                .andExpect(jsonPath("$.results[1].error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.results[1].record").doesNotExist())
                // 🔴 第 3 条在坏的那条【之后】,它必须照样落地 —— 这一条挂了就说明中途断了
                .andExpect(jsonPath("$.results[2].status").value("STORED"));

        assertEquals(10, store.count(), "坏的那条不落库,好的两条一条都不能少");
    }

    @Test
    @DisplayName("🔴 批量里的重复条目算 DUPLICATE,不算失败 —— 补传敢重发全靠这一条")
    void batchTreatsReplayAsDuplicateNotFailure() throws Exception {
        String batch = """
                {"records":[
                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"average-calc","clientToken":"o-1"},
                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"yoy-mom","clientToken":"o-2"}
                ]}
                """;
        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(jsonPath("$.stored").value(2));

        // 断线重连,整批再发一次 —— 这正是离线队列的常态
        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stored").value(0))
                .andExpect(jsonPath("$.duplicated").value(2))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.results[0].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.results[0].record.id").isNotEmpty());

        assertEquals(10, store.count(), "重发一整批,一条都不该多");
    }

    @Test
    @DisplayName("🔴 补传缺 clientToken → 那一条被拒,其余照落 —— 没有它的补传是注定重复的写入")
    void batchItemWithoutClientTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[
                                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"average-calc"},
                                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"yoy-mom","clientToken":"o-2"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.results[0].status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].error.code").value("MISSING_CLIENT_TOKEN"))
                // clientToken 缺失时结果里它是 null,所以客户端只能靠 index 对回队列里那一条
                .andExpect(jsonPath("$.results[0].clientToken").doesNotExist())
                .andExpect(jsonPath("$.results[0].index").value(0))
                .andExpect(jsonPath("$.results[1].status").value("STORED"));

        assertEquals(9, store.count());
    }

    @Test
    @DisplayName("🔴 单批上限 50:超了整批 400,不截断到 50 条处理")
    void batchOverFiftyIsRejectedWhole() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            items.append(i == 0 ? "" : ",").append("""
                    {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"average-calc","clientToken":"o-%d"}
                    """.formatted(i));
        }

        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[" + items + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 🔴 一条都不许落。截断到 50 条处理是这里最危险的写法:
        // 服务端存 50 条回一个成功,客户端清空整个队列 —— 用户丢了 1 笔,两边都以为一切正常。
        assertEquals(8, store.count());
    }

    @Test
    @DisplayName("空批被拒 —— 空的补传只可能是客户端的 bug")
    void emptyBatchIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("🔴 R-07 在批量端点上同样成立:外层壳和内层条目都不接受未定义字段")
    void batchRejectsUnknownFieldsAtBothLevels() throws Exception {
        // 外层:如果这里宽容,{"records":[...], "tags":[...]} 会被安静忽略,调用方以为标签生效了
        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[{"kind":"MANUAL","sourceName":"地铁上",
                                             "nodeCode":"average-calc","clientToken":"o-1"}],
                                 "tags":["我自己想的考点"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("tags")));

        // 内层:解析层的失败必然是整批的 —— 它不是「有一条数据不干净」,是调用方在试探红线
        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[
                                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"average-calc","clientToken":"o-1"},
                                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"yoy-mom","clientToken":"o-2",
                                   "transcript":"2023 年全国粮食产量为..."}
                                ]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));

        assertEquals(8, store.count(), "解析就没过,一条都不该落");
    }

    @Test
    @DisplayName("批里某一条的字段不合法 → 只拒那一条,而且报错里没有用户送来的值")
    void batchItemValidationFailsAloneAndEchoesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/records/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[
                                  {"kind":"DRILL","sourceName":"%s","nodeCode":"average-calc","clientToken":"o-1"},
                                  {"kind":"MANUAL","sourceName":"地铁上","nodeCode":"yoy-mom","clientToken":"o-2"}
                                ]}
                                """.formatted("题".repeat(200))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.stored").value(1))
                .andExpect(jsonPath("$.results[0].error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.results[0].error.message")
                        .value(Matchers.containsString("sourceName")))
                // 🔴 一批 50 条,原样回声等于把 50 段用户输入一起写进响应体和访问日志
                .andExpect(jsonPath("$.results[0].error.message")
                        .value(Matchers.not(Matchers.containsString("题题题"))));
    }

    // ---------------------------------------------------------------- 删记录

    @Test
    @DisplayName("DELETE /api/v1/records/{id} —— 记录没了,那个考点的状态跟着退回去")
    void deleteRecordMovesTheNodeBack() throws Exception {
        // share-change 只有一条记录(仅接触),删掉它那个考点就该回到空白
        mockMvc.perform(delete("/api/v1/records/{id}", "t-share-change"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t-share-change"))
                .andExpect(jsonPath("$.node.state").value("EMPTY"))
                .andExpect(jsonPath("$.summary.covered").value(7))
                .andExpect(jsonPath("$.summary.percent").value(39));

        assertEquals(7, store.count());
        mockMvc.perform(get("/api/v1/coverage/summary")).andExpect(jsonPath("$.covered").value(7));
    }

    @Test
    @DisplayName("🔴 删记录时级联删它的标签(docs/technical/INDEX.md §6.2)—— 不删会留下一批没人会再过滤的孤儿行")
    void deletingARecordCascadesToItsTags() throws Exception {
        // 那些孤儿行今天进不了覆盖度(CoverageService 会跳过指不到记录的标签),
        // 所以这不是一条会立刻算错数的路 —— 它的危险在于【下一个读它们的人未必也做那层过滤】。
        // 契约把「级联删标签」写成了这个端点的约束,那它就得真的发生,而不是靠下游都记得防一手。
        tagStore.put(new RecordTag("tag-x", "t-share-change", "average-calc",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, Instant.now(), false));
        tagStore.put(new RecordTag("tag-y", "t-growth-rate", "average-calc",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, Instant.now(), false));

        mockMvc.perform(delete("/api/v1/records/{id}", "t-share-change"))
                .andExpect(status().isOk());

        assertEquals(List.of("tag-y"), tagStore.findAll().stream().map(RecordTag::id).toList(),
                "被删记录的标签行还留着 —— 契约里那句「级联删标签」没有对应物");
    }

    @Test
    @DisplayName("🔴 删一条不存在的记录 → 404,而且消息里不回显那个 id(路径变量没有长度上限)")
    void deletingAMissingRecordIs404AndEchoesNothing() throws Exception {
        String pastedStem = "2023 年全国粮食总产量为 13908 亿斤,比上年增加 177 亿斤".repeat(40);

        String body = mockMvc.perform(delete("/api/v1/records/{id}", pastedStem))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("粮食"),
                "那个 id 是客户端自己刚发过来的,回显一个字的信息都不增加,却开了一条往日志里写题干的路");
        assertEquals(8, store.count());
    }

    // ---------------------------------------------------------------- cursor 分页

    @Test
    @DisplayName("GET /api/v1/records —— 倒序、带 total,与 /api/v1/timeline 是两个端点")
    void recordsAreListedNewestFirst() throws Exception {
        mockMvc.perform(get("/api/v1/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.returned").value(8))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.items[0].nodeCode").value("growth-rate"))
                // 🔴 与时间线同一条纪律:这里没有内容字段,一个都没有
                .andExpect(jsonPath("$.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.items[0].transcript").doesNotExist());

        // §6.4 的聚合视图仍然在,而且【出的不是同一种东西】:那边是格子,这边是条目。
        // 只断言它还回 200 是不够的 —— 两个端点又做成同一件事的时候,它照样回 200
        // (见 RecordPageResponse 的 javadoc)
        mockMvc.perform(get("/api/v1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets").isArray())
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    @DisplayName("🔴 cursor 翻页把 8 条不重不漏地翻完")
    void cursorPagingCoversEveryRecordExactlyOnce() throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {                 // 上限只为防死循环
            var request = get("/api/v1/records").param("limit", "3");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            String body = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> ids = JsonPath.read(body, "$.items[*].id");
            seen.addAll(ids);
            if (!(boolean) JsonPath.read(body, "$.hasMore")) {
                assertNull(JsonPath.read(body, "$.nextCursor"), "没有更多时不该再给游标");
                break;
            }
            cursor = JsonPath.read(body, "$.nextCursor");
        }

        assertEquals(8, seen.size(), "翻完之后条数必须正好等于总数 —— 多了是重复,少了是漏条");
        assertEquals(8, Set.copyOf(seen).size(), "同一条不能被吐两次");
    }

    /**
     * 🔴 这一条是游标为什么要带 id 的全部理由。
     *
     * <p>补传一次落 50 条,它们的 {@code occurredAt} 全部来自<b>同一次 {@code clock.instant()}</b>。
     * 游标只锚时间戳的话,这一整批要么一起被跳过、要么一起被重复吐出来 ——
     * 而它们恰恰是用户断网那天记的全部东西。
     */
    @Test
    @DisplayName("🔴 同一毫秒里的多条记录也能被翻完 —— 补传落下的那一批就是这样")
    void cursorSurvivesRecordsSharingATimestamp() throws Exception {
        Instant sameMoment = Instant.now().minus(Duration.ofDays(7));
        List<Touch> burst = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            burst.add(new Touch("t-burst-" + i, "average-calc", "地铁上",
                    TouchKind.MANUAL, sameMoment, null, "o-" + i));
        }
        store.reset(burst);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            var request = get("/api/v1/records").param("limit", "2");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.items[*].id"));
            if (!(boolean) JsonPath.read(body, "$.hasMore")) {
                break;
            }
            cursor = JsonPath.read(body, "$.nextCursor");
        }

        assertEquals(6, seen.size(), "同一毫秒的 6 条一条都不能少");
        assertEquals(6, Set.copyOf(seen).size(), "也一条都不能重复");
    }

    @Test
    @DisplayName("🔴 解不开的游标 → 400,而且报错里不回显整段原文")
    void badCursorIsRejectedWithoutEchoingIt() throws Exception {
        String pastedStem = "2023 年全国粮食总产量为 13908 亿斤,比上年增加 177 亿斤".repeat(40);

        String body = mockMvc.perform(get("/api/v1/records").param("cursor", pastedStem))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.length() < pastedStem.length(),
                "游标是查询参数,没有 @Size 管得着它 —— 回声必须自己截断");

        // 长度够短但根本不是游标的,同样是 INVALID_CURSOR,不是 500
        mockMvc.perform(get("/api/v1/records").param("cursor", "不是游标"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("limit 越界被拒;默认 50")
    void recordsLimitIsValidated() throws Exception {
        mockMvc.perform(get("/api/v1/records").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/records").param("limit", "201"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/records"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 跨域

    @Test
    @DisplayName("CORS:放行 Vite dev server;全局方法白名单里没有 DELETE")
    void corsAllowsViteDevServerOnly() throws Exception {
        mockMvc.perform(options("/api/v1/coverage/summary")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                // 方法白名单里没有 DELETE —— 要开写入/删除必须显式改配置,而「必须显式改」正是要的效果
                .andExpect(header().string("Access-Control-Allow-Methods",
                        Matchers.not(Matchers.containsString("DELETE"))));

        mockMvc.perform(options("/api/v1/coverage/summary")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    /**
     * 🔴 {@code DELETE} 是逐条路径开的,不是往 {@code /api/v1/**} 里加一个方法。
     *
     * <p>加在全局上会<b>连带给 {@code /api/v1/syllabus/**} 开删除口子</b>,
     * 而骨架层的删除守则(有记录就不许删,只能归档)保护的正是行为层的记录 ——
     * 它不能被一行图省事的跨域配置从旁边绕开。这个测试守的就是这条边界。
     */
    @Test
    @DisplayName("🔴 CORS:DELETE 只开给 /api/v1/records/*,骨架层那边照旧不开")
    void corsOpensDeleteOnlyWhereTheContractAsksForIt() throws Exception {
        mockMvc.perform(options("/api/v1/records/t-share-change")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods",
                        Matchers.containsString("DELETE")));

        mockMvc.perform(options("/api/v1/syllabus/nodes/growth-rate")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE"))
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
                        dto.getSimpleName() + " 不允许出现装内容的字段(决策记录 §2.2 不碰内容):" + bad);
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

    /**
     * 🔴 批量端点的<b>外层壳</b>也要有第二道锁,理由与上面那条一模一样。
     *
     * <p>批量是绕过 R-07 最省事的一条路:外层若对未定义字段宽容,
     * {@code {"records":[…], "tags":["我自己想的考点"]}} 会被安静忽略,调用方以为标签生效了。
     * 而走 MockMvc 的那条测试证明不了「有两道锁」—— 真实配置里
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} 一直开着,第一道锁会先响。所以这里同样把它拆掉。
     */
    @Test
    @DisplayName("🔴 批量外层壳的第二道锁:关掉 FAIL_ON_UNKNOWN_PROPERTIES 也照样拒未定义字段")
    void batchShellRejectsUnknownFieldsEvenWithoutTheMapperFlag() {
        JsonMapper lenient = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)   // 第一道锁,故意拆掉
                .build();

        for (String field : List.of("tags", "labels", "notes")) {
            String body = """
                    {"records":[{"kind":"MANUAL","sourceName":"地铁上",
                                 "nodeCode":"growth-rate","clientToken":"o-1"}],
                     "%s":["2023 年全国粮食产量为..."]}
                    """.formatted(field);

            Exception thrown = assertThrows(Exception.class,
                    () -> lenient.readValue(body, BatchCreateRecordsRequest.class),
                    "配置锁拆掉之后 " + field + " 就进来了 —— 内层每一条严、外层松,等于整条线松");

            UnknownFieldException lock = null;
            for (Throwable t = thrown; t != null && t != t.getCause(); t = t.getCause()) {
                if (t instanceof UnknownFieldException ufe) {
                    lock = ufe;
                }
            }
            assertNotNull(lock, "拦下它的必须是 DTO 上那道锁,不是别的解析错误:" + thrown);
            assertEquals(field, lock.fieldName());
            assertFalse(lock.getMessage().contains("粮食产量"),
                    "异常消息里只能有字段名,不能有字段的值");
        }
    }

    @Test
    @DisplayName("🔴 报错回声有长度上限 —— 路径变量/查询参数没有 @Size 管着,别成了写日志的通道")
    void rejectionMessagesDoNotEchoUnboundedUserInput() throws Exception {
        String pastedStem = "2023 年全国粮食总产量为 13908 亿斤,比上年增加 177 亿斤".repeat(40);

        String nodeMessage = mockMvc.perform(get("/api/v1/syllabus/nodes/{code}", pastedStem))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(nodeMessage.length() < pastedStem.length(),
                "整段原文被原样回声了 —— 它同时会进服务端日志");

        String subjectMessage = mockMvc.perform(get("/api/v1/syllabus/tree").param("subject", pastedStem))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_LOADED"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(subjectMessage.length() < pastedStem.length(),
                "subject 是查询参数,没有 @Size 管得着它 —— 回声必须自己截断");
    }

    /**
     * 🔴 字段表从五个变成六个,是 {@code clientToken} 加进来的那一次(docs/technical/INDEX.md §6.2「client_token 幂等」)。
     *
     * <p>这个断言的作用不是「数字必须是 5」,是<b>加字段这件事必须先在这里被挡一下</b>:
     * 改这一行的人得先回答「这个字段会不会变成放内容的地方」。
     * {@code clientToken} 的答案是它有上限({@link Touch#MAX_CLIENT_TOKEN_LENGTH} = 64),
     * 而 64 装不下任何一道题的题干 —— 下一个字段也得拿出同样量级的答案。
     */
    @Test
    @DisplayName("🔴 写入请求体的字段表被钉死:六个,不多不少")
    void createRequestShapeIsPinned() {
        List<String> fields = Arrays.stream(CreateRecordRequest.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertEquals(List.of("kind", "sourceName", "nodeCode", "practiced", "correct", "clientToken"), fields,
                "「只接受 nodeCode,不接受 name」是 R-07 在接口层的实现 —— 加字段前先回去看 docs/technical/INDEX.md §6.3");
    }

    @Test
    @DisplayName("🔴 clientToken 有长度上限,而且上限只有一个数 —— 它是 id,不是放内容的地方")
    void clientTokenHasASingleCeiling() {
        RecordComponent rc = Arrays.stream(CreateRecordRequest.class.getRecordComponents())
                .filter(c -> c.getName().equals("clientToken"))
                .findFirst().orElseThrow();
        // @Size 会按自身的 @Target 落到分量 / 访问器 / 后备字段里的某几处 —— 三处都看一遍,
        // 别因为落点不同就当成「没写」(与 NoStemFieldTest#sizeOf 同一条)
        Size size = rc.getAnnotation(Size.class);
        if (size == null) {
            size = rc.getAccessor().getAnnotation(Size.class);
        }

        assertNotNull(size, "clientToken 没有 @Size —— 那它就能装下一整道题(R-01)");
        assertEquals(Touch.MAX_CLIENT_TOKEN_LENGTH, size.max(),
                "上限必须引用 Touch.MAX_CLIENT_TOKEN_LENGTH,不能自己写一个数:"
                        + "请求体、领域记录、落盘 JSON 三处都要说得出上限,三个数迟早对不上");
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

    /** 聚合测试用的一条记录 —— 时刻按北京时间给,因为要验的就是「这一笔算哪一天」。 */
    private static Touch at(String id, String node, String source, ZonedDateTime when) {
        return new Touch(id, node, source, TouchKind.MANUAL, when.toInstant(), null);
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

        /** 契约见 {@link TouchStore#findByClientToken} —— 没有去重键不是一个能互相匹配的值。 */
        @Override
        public Touch findByClientToken(String clientToken) {
            if (clientToken == null || clientToken.isBlank()) {
                return null;
            }
            return touches.stream()
                    .filter(t -> clientToken.equals(t.clientToken()))
                    .findFirst()
                    .orElse(null);
        }

        /**
         * 契约见 {@link TouchStore#append} —— <b>幂等在这一层</b>。
         *
         * <p>这个内存版必须跟着实现这一条,不能只让 {@code FileTouchStore} 实现:
         * 否则接口契约测试跑的是一个「没有幂等」的 store,
         * 而幂等恰恰是这一批端点要验的东西 —— 那样验的就是假的。
         */
        @Override
        public Touch append(Touch touch) {
            Touch existing = findByClientToken(touch.clientToken());
            if (existing != null) {
                return existing;
            }
            touches.add(touch);
            return touch;
        }

        /** 契约见 {@link TouchStore#delete} —— 删一条不存在的返回 null,不抛异常。 */
        @Override
        public Touch delete(String id) {
            for (int i = 0; i < touches.size(); i++) {
                if (touches.get(i).id().equals(id)) {
                    return touches.remove(i);
                }
            }
            return null;
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
                            t.occurredAt(), t.drill(), t.clientToken()));
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
                                      RecordTagStore tagStore, AssertionStore assertionStore,
                                      CoverageService coverage, Clock clock) {
            return new CoverageReader(syllabus, store, tagStore, assertionStore, coverage, clock);
        }

        /** 「我已掌握」。它不进覆盖度的分子(决策记录 §5.2:补丁不是解法),但 CoverageReader 要读它。 */
        @Bean
        AssertionStore assertionStore() {
            return new InMemoryAssertionStore();
        }

        /**
         * 标签层。<b>覆盖度的分子从这里出来</b>,所以它必须真的在场 ——
         * 给个空实现会让这个测试跑在一个「标签永远为空」的世界里,而那正是覆盖度口径的一半。
         *
         * <p>这个测试不打标,所以它从头到尾是空的;空<b>不等于没有</b>:
         * 采集时挂上的那条主标签是由记录推出来的(见 {@code RecordTag#effectiveTagsOf}),
         * 一行都不必存,44% 照样算得出来。这条正是那个设计要保住的东西。
         */
        @Bean
        RecordTagStore recordTagStore() {
            return new InMemoryRecordTagStore();
        }

        /** {@link CaptureService} 同理。写入端点委托给它,不自己 new Touch。 */
        @Bean
        CaptureService captureService(TouchStore store, VisionTagger tagger,
                                      SyllabusSource syllabus, Clock clock) {
            return new CaptureService(store, tagger, syllabus, clock);
        }

        /**
         * POST /api/v1/records 这条路压根不调用模型(用户已经从树里挑好了考点),
         * 所以这里给一个「一调用就炸」的实现:<b>它一旦被调用,测试就会红</b> ——
         * 这本身就是一条断言,钉住 docs/product/商业化与额度设计.md §二「手动记录永不消耗 AI 额度」。
         */
        @Bean
        VisionTagger visionTagger() {
            return (image, mimeType, candidates) -> {
                throw new AssertionError("手动记一笔不该调用识别 —— 额度用尽 ≠ 记不了(docs/product/商业化与额度设计.md §二)");
            };
        }

        @Bean
        InMemoryTouchStore touchStore() {
            return new InMemoryTouchStore();
        }
    }
}
