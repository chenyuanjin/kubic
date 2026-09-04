package com.kaodian.server.api;

import com.kaodian.server.api.events.BlindspotEventController;
import com.kaodian.server.api.events.BlindspotEventStore;
import com.kaodian.server.api.events.BlindspotOpenedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/events/blindspot-opened} 的<b>形状</b> ——
 * {@code M3-骨架与覆盖度差集} §6.1 / §6.4 / §6.5。
 *
 * <h2>🌟 为什么这个事件的形状值得一个专门的测试类</h2>
 *
 * 它是北极星「主动查看盲区的人数」的<b>唯一数据源</b>(§六 开篇)。
 * 而这一类东西的失败方式全是无声的:
 * <ul>
 *   <li>多一个 {@code dwellMs} 字段 —— 接口全绿,只是这个事件从「一个人来看了」
 *       变成了<b>一份行为画像</b>(§6.1)</li>
 *   <li>响应体多回一个 {@code "first":true} —— 接口全绿,只是端上很快会长出一个
 *       「今天你已经看过了」的界面,而这两屏<b>没有第四种反馈形态</b>(红线七)</li>
 *   <li>{@code NORTH_STAR_SURFACES} 被搬进 {@code application.properties} —— 接口全绿,
 *       只是这个数从此<b>可以在不改一行代码的情况下换定义</b>,历史口径当场断成两段</li>
 * </ul>
 * 三件事都不会让任何别的测试变红,所以它们只能被逐条钉在这里。
 *
 * <h2>🔴 这个类<b>不装</b>默认令牌头</h2>
 *
 * {@link NoDefaultToken} 把 {@link ApiTestAuth} 那个「给每个请求装上 Authorization」的
 * customizer 覆盖成空操作,于是这里看到的是<b>没有令牌的世界</b> ——
 * {@link #withoutATokenItIsUnauthorized} 需要它。要带令牌的用例自己显式加头。
 */
@WebMvcTest(controllers = BlindspotEventController.class)
@Import({BlindspotEventShapeTest.Fixtures.class, BlindspotEventShapeTest.NoDefaultToken.class})
class BlindspotEventShapeTest {

    private static final String PATH = "/api/v1/events/blindspot-opened";

    /** 一条合法请求。四个字段按 §6.1 的原文钉死,不从代码里取常量对照自己。 */
    private static final String VALID =
            "{\"localDate\":\"2026-09-03\",\"surface\":\"S-BLIND\",\"entry\":\"home\",\"outcome\":\"data\"}";

    @Autowired
    private MockMvc mockMvc;

    // ———————————————————— 一、事件的分量恰好四个 ————————————————————

    /**
     * 🔴 <b>多一个字段即红。</b>
     *
     * <p>§6.1 逐条点名了不许出现的那些:科目、排序口径、筛选、停留时长、滚动深度、
     * 点了几个考点、设备指纹、{@code identity_kind}。这里<b>不写那张黑名单</b> ——
     * 黑名单只挡得住想得到的那几个,而下一个被加进来的字段一定是没人想到的那个。
     * 断言写成<b>白名单恰好相等</b>,任何新增分量都会撞上它。
     */
    @Test
    @DisplayName("🔴 事件 DTO 的分量名集合恰好是 {localDate, surface, entry, outcome}")
    void theEventCarriesExactlyFourComponents() {
        assertTrue(BlindspotOpenedRequest.class.isRecord(),
                "事件 DTO 不是 record —— 那样就没有一份可枚举的分量清单,这条断言会变成永远绿");

        assertEquals(Set.of("localDate", "surface", "entry", "outcome"), componentNames(BlindspotOpenedRequest.class),
                "🔴 事件的字段集合变了(§6.1:「不带的属性,一个都不加」)。"
                        + "多一个,这个事件就从「一个人来看了」变成一份行为画像,而产品不做行为分析");
    }

    /**
     * 存储这一侧的同一条 —— <b>请求体上拦掉的属性,不能从库里偷偷长回来</b>。
     *
     * <p>§6.5 那张表把行的形状写死成六个字段;§十四 增量 3 又单独强调去重键
     * 「🔴 <b>不含任何设备指纹</b>」。只钉请求体的话,一个「顺手在落库时记一下 User-Agent」
     * 的实现会全绿 —— 而那正是设备指纹。
     */
    @Test
    @DisplayName("🔴 落库的一行恰好是 {userId, localDate, surface, entry, outcome, createdAt},没有设备指纹")
    void theStoredRowCarriesExactlySixColumns() {
        assertEquals(Set.of("userId", "localDate", "surface", "entry", "outcome", "createdAt"),
                componentNames(BlindspotEventStore.Row.class),
                "🔴 事件行的字段集合变了(§6.5 那张表 / §十四 增量 3:去重键不含任何设备指纹)");
    }

    // ———————————————————— 二、北极星式子在一处常量上 ————————————————————

    /**
     * 🔴 <b>一个 {@code static final},默认恰好 {@code {S-BLIND}}</b>(§6.4「默认值」那一行)。
     *
     * <p>断言分三段,少哪一段都留着一条路:
     * <ol>
     *   <li><b>只有一个同名字段</b> —— 两处各写一份,改一处就会让两个地方的口径分叉</li>
     *   <li><b>是 {@code static final}</b> —— 实例字段或可变字段意味着它能在运行期被改,
     *       而「改它是一次代码改动 + 一条决策记录」这句话就不成立了</li>
     *   <li><b>值恰好 {@code {S-BLIND}}</b> —— 取<b>收窄</b>的那一读。
     *       ⚠️ 含不含 {@code S-ASK} 是 §6.4 登记的冲突,<b>待产品裁定,技术侧不选边</b>;
     *       收窄可以随时放开,<b>放开之后再收窄会让历史数据的口径断成两段</b></li>
     * </ol>
     */
    @Test
    @DisplayName("🔴 NORTH_STAR_SURFACES 是唯一一处 static final,默认恰好 {S-BLIND}")
    void theNorthStarFormulaLivesInOneConstant() throws ReflectiveOperationException {
        List<Field> declared = Arrays.stream(BlindspotEventStore.class.getDeclaredFields())
                .filter(f -> f.getName().equals("NORTH_STAR_SURFACES"))
                .toList();

        assertEquals(1, declared.size(), "🔴 NORTH_STAR_SURFACES 不是恰好一处(§6.4:服务端一处常量)");

        Field field = declared.get(0);
        assertTrue(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
                "🔴 NORTH_STAR_SURFACES 不是 static final —— 能在运行期改的东西不叫「改它是一次代码改动」");

        field.setAccessible(true);
        assertEquals(Set.of("S-BLIND"), field.get(null),
                "🔴 北极星默认口径变了。⚠️ 含不含 S-ASK 是 §6.4 登记的冲突,待产品裁定;"
                        + "改它必须同时留一条决策记录,不是顺手改一个常量");
    }

    /**
     * 🔴 <b>它不在任何一份配置文件里</b>(§6.5 那条 grep 逐字:
     * {@code grep -rniE 'north_?star' server/kaodian-app/src/main/resources/} 期望 0 命中)。
     *
     * <p>搬进 {@code .properties} / {@code .yml} 之后,这个数就<b>可以在不改一行代码、
     * 不留一条记录的情况下换定义</b> —— 而且换完之后没有任何测试会红。
     * 「不是查询参数、不下发、不可配置」三件事里,前两件由「根本没有那个参数」结构性地保证,
     * 只有第三件需要一条断言。
     *
     * <p>路径从 class 的位置反推而不是用 {@code user.dir}:测试跑在哪个工作目录下由 runner 决定,
     * 而一条<b>因为路径不对而扫了 0 个文件</b>的断言会永远绿。
     */
    @Test
    @DisplayName("🔴 北极星口径不在任何 .properties / .yml 里 —— 改它必须是一次代码改动")
    void theNorthStarFormulaIsNotConfigurable() throws IOException, URISyntaxException {
        Path resources = null;
        // 从 class 文件所在处(target/test-classes)一路往上找,而不是数几层 getParent():
        // 数错一层这条断言就会扫 0 个文件,而它仍然是绿的。
        for (Path p = Path.of(BlindspotEventShapeTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()); p != null; p = p.getParent()) {
            Path candidate = p.resolve("src/main/resources");
            if (Files.isDirectory(candidate)) {
                resources = candidate;
                break;
            }
        }

        assertTrue(resources != null && Files.isDirectory(resources),
                "扫不到 src/main/resources —— 这条断言等于没跑,而一条永远绿的断言比没有更糟");

        Pattern northStar = Pattern.compile("north.?star", Pattern.CASE_INSENSITIVE);
        try (Stream<Path> files = Files.walk(resources)) {
            List<String> hits = files.filter(Files::isRegularFile)
                    .filter(p -> northStar.matcher(readLatin1(p)).find())
                    .map(Path::toString)
                    .toList();

            assertEquals(List.of(), hits,
                    "🔴 北极星口径出现在配置文件里(§6.5):它就此可以被不留痕地改掉,"
                            + "而历史数据的口径会断成两段");
        }
    }

    // ———————————————————— 三、响应体是空对象 ————————————————————

    /**
     * 🔴 <b>{@code 200 {}} —— 一个字段都不回,包括「这是不是第一次」。</b>
     *
     * <p>§6.1:端知道了就会有人拿它做「今天你已经看过了」的界面,
     * 而这两屏<b>没有第四种反馈形态</b>(红线七:没有小红点、未读计数、推送)。
     * <p>
     * 两次都断言,是因为「不回显」的失败方式恰恰藏在第二次:
     * 一个返回 {@code {"counted":false}} 的实现在<b>第一次</b>看起来完全正常。
     */
    @Test
    @DisplayName("🔴 响应体是空对象,重复上报也是空对象 —— 端分辨不出这一次算没算")
    void theResponseBodyIsAnEmptyObjectBothTimes() throws Exception {
        assertEquals("{}", postValid(), "🔴 响应体不是空对象(§6.1:不回显是不是第一次)");
        assertEquals("{}", postValid(),
                "🔴 重复上报的响应体不是空对象 —— 端由此能推出「今天已经看过了」,"
                        + "而那正好是红线七禁掉的那种界面");
    }

    // ———————————————————— 四、没有令牌就打不通 ————————————————————

    /**
     * 🔴 事件<b>只在已登录时产生</b>(§6.3「身份」那一行)—— 这不是靠自觉,
     * 是 {@code ApiAuthFilter} 那条「默认拒绝」的结构后果:这个端点
     * <b>不在那张七行匿名白名单里</b>(§6.5「鉴权」)。
     *
     * <p>它要是能匿名打,北极星数的就不再是「几个人」,而是「几个来路不明的请求」。
     */
    @Test
    @DisplayName("🔴 不带令牌 → 401 UNAUTHORIZED(它不在七行匿名白名单里)")
    void withoutATokenItIsUnauthorized() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 夹具

    private String postValid() throws Exception {
        return mockMvc.perform(post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, ApiTestAuth.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static Set<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    /** 按 Latin-1 读:这里只找一个 ASCII 子串,而二进制资源用 UTF-8 读会直接抛解码异常。 */
    private static String readLatin1(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new IllegalStateException("读不了资源文件:" + p, e);
        }
    }

    /**
     * 把 {@link ApiTestAuth} 的默认 {@code Authorization} 头覆盖成空操作。
     *
     * <p>令牌服务与参数解析器照旧从父类来 —— 这里改的<b>只是「每个请求自带一条令牌」</b>,
     * 不是把过滤器关掉。关过滤器的那种写法会让「默认拒绝」在测试里不成立,
     * 而测试正是唯一会发现它不成立的地方({@link ApiTestAuth} 类注释)。
     */
    @TestConfiguration
    static class NoDefaultToken extends ApiTestAuth {

        @Bean
        @Override
        public MockMvcBuilderCustomizer defaultBearerToken() {
            return builder -> {
            };
        }
    }

    @TestConfiguration
    static class Fixtures {

        /** 固定时钟:{@code localDate} 的上界是「服务端 UTC 今天 + 1 天」,它得是个定值。 */
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC);
        }

        /**
         * 🔴 临时目录,不碰真实的 {@code ~/.kaodian}:跑一次测试就往用户的北极星数据里
         * 播几行假事件,是这一类测试最容易留下的副作用。
         */
        @Bean
        BlindspotEventStore blindspotEventStore() throws IOException {
            return new BlindspotEventStore(Files.createTempDirectory("kaodian-blindspot-shape")
                    .resolve("blindspot-opened-events.json"));
        }
    }
}
