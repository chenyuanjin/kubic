package com.kaodian.server.api;

import com.kaodian.server.api.events.BlindspotEventController;
import com.kaodian.server.api.events.BlindspotEventStore;
import com.kaodian.server.auth.AccountStore;
import com.kaodian.server.auth.AppUser;
import com.kaodian.server.auth.FileAccountStore;
import com.kaodian.server.auth.IdentityType;
import com.kaodian.server.auth.UserIdentity;
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

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code M3-骨架与覆盖度差集} §6.3 那张「<b>重复打开算几次</b>」表,一行一个用例;
 * 外加 §6.3 末的 {@code localDate} 合法窗口与 §6.1 的三个闭集。
 *
 * <h2>🌟 这个类算的是产品唯一的那个数</h2>
 *
 * 北极星是<b>「主动查看盲区的人数」</b>。所以每一行都断言<b>两件事</b>:
 * 落了几行事件,以及那一天的 {@code COUNT(DISTINCT user_id)} 是几。
 * <p>
 * 只断言行数是不够的 —— 「同日 {@code S-BLIND} 与 {@code S-ASK} 各一次」是
 * <b>2 行但 1 人</b>,一个把北极星实现成「数行数」的版本在行数上完全正确。
 * 反过来只断言人数也不够:那样「按 {@code (userId, localDate)} 去重、丢掉 surface」的版本
 * 会全绿,而它把两种读法的数据合并了,§6.4 那个待裁定的冲突就<b>再也没法裁</b>——
 * 库里已经没有分得开的数据了。
 *
 * <h2>🔴 去重在服务端,不在客户端</h2>
 *
 * 客户端去重挡不住重装与多端,而这个数<b>不能被客户端状态左右</b>(§6.3 / 契约 §5.7)。
 * 所以下面每一条都是<b>真的发 HTTP</b>,而不是直接调 store —— 客户端能做的只有
 * 「同一条请求再发一次」,而这正是要被挡住的那件事。
 *
 * <h2>每个用例用自己的 {@code localDate}</h2>
 *
 * 一个 {@code @WebMvcTest} 类共用一个上下文,而这个 store 没有 {@code reset()} ——
 * <b>给北极星的唯一数据源开一个「清空」方法,是一件迟早会在生产里被谁调到的事</b>。
 * 用例之间靠日期隔离,而这本来就是这张表的天然分区键。
 */
@WebMvcTest(controllers = BlindspotEventController.class)
@Import({BlindspotDedupTest.Fixtures.class, ApiTestAuth.class})
class BlindspotDedupTest {

    private static final String PATH = "/api/v1/events/blindspot-opened";

    /** 固定的服务端「今天」(UTC)。上界 = 它 + 1 天 = {@code 2026-09-04}。 */
    private static final LocalDate SERVER_TODAY = LocalDate.parse("2026-09-03");

    /** 建号日。下界 = 它 − 1 天 = {@code 2019-12-31}。 */
    private static final LocalDate ACCOUNT_DAY = LocalDate.parse("2020-01-01");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BlindspotEventStore store;

    // ————————— §6.3「重复打开算几次」六行,逐行 —————————

    /** 第一行:同一天、同一屏、打开 5 次 → <b>1 行 / 1 人</b>(唯一索引)。 */
    @Test
    @DisplayName("🔴 同一天同一屏打开 5 次 → 1 行、1 人(重复上报 200,不计第二次)")
    void openingTheSameSurfaceFiveTimesInADayCountsOnce() throws Exception {
        LocalDate day = LocalDate.parse("2026-08-01");
        for (int i = 0; i < 5; i++) {
            open(day, "S-BLIND", "home", "data");
        }

        assertEquals(1, store.countOn(day), "🔴 落了不止一行 —— 去重键 (userId, localDate, surface) 没生效");
        assertEquals(1, store.northStarUserCount(day),
                "🔴 北极星是【人数】不是【打开次数】(§6.3 四条不许的第一条)");
    }

    /** 第二行:同一天、{@code S-BLIND} 与 {@code S-ASK} 各一次 → <b>2 行 / 1 人</b>。 */
    @Test
    @DisplayName("🔴 同一天 S-BLIND 与 S-ASK 各一次 → 2 行,但仍是 1 人(COUNT DISTINCT user_id)")
    void twoSurfacesInADayAreTwoRowsButOnePerson() throws Exception {
        LocalDate day = LocalDate.parse("2026-08-02");
        open(day, "S-BLIND", "home", "data");
        open(day, "S-ASK", "home", "data");

        assertEquals(2, store.countOn(day),
                "🔴 唯一索引没带上 surface —— 两种读法的数据合并了,§6.4 那个冲突就再也没法裁");
        assertEquals(1, store.northStarUserCount(day), "🔴 同一个人被数成了两个人");
    }

    /** 第三行:同一天、手机端 + 网页端各一次 → <b>1 行 / 1 人</b>(同 {@code user_id}、同日、同屏)。 */
    @Test
    @DisplayName("🔴 同一天两台设备各一次 → 1 行、1 人(去重键不含任何设备指纹)")
    void twoDevicesInADayCountOnce() throws Exception {
        LocalDate day = LocalDate.parse("2026-08-03");
        open(day, "S-BLIND", "home", "data");        // 手机端
        open(day, "S-BLIND", "deeplink", "data");    // 网页端,连入口都不一样

        assertEquals(1, store.countOn(day),
                "🔴 落了两行 —— 去重键里混进了设备指纹或 entry(§十四 增量 3:不含任何设备指纹)。"
                        + "entry 不进唯一键:同一天先 home 后 deeplink 是同一次「今天他来看了」");
        assertEquals(1, store.northStarUserCount(day), "🔴 换台设备就多一个人,这个数会被重装刷起来");
    }

    /** 第四行:跨零点前后各一次 → <b>2 行,两天各 1 人</b>({@code local_date} 不同)。 */
    @Test
    @DisplayName("🔴 跨零点 22:00 与次日 00:30 各一次 → 2 行,两天各 1 人")
    void openingAcrossMidnightCountsOnEachDay() throws Exception {
        LocalDate before = LocalDate.parse("2026-08-04");   // 端上本地 22:00
        LocalDate after = LocalDate.parse("2026-08-05");    // 端上本地次日 00:30
        open(before, "S-BLIND", "home", "data");
        open(after, "S-BLIND", "home", "data");

        assertEquals(1, store.countOn(before));
        assertEquals(1, store.countOn(after));
        assertEquals(1, store.northStarUserCount(before),
                "🔴 窗口是一个自然日、按【设备本地时区】切 —— 服务端不该拿自己的时区去归并这两条");
        assertEquals(1, store.northStarUserCount(after));
    }

    /**
     * 第五行:一次 {@code outcome=data}、一次 {@code outcome=empty},同日同屏 →
     * <b>1 行,先到的那条</b>。
     *
     * <p>🔴 这一条是「按键覆盖」与「先到的赢」的分水岭,而<b>行数上两者一模一样</b>。
     * 覆盖的那一版会把那一天改写成「他打开时是空的」,而事实是
     * <b>他第一次看见的是有数据的那一屏</b> —— 空态与有数据必须可区分(§6.1),
     * 区分错了比不区分更糟。
     */
    @Test
    @DisplayName("🔴 同日同屏先 data 后 empty → 1 行,而且活下来的是先到的那条 data")
    void theFirstWriterWinsWhenOutcomeDiffers() throws Exception {
        LocalDate day = LocalDate.parse("2026-08-06");
        open(day, "S-BLIND", "home", "data");
        open(day, "S-BLIND", "home", "empty");

        assertEquals(1, store.countOn(day));
        assertEquals(1, store.northStarUserCount(day));

        BlindspotEventStore.Row row = store.find(ApiTestAuth.USER_ID, day, "S-BLIND");
        assertNotNull(row, "那一行不见了");
        assertEquals("data", row.outcome(),
                "🔴 后到的 empty 覆盖了先到的 data —— 那一天被改写成了「他打开时是空的」");
    }

    /** 第六行:补传三天前的一条,当天也有一条 → <b>2 行,各自那天各 1 人</b>。 */
    @Test
    @DisplayName("🔴 补传三天前 + 当天各一条 → 2 行,各自那天各 1 人(按原始 localDate 去重)")
    void aBackfilledEventCountsOnItsOwnDay() throws Exception {
        LocalDate threeDaysAgo = SERVER_TODAY.minusDays(3);
        open(threeDaysAgo, "S-BLIND", "home", "data");   // 队列补传,localDate 原样带上
        open(SERVER_TODAY, "S-BLIND", "home", "data");

        assertEquals(1, store.countOn(threeDaysAgo),
                "🔴 补传按【原始 localDate】去重,不按补传时刻(§6.3「补传」)");
        assertEquals(1, store.countOn(SERVER_TODAY));
        assertEquals(1, store.northStarUserCount(threeDaysAgo),
                "🔴 补传的那条被算到了今天 —— 三天前那天的人数少一个,今天多一个,两天同时错");
        assertEquals(1, store.northStarUserCount(SERVER_TODAY));
    }

    // ————————— 北极星式子本身 —————————

    /**
     * {@code COUNT(DISTINCT user_id)} 里的 {@code DISTINCT} 只有<b>两个人在场</b>时才有意义。
     *
     * <p>第二个人直接写 store:这个类的令牌只有一条,而这一条断言问的是式子怎么算,
     * 不是请求怎么进来。
     */
    @Test
    @DisplayName("同一天两个人各打开一次 → 2 行、2 人")
    void twoPeopleInADayAreTwoPeople() throws Exception {
        LocalDate day = LocalDate.parse("2026-08-10");
        open(day, "S-BLIND", "home", "data");
        store.record(ApiTestAuth.OTHER_USER_ID, day, "S-BLIND", "home", "empty",
                Instant.parse("2026-08-10T10:00:00Z"));

        assertEquals(2, store.countOn(day));
        assertEquals(2, store.northStarUserCount(day),
                "🔴 两个人被去重成了一个 —— DISTINCT 的键不是 user_id");
    }

    /**
     * 🔴 <b>只用 {@code S-ASK} 的人不进默认口径的北极星</b> —— 这正是 §6.4 登记的那个冲突,
     * 而默认值取的是<b>收窄</b>的那一读({@code 看盲区} §十三:北极星只数总览屏)。
     *
     * <p>那一行仍然<b>落库了</b>:两种读法的数据都在库里,改的只是
     * {@code NORTH_STAR_SURFACES} 一个常量。<b>这条断言不是在说 S-ASK 不重要,
     * 是在说今天的口径是哪一个</b> —— 产品裁定改口径时,红的会是这一条,而不是一堆数字。
     */
    @Test
    @DisplayName("🔴 只开过 S-ASK 的人:事件照样落库,但不进默认口径的北极星(§6.4 收窄默认值)")
    void sAskAloneDoesNotEnterTheDefaultNorthStar() throws Exception {
        LocalDate day = LocalDate.parse("2026-08-11");
        open(day, "S-ASK", "deeplink", "data");

        assertEquals(1, store.countOn(day), "🔴 S-ASK 没落库 —— 那样产品就再也裁不了 §6.4 那个冲突");
        assertEquals(0, store.northStarUserCount(day),
                "🔴 默认口径是 {S-BLIND}。要改成含 S-ASK,改的是那一个常量 + 一条决策记录");
    }

    // ————————— §6.3 末:localDate 合法窗口 —————————

    /**
     * 🔴 上界 = <b>服务端 UTC 今天 + 1 天</b>,超窗 {@code 400 INVALID_ARGUMENT},
     * <b>既不接受也不归一化到服务端当天</b>。
     *
     * <p>一台时钟坏掉的设备会产出 {@code 2035-01-01} 的行,那一行会在未来某一天的人数里
     * 凭空多一个人;而归一化会让<b>今天</b>的人数多一个「从没打开过这一屏的人」。
     * 两种都是编数据 —— <b>丢弃是少一条数据,编是多一条假的</b>。
     */
    @Test
    @DisplayName("🔴 localDate 超过「UTC 今天 + 1 天」→ 400 INVALID_ARGUMENT,不归一化到今天")
    void aLocalDateBeyondTomorrowIsRejectedNotNormalised() throws Exception {
        for (LocalDate tooLate : new LocalDate[]{SERVER_TODAY.plusDays(2), LocalDate.parse("2035-01-01")}) {
            rejected(body(tooLate, "S-BLIND", "home", "data"));
            assertEquals(0, store.countOn(tooLate), "被拒的请求不该留下任何一行");
        }
        assertEquals(0, store.northStarUserCount(SERVER_TODAY.plusDays(2)));
    }

    /** {@code +1} 天是<b>跨时区的合法余量</b>,不是笔误 —— 边界上那一天必须收下。 */
    @Test
    @DisplayName("边界:localDate = UTC 今天 + 1 天 → 200(±1 天是跨时区的合法余量)")
    void tomorrowIsInsideTheWindow() throws Exception {
        LocalDate tomorrow = SERVER_TODAY.plusDays(1);
        open(tomorrow, "S-BLIND", "home", "data");
        assertEquals(1, store.countOn(tomorrow));
    }

    /** 🔴 下界 = <b>账号创建日 − 1 天</b>。早于它的一律 {@code 400 INVALID_ARGUMENT}。 */
    @Test
    @DisplayName("🔴 localDate 早于「建号日 − 1 天」→ 400 INVALID_ARGUMENT")
    void aLocalDateBeforeTheAccountExistedIsRejected() throws Exception {
        LocalDate tooEarly = ACCOUNT_DAY.minusDays(2);
        rejected(body(tooEarly, "S-BLIND", "home", "data"));
        assertEquals(0, store.countOn(tooEarly), "被拒的请求不该留下任何一行");
    }

    @Test
    @DisplayName("边界:localDate = 建号日 − 1 天 → 200")
    void theDayBeforeSignupIsInsideTheWindow() throws Exception {
        LocalDate edge = ACCOUNT_DAY.minusDays(1);
        open(edge, "S-BLIND", "home", "data");
        assertEquals(1, store.countOn(edge));
    }

    /**
     * 日期格式不对也走同一个码。
     *
     * <p>🔴 不是 {@code MALFORMED_BODY}:那会把排查的人指向 JSON 语法,而问题在日期取值
     * ({@code BlindspotOpenedRequest} 类注释)。
     */
    @Test
    @DisplayName("🔴 localDate 格式不对 / 缺失 → 400 VALIDATION_FAILED(「缺必填 / 类型不对」那一档)")
    void aMalformedLocalDateIsValidationFailed() throws Exception {
        // 🔴 与「超窗」分档:契约 §十 里 VALIDATION_FAILED 是「缺必填 / 类型不对」,
        //    INVALID_ARGUMENT 是「参数值不合法」。这两种端上 bug 的排查方向不同 ——
        //    一个查端的 DTO,一个查那台设备的时钟,所以不合成一档。
        malformed(body(LocalDate.parse("2026-07-01"), "S-BLIND", "home", "data")
                .replace("2026-07-01", "2026-9-3"));
        malformed(body(LocalDate.parse("2026-07-01"), "S-BLIND", "home", "data")
                .replace("2026-07-01", "昨天"));
        malformed("{\"surface\":\"S-BLIND\",\"entry\":\"home\",\"outcome\":\"data\"}");
    }

    // ————————— §6.1 三个闭集 —————————

    /**
     * 🔴 三个闭集,每一个的违例都是 {@code 400 INVALID_ARGUMENT} ——
     * <b>不新起码</b>(§6.3 末行)。界面上这三个位置用户都<b>选不出</b>非法值,
     * 走到这里就是端上的 bug,而「bug」不是一档界面状态。
     *
     * <p>🔴 {@code entry=restore} 单独点名:冷启动恢复到这一屏<b>恒不上报</b>(§6.2),
     * 所以它<b>不在取值域里</b>。取值域里留着它,端迟早会「顺手也报一个」,
     * 而那些行会把北极星撑成一个「谁昨天开着这一屏」的数 —— 那不是主动查看。
     */
    @Test
    @DisplayName("🔴 闭集越界 → INVALID_ARGUMENT;字段没传 → VALIDATION_FAILED(两个码)")
    void everyClosedSetViolationIsInvalidArgument() throws Exception {
        LocalDate day = LocalDate.parse("2026-07-01");

        rejected(body(day, "S-NODE", "home", "data"));          // surface 不在闭集
        rejected(body(day, "s-blind", "home", "data"));         // 大小写也不放过
        rejected(body(day, "S-BLIND", "restore", "data"));      // 🔴 restore 恒不上报
        rejected(body(day, "S-BLIND", "notification", "data")); // 产品没有通知机制
        rejected(body(day, "S-BLIND", "home", "cached"));       // outcome 不在闭集
        malformed("{\"localDate\":\"2026-07-01\",\"entry\":\"home\",\"outcome\":\"data\"}"); // 缺 surface —— 缺必填那一档
        malformed("{\"localDate\":\"2026-07-01\",\"surface\":\"S-BLIND\",\"outcome\":\"data\"}"); // 缺 entry
        malformed("{\"localDate\":\"2026-07-01\",\"surface\":\"S-BLIND\",\"entry\":\"home\"}");   // 缺 outcome

        assertEquals(0, store.countOn(day), "被拒的请求不该留下任何一行");
    }

    /**
     * 🔴 多一个键就是 400 —— <b>不是被静默忽略</b>。
     *
     * <p>静默忽略比报错危险:端以为自己在采集设备指纹,服务端以为自己没在收,
     * <b>两边都不会发现</b>(§6.1「不带的属性,一个都不加」)。
     */
    @Test
    @DisplayName("🔴 请求体多一个键(deviceId / dwellMs / subject)→ 400 UNKNOWN_FIELD")
    void unknownFieldsAreRejected() throws Exception {
        LocalDate day = LocalDate.parse("2026-07-02");
        for (String extra : new String[]{"deviceId", "dwellMs", "scrollDepth", "subject", "identity_kind"}) {
            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content(body(day, "S-BLIND", "home", "data")
                                    .replace("}", ",\"" + extra + "\":\"x\"}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        }
        assertEquals(0, store.countOn(day), "被拒的请求不该留下任何一行");
    }

    // ————————— 鉴权 —————————

    /**
     * 🔴 鉴权是 {@code full}(§6.5)。只读令牌 {@code POST} → {@code 403 READONLY_TOKEN}。
     *
     * <p>三道锁都在:{@code TokenScope} 换不出写能力、{@code ApiAuthFilter} 挡非 GET、
     * {@code CurrentSession#requireWrite()} 再判一次。<b>冗余是有意的。</b>
     */
    @Test
    @DisplayName("🔴 只读令牌 → 403 READONLY_TOKEN,而且一行都不落")
    void aReadonlyTokenCannotWriteEvents() throws Exception {
        LocalDate day = LocalDate.parse("2026-07-03");
        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, ApiTestAuth.readonlyBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(day, "S-BLIND", "home", "data")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("READONLY_TOKEN"));

        assertEquals(0, store.countOn(day), "被拒的请求不该留下任何一行");
    }

    /**
     * 🔴 认不出的令牌 → {@code 401 UNAUTHORIZED}。
     *
     * <p>「完全不带头」那一条在 {@code BlindspotEventShapeTest} 与
     * {@code ApiAuthDefaultDenyTest} 里 —— 这个类装了 {@link ApiTestAuth},
     * 每个请求都自带一条真令牌,<b>在这里写不出「不带令牌」那一条</b>。
     */
    @Test
    @DisplayName("🔴 令牌认不出 → 401 UNAUTHORIZED")
    void anUnknownTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer at_not_a_real_token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SERVER_TODAY, "S-BLIND", "home", "data")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ---------------------------------------------------------------- 夹具

    /** 发一次真的上报,并顺手钉住「响应体是空对象」。 */
    private void open(LocalDate localDate, String surface, String entry, String outcome) throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(localDate, surface, entry, outcome)))
                .andExpect(status().isOk())
                .andExpect(content().string("{}"));
    }

    /** 参数【值】不合法 —— 闭集越界、localDate 超窗。 */
    private void rejected(String json) throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 缺必填 / 类型不对 —— 与 {@link #rejected} <b>是两个码</b>(契约 §十)。
     *
     * <p>两者在界面上都是「端上 bug」那一档,所以很容易被合成一个。不合的理由是
     * <b>排查方向不同</b>:这一档说「端根本没发这个字段,或者发了个不是日期的东西」,
     * 那一档说「端发了,但发了个 2035 年」—— 前者查端的 DTO,后者查那台设备的时钟。
     */
    private void malformed(String json) throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private static String body(LocalDate localDate, String surface, String entry, String outcome) {
        return "{\"localDate\":\"%s\",\"surface\":\"%s\",\"entry\":\"%s\",\"outcome\":\"%s\"}"
                .formatted(localDate, surface, entry, outcome);
    }

    @TestConfiguration
    static class Fixtures {

        /** 固定「今天」,否则窗口的两条边每天都在动,而边界用例正好压在边上。 */
        @Bean
        Clock clock() {
            return Clock.fixed(SERVER_TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }

        /**
         * 🔴 临时目录,不碰真实的 {@code ~/.kaodian} —— 跑一次测试就往用户的北极星数据里
         * 播几行假事件,是这一类测试最容易留下的副作用。
         */
        @Bean
        BlindspotEventStore blindspotEventStore() throws IOException {
            return new BlindspotEventStore(Files.createTempDirectory("kaodian-blindspot-dedup")
                    .resolve("blindspot-opened-events.json"));
        }

        /**
         * 真的 {@link AccountStore},不是桩 —— {@code localDate} 的下界读的是
         * {@code AppUser.createdAt},而桩最容易把「读错了字段」这件事一起模拟掉。
         */
        @Bean
        AccountStore accountStore() throws IOException {
            Instant createdAt = ACCOUNT_DAY.atStartOfDay().toInstant(ZoneOffset.UTC);
            FileAccountStore accounts = new FileAccountStore(
                    Files.createTempDirectory("kaodian-blindspot-accounts").resolve("accounts.json"));
            accounts.create(AppUser.fresh(ApiTestAuth.USER_ID, createdAt),
                    new UserIdentity(ApiTestAuth.USER_ID, IdentityType.WX_UNION,
                            "test-union-" + ApiTestAuth.USER_ID, createdAt),
                    null);
            return accounts;
        }
    }
}
