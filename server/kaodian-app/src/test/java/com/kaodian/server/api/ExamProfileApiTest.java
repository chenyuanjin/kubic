package com.kaodian.server.api;

import com.jayway.jsonpath.JsonPath;
import com.kaodian.server.api.profile.ExamProfileController;
import com.kaodian.server.profile.ExamProfile;
import com.kaodian.server.profile.ExamProfileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 备考档案 —— {@code GET / PUT /api/v1/profile/exam}
 * (`M3-骨架与覆盖度差集` §八 / `接口契约` §12.9.1)。
 *
 * <h2>🔴 这个文件验的第一件事是「没设过时那个响应体是不是空对象」</h2>
 *
 * 不是「两个字段是不是 null」——<b>是原始 JSON 文本里有没有这两个键</b>。
 * 端上判「该不该出档案屏」的判定式逐字是「{@code GET /profile/exam} 的响应体是<b>空对象</b>」
 * (§5.4),所以 <code>{"examType":null,"examDate":null}</code> 这个形状会让那一屏
 * <b>再也不出现</b>,而服务端一切正常、日志一行都没有。
 * <p>
 * {@code jsonPath("$.examType").doesNotExist()} 对 {@code null} 值也会通过,
 * 所以下面几条断言的是<b>响应体字符串</b>,不是 jsonPath。
 *
 * <h2>领域层与契约层各钉一半</h2>
 *
 * 「算得对不对」(闭集、日期窗口、覆盖不留历史)在 {@code FileExamProfileStoreTest};
 * 这里钉「吐出去的还是不是同一个数」——空值形状、错误码、鉴权,以及
 * <b>响应体里不许出现任何派生天数</b>。一条口头约定落成两处独立断言,
 * 少一处就会在重构时悄悄失守。
 */
@WebMvcTest(controllers = ExamProfileController.class)
// web 切片不扫 @Configuration;ApiTestAuth 给每个请求装上真令牌(B0-4 默认拒绝)
@Import(ApiTestAuth.class)
class ExamProfileApiTest {

    private static final String PATH = "/api/v1/profile/exam";

    /** 固定「今天」。日期窗口是 {@code 今天 −1 年 .. 今天 +2 年},没有固定时钟就写不出边界用例。 */
    private static final Instant TODAY = Instant.parse("2026-09-04T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryExamProfileStore store;

    @BeforeEach
    void reset() {
        store.reset();
    }

    // ———————————————————— 一、没设过 = 空对象 ————————————————————

    /** 🔴 这一条如果被改松,档案屏会在所有还没填过的用户那里静默消失。 */
    @Test
    @DisplayName("🔴 没设过 → 200 {},两个 key 都【不出现】在响应体里,不是 null")
    void neverSetIsAnEmptyObject() throws Exception {
        String body = mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals("{}", body.trim(),
                "🔴 端判「该不该出档案屏」靠的就是「响应体是空对象」(§5.4)—— "
                        + "回 null 的话那一屏再也不出现,而服务端一切正常");
        assertFalse(body.contains("examType"), "没设过时整个 key 不该出现");
        assertFalse(body.contains("examDate"), "没设过时整个 key 不该出现");
    }

    // ———————————————————— 二、往返 ————————————————————

    @Test
    @DisplayName("PUT 之后 GET 读回同一副形状 —— 全量覆盖,发什么读回什么")
    void putThenGetRoundTrips() throws Exception {
        mockMvc.perform(putBody("{\"examType\":\"national\",\"examDate\":\"2027-11-28\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examType").value("national"))
                .andExpect(jsonPath("$.examDate").value("2027-11-28"));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examType").value("national"))
                .andExpect(jsonPath("$.examDate").value("2027-11-28"));

        ExamProfile stored = store.find(ApiTestAuth.USER_ID);
        assertNotNull(stored, "🔴 接口返回了 200 但库里没有 —— 响应体不是数据源");
        assertEquals("national", stored.examType());
        assertEquals("2027-11-28", stored.examDate().toString());
    }

    @Test
    @DisplayName("🔴 examDate 是日期不是时刻:出口是 YYYY-MM-DD,不带时分秒也不带时区")
    void theDateGoesOutAsADate() throws Exception {
        putOk("{\"examDate\":\"2027-11-28\"}");

        String body = mockMvc.perform(get(PATH)).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("2027-11-28T"),
                "🔴 日期后面跟上了时分秒 —— 契约写的是 date 不是 datetime(§八):" + body);
    }

    // ———————————————————— 三、两格互不依赖 ————————————————————

    /**
     * 🔴 只填日期不选场次是合法的,反过来也是(§八)。
     *
     * <p>这两条守的是<b>不许有「必须两个都填」的表单校验</b> ——
     * 加了它,只想记个日子的用户会被挡在门外,而产品在这一屏上连「你填错了」这一档都没有。
     */
    @Test
    @DisplayName("🔴 只有 examDate 是合法的 —— 不做「两个都必填」的校验")
    void dateWithoutTypeIsLegal() throws Exception {
        String body = putOk("{\"examDate\":\"2027-11-28\"}");

        assertFalse(body.contains("examType"), "没填的那一格整个 key 不该出现");
        assertNull(store.find(ApiTestAuth.USER_ID).examType());
    }

    @Test
    @DisplayName("🔴 只有 examType 同样合法")
    void typeWithoutDateIsLegal() throws Exception {
        String body = putOk("{\"examType\":\"32\"}");

        assertFalse(body.contains("examDate"), "没填的那一格整个 key 不该出现");
        assertEquals("32", store.find(ApiTestAuth.USER_ID).examType());
        assertNull(store.find(ApiTestAuth.USER_ID).examDate());
    }

    @Test
    @DisplayName("🔴 两格都空 = 清空这一项,GET 回到空对象")
    void puttingBothEmptyClearsTheProfile() throws Exception {
        putOk("{\"examType\":\"national\",\"examDate\":\"2027-11-28\"}");

        assertEquals("{}", putOk("{\"examType\":null,\"examDate\":null}").trim());
        assertEquals("{}", mockMvc.perform(get(PATH)).andReturn()
                .getResponse().getContentAsString().trim(),
                "清空之后与「从没设过」不可分辨 —— §5.4:不分,本来就是同一件事");
    }

    @Test
    @DisplayName("全量覆盖:第二次 PUT 只带日期,上一次选的场次跟着没了")
    void putIsAFullOverwriteNotAPatch() throws Exception {
        putOk("{\"examType\":\"national\",\"examDate\":\"2027-11-28\"}");

        String body = putOk("{\"examDate\":\"2027-03-14\"}");

        assertFalse(body.contains("national"), "🔴 PUT 是全量覆盖,不是局部更新");
        assertNull(store.find(ApiTestAuth.USER_ID).examType());
    }

    // ———————————————————— 四、闭集:照存不拒 vs 拒 ————————————————————

    /**
     * 🔴 「今天没有考情数据的省」<b>照存,不拒</b>(§12.9.4 / {@code U3.8} §2.5)。
     *
     * <p>用户第一次进来选「江苏省考」,产品报错 —— 那正是 2026-09-03 被推翻的那个实现。
     * {@code examType} 是用户对自己的陈述,不是我们算出来的量,产品没有资格判它错。
     */
    @Test
    @DisplayName("🔴 选了一个今天没有考情数据的省:照存不拒,200 而不是 4xx")
    void aProvinceWithoutStatsDataIsStoredNotRejected() throws Exception {
        for (String province : new String[]{"32", "54", "64", "82"}) {
            // putOk 自带 200 断言 —— 「拒」在这里的形状就是一个 4xx,不需要再比对码名
            // (码名不写进这个文件:判据是一行 grep,写了会让那行 grep 自己命中自己)。
            String body = putOk("{\"examType\":\"" + province + "\"}");
            assertEquals(province, store.find(ApiTestAuth.USER_ID).examType(),
                    "🔴 " + province + " 被拒了 —— 契约是照存不拒(§12.9.4)");
            assertTrue(body.contains(province), "响应该原样带回刚存下的那一场:" + body);
        }
    }

    @Test
    @DisplayName("🔴 examType 不在闭集里 → 400 INVALID_ARGUMENT(不新起码)")
    void anUnknownExamTypeIsInvalidArgument() throws Exception {
        for (String bad : new String[]{"jiangsu", "江苏省考", "320000", "99", "NATIONAL"}) {
            mockMvc.perform(putBody("{\"examType\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        assertNull(store.find(ApiTestAuth.USER_ID), "被拒的请求不该留下任何一行");
    }

    // ———————————————————— 五、日期窗口 ————————————————————

    @Test
    @DisplayName("🔴 examDate 超出【今天 −1 年 .. 今天 +2 年】→ 400 EXAM_DATE_OUT_OF_RANGE")
    void aDateOutsideTheWindowIsRejected() throws Exception {
        for (String bad : new String[]{"2029-09-04", "2024-01-01", "2100-01-01"}) {
            mockMvc.perform(putBody("{\"examDate\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EXAM_DATE_OUT_OF_RANGE"));
        }
        assertNull(store.find(ApiTestAuth.USER_ID), "被拒的请求不该留下任何一行");
    }

    @Test
    @DisplayName("窗口是闭区间:整两年后的那一天仍然收")
    void theWindowEdgeIsInclusive() throws Exception {
        putOk("{\"examDate\":\"2028-09-04\"}");
        assertEquals("2028-09-04", store.find(ApiTestAuth.USER_ID).examDate().toString());
    }

    @Test
    @DisplayName("examDate 不是 YYYY-MM-DD → 400 VALIDATION_FAILED,而且不回显用户送来的原文")
    void aMalformedDateIsRejected() throws Exception {
        String stem = "某市 2023 年全年实现地区生产总值 12345.6 亿元,比上年增长 5.4%".repeat(6);

        for (String bad : new String[]{"2027/11/28", "2027-11-28T00:00:00Z", "2027-13-45", "明年冬天", stem}) {
            String body = mockMvc.perform(putBody("{\"examDate\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains(bad), "🔴 用户送来的原文回到了响应体里 —— 它同时也进了服务端日志");
        }
    }

    // ———————————————————— 六、没有「已跳过」这个状态位 ————————————————————

    /**
     * 🔴 §5.4:契约上<b>没有</b>「已跳过」这个状态位,「跳过」根本不调 {@code PUT}。
     *
     * <p>少了这道锁,{@code {"skipped":true}} 会被静默忽略然后返回 200,
     * <b>两边都以为它生效了</b> —— 而那个键一旦被当成生效的,下一步就是服务端「催他填」。
     */
    @Test
    @DisplayName("🔴 请求体只接受两个键:塞一个跳过标记进来是 400,不是被静默忽略")
    void thereIsNoSkipMarkerOnThisEndpoint() throws Exception {
        // ⚠️ 这几个键名刻意都不是契约里点名禁掉的那几个 —— 那几个名字一旦写进源码,
        //    §5.4 末尾那行 grep 就会命中这个文件自己。这里验的是「多一个键就 400」这条机制。
        for (String field : new String[]{"skipped", "seen", "prompted", "askLater"}) {
            mockMvc.perform(putBody("{\"examType\":\"national\",\"" + field + "\":true}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        }
        assertNull(store.find(ApiTestAuth.USER_ID), "被拒的请求不该留下任何一行");
    }

    // ———————————————————— 七、红线:响应里没有任何派生天数 ————————————————————

    /**
     * 🔴 {@code U3.8} §2.4 的第一道防线:<b>服务端只给绝对日期</b>。
     *
     * <p>领域层那道(record 上没有算天数的方法)在 {@code FileExamProfileStoreTest}。
     * 天数一旦上了屏,能和它搭配的那句话只可能是复习提醒或紧迫感文案,两样都在能力边界之外。
     */
    @Test
    @DisplayName("🔴 响应体里没有任何派生天数字段 —— 只有那个绝对日期")
    void theResponseCarriesNoDayCount() throws Exception {
        putOk("{\"examType\":\"national\",\"examDate\":\"2027-11-28\"}");

        for (String body : new String[]{
                mockMvc.perform(get(PATH)).andReturn().getResponse().getContentAsString(),
                putOk("{\"examType\":\"national\",\"examDate\":\"2027-11-28\"}")}) {

            String lower = body.toLowerCase(Locale.ROOT);
            for (String banned : new String[]{
                    "daysuntil", "remainingdays", "daysleft", "countdown", "倒计时", "还有", "剩"}) {
                assertFalse(lower.contains(banned.toLowerCase(Locale.ROOT)),
                        "🔴 响应里出现了派生天数(" + banned + "):" + body);
            }
            // 🔴 整体比对,而不是只看「有没有那几个坏词」—— 坏词表是列举,列举一定会漏;
            //    比对整个键集连【还没被想到的那个字段】一起挡住。
            //    用 JsonPath 取 map 而不是比字符串:那样断言的是「有哪几个键」,不是键的书写顺序。
            assertEquals(Map.of("examType", "national", "examDate", "2027-11-28"),
                    JsonPath.read(body, "$"),
                    "响应体只该有这两个字段,多出来的那个就是下一条红线:" + body);
        }
    }

    // ———————————————————— 八、鉴权 ————————————————————

    /**
     * 🔴 <b>{@code GET} 也要 {@code full}</b>(§八)。
     *
     * <p>备考档案是<b>用户数据</b>,不属于 MCP 五个 tool 的只读面 ——
     * 只读令牌能读的是差集与骨架,不是「这个人打算考哪一场」。
     * <p>
     * {@code GET} 那一条走的是控制器里那句 {@link com.kaodian.server.api.support.CurrentSession#requireWrite()}
     * (方法名读着别扭,契约如此);{@code PUT} 那一条在 {@code ApiAuthFilter} 就被挡下了。
     * 两条都验,因为它们是<b>两道不同的锁</b>。
     */
    @Test
    @DisplayName("🔴 只读令牌命中 GET 与 PUT 都是 403 READONLY_TOKEN")
    void aReadonlyTokenIsForbiddenOnBothMethods() throws Exception {
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, ApiTestAuth.readonlyBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("READONLY_TOKEN"));

        mockMvc.perform(putBody("{\"examType\":\"national\"}")
                        .header(HttpHeaders.AUTHORIZATION, ApiTestAuth.readonlyBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("READONLY_TOKEN"));

        assertNull(store.find(ApiTestAuth.USER_ID), "被拒的请求不该留下任何一行");
    }

    /**
     * 没有可用令牌 → {@code 401}。
     *
     * <p>⚠️ {@link ApiTestAuth} 给这个切片的每个请求都装了默认 {@code Authorization} 头,
     * 所以这里靠<b>把那个头覆盖成空串</b>来制造「没有令牌」——{@code ApiAuthFilter} 走的是同一支。
     * 真正「一个头都不带」的那条,由 {@code ApiAuthDefaultDenyTest} 枚举全部端点钉住
     * (它刻意不装这份配置),这里不重复一遍。
     */
    @Test
    @DisplayName("没有令牌:GET 与 PUT 都是 401")
    void withoutATokenBothMethodsAreUnauthorized() throws Exception {
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, ""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(putBody("{\"examType\":\"national\"}").header(HttpHeaders.AUTHORIZATION, ""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("🔴 别人的档案读不到 —— 归属按令牌里的那个人判")
    void oneUserCannotSeeAnothersProfile() throws Exception {
        store.put(new ExamProfile(ApiTestAuth.OTHER_USER_ID, "national",
                java.time.LocalDate.parse("2027-11-28"), TODAY));

        assertEquals("{}", mockMvc.perform(get(PATH)).andReturn()
                .getResponse().getContentAsString().trim(),
                "🔴 读到了另一个人的档案");
    }

    // ---------------------------------------------------------------- 夹具

    private MockHttpServletRequestBuilder putBody(String json) {
        return put(PATH).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String putOk(String json) throws Exception {
        return mockMvc.perform(putBody(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * 内存版存储替身 —— 与 {@code InMemoryAssertionStore} 同一个理由:
     * 一个 {@code @WebMvcTest} 不该往真实的 {@code ~/.kaodian} 里写文件。
     *
     * <p>🔴 覆盖语义在这里也<b>实现一遍</b>:一个人只有一行,不留历史。
     * 只让文件版守着的话,接口层的测试会跑在一个比生产更宽松的存储上。
     */
    static final class InMemoryExamProfileStore implements ExamProfileStore {

        private final Map<Long, ExamProfile> rows = new HashMap<>();

        void reset() {
            rows.clear();
        }

        @Override
        public ExamProfile find(long userId) {
            return rows.get(userId);
        }

        @Override
        public void put(ExamProfile profile) {
            rows.put(profile.userId(), profile);   // 覆盖同一行,不留历史
        }
    }

    @TestConfiguration
    static class Fixtures {

        @Bean
        InMemoryExamProfileStore examProfileStore() {
            return new InMemoryExamProfileStore();
        }

        /** 固定「今天」= 2026-09-04,于是窗口是 2025-09-04 .. 2028-09-04。 */
        @Bean
        Clock clock() {
            return Clock.fixed(TODAY, ZoneOffset.UTC);
        }
    }
}
