package com.kaodian.server.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kaodian.server.api.config.CaliberDeviationFilter;
import com.kaodian.server.api.config.ConfigController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 偏离登记的请求头({@code M3-骨架与覆盖度差集} §3.2 / §十四 增量 7)。
 *
 * <h2>🔴 这个文件验的第一件事:畸形的头不许让它搭车的那个请求失败</h2>
 *
 * 判据里那三条畸形输入逐字写在 {@link #MALFORMED} 里,<b>三条都必须 2xx</b>,
 * 而且三条都要留下一行 {@link CaliberDeviationFilter#CODE_MALFORMED}。
 * <p>
 * 反过来的那一版(解析失败就 400)在测试里很好看:参数校验生效了。
 * 但它把「口径拿不到」升级成了「<b>功能用不了</b>」—— 端退让之后发的<b>每一个</b>请求都带着
 * 这个头,于是一个诊断头的语法错误会让整个 App 从下一次请求起全线 400,
 * 而报错指向的是一个与口径毫无关系的接口。
 *
 * <h2>不建端点、不建表,所以「留下痕迹」验的是日志</h2>
 *
 * 这个仓库没有 {@code error_event} 表({@code §3.2}:🔴 不建端点、不建表),
 * 登记落在 SLF4J 上,与 {@code ApiExceptionHandler} 记 {@code code=...} 是同一种形状。
 * 所以这里抓根 logger,按 {@code code=} 这个 token 数行数。
 */
@WebMvcTest(controllers = ConfigController.class)
// web 切片不扫 @Configuration;ApiTestAuth 给每个请求装上真令牌(B0-4 默认拒绝)
@Import(ApiTestAuth.class)
class ConfigFallbackHeaderTest {

    /** 🔴 §3.2 判据里那三条畸形输入,<b>逐字</b>:值不是数字 / 名字不是 ASCII 且不在闭集 / 夹带自由文本。 */
    private static final List<String> MALFORMED = List.of(
            "blindspotOrderBy=abc",
            "科目=1",
            "x=1;DROP TABLE");

    /** 一个搭车用的请求 —— 端把这个头捎在<b>任何</b>成功的 API 请求上,这里挑最便宜的那个。 */
    private static final String CARRIER = "/api/v1/config/effective";

    @Autowired
    private MockMvc mockMvc;

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger rootLogger;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        rootLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        originalLevel = rootLogger.getLevel();
        logs = new ListAppender<>();
        logs.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        rootLogger.detachAppender(logs);
        rootLogger.setLevel(originalLevel);
        logs.stop();
    }

    // ———————————————————— 一、畸形头:2xx + 一行 MALFORMED ————————————————————

    /**
     * 🔴 三条都 2xx,三条都留下一行 {@code CONFIG_FALLBACK_MALFORMED},
     * 而且<b>头的原文一个字都没进日志的任何级别</b>。
     *
     * <p>最后那半条不是附加要求:{@code x=1;DROP TABLE} 说明这个头装得下任意自由文本,
     * 而「不往磁盘上落用户送来的原文」是 {@code ApiExceptionHandler} 开头那条纪律 ——
     * 一次「把收到的头打出来看看」就等于把用户输入落了盘,落在最不容易想到的地方。
     */
    @Test
    @DisplayName("🔴 三条畸形输入都是 2xx,各留下一行 CONFIG_FALLBACK_MALFORMED,原文不进日志")
    void malformedHeadersNeverFailTheRequestTheyRodeIn() throws Exception {
        rootLogger.setLevel(Level.TRACE);          // 「不进日志的任何级别」要能被验一次

        for (String header : MALFORMED) {
            logs.list.clear();

            mockMvc.perform(get(CARRIER).header(CaliberDeviationFilter.HEADER, header))
                    .andExpect(status().is2xxSuccessful())
                    // 搭车的那个请求照常出结果 —— 不是「勉强 200 但内容坏了」
                    .andExpect(jsonPath("$.blindspotTop").value(20));

            assertEquals(1, linesWith(CaliberDeviationFilter.CODE_MALFORMED),
                    "🔴 畸形头 [" + header + "] 没有留下恰好一行 CONFIG_FALLBACK_MALFORMED —— "
                            + "这次退让从此查不到,而「这次退让发生过就要留下痕迹」是它存在的全部理由");
            assertEquals(0, linesWith(CaliberDeviationFilter.CODE),
                    "🔴 畸形头被当成一次合法登记记下了 —— 整条头必须丢弃,不做部分接受");
            assertNoLogContains(header);
        }
    }

    @Test
    @DisplayName("🔴 超过 256 字节的头整条丢弃,照样不是 4xx")
    void anOverlongHeaderIsDiscardedWithoutA4xx() throws Exception {
        // 🔴 语法与名字都合法,只有长度不合 —— 这样红的时候能确定是长度那一道拦的
        String overlong = "blindspotTop=1,".repeat(30) + "blindspotTop=1";
        assertTrue(overlong.length() > 256, "这条用例的前提没了:构造出来的头没有超过 256 字节");

        mockMvc.perform(get(CARRIER).header(CaliberDeviationFilter.HEADER, overlong))
                .andExpect(status().is2xxSuccessful());

        assertEquals(1, linesWith(CaliberDeviationFilter.CODE_MALFORMED));
        assertEquals(0, linesWith(CaliberDeviationFilter.CODE));
    }

    /**
     * 名字取值域是闭集两个,次数是正整数 —— 越界的处置与语法错完全一样:<b>整条丢弃</b>。
     *
     * <p>最后一条是混合的:一个合法对 + 一个不在闭集的名字。
     * 🔴 <b>不许部分接受</b> —— 「认识的留下、不认识的跳过」的那一版会让端悄悄往这个头里
     * 塞第三个名字(科目、来源、任何自由文本),服务端全程 2xx。
     */
    @Test
    @DisplayName("🔴 名字不在闭集两个之内、次数不是正整数 → 同样整条丢弃 + 一行 MALFORMED")
    void unknownNamesAndNonPositiveCountsAreDiscardedToo() throws Exception {
        for (String header : List.of(
                "blindspotTopp=1",              // 差一个字母,不在闭集
                "subject=1",                    // 🔴 科目不许进这个头
                "blindspotTop=0",               // 0 次的偏离不是一次偏离
                "blindspotTop=1,x=2")) {        // 一半合法 —— 整条照样丢

            logs.list.clear();

            mockMvc.perform(get(CARRIER).header(CaliberDeviationFilter.HEADER, header))
                    .andExpect(status().is2xxSuccessful());

            assertEquals(1, linesWith(CaliberDeviationFilter.CODE_MALFORMED),
                    "[" + header + "] 应当整条丢弃并留下一行 MALFORMED");
            assertEquals(0, linesWith(CaliberDeviationFilter.CODE),
                    "🔴 [" + header + "] 被部分接受了");
        }
    }

    // ———————————————————— 二、合法头:一行 CONFIG_FALLBACK ————————————————————

    /**
     * 🔴 登记落在 {@code path=/config/effective} 名下,<b>不是搭车的那个请求的路径</b>。
     *
     * <p>按搭车路径记的那一版,同一件事会散在十几个端点名下,
     * 「这个端点最近拿不到几次」当场问不出来 —— 而那正是这条登记要回答的唯一问题。
     */
    @Test
    @DisplayName("合法头 blindspotOrderBy=3,blindspotTop=1 被接受,记在 path=/config/effective 名下")
    void aWellFormedHeaderIsRecorded() throws Exception {
        mockMvc.perform(get(CARRIER)
                        .header(CaliberDeviationFilter.HEADER, "blindspotOrderBy=3,blindspotTop=1"))
                .andExpect(status().isOk());

        assertEquals(1, linesWith(CaliberDeviationFilter.CODE),
                "合法的登记一次也没落下 —— 端的计数器会在收到 2xx 之后清零,这一次就永远丢了");
        assertEquals(0, linesWith(CaliberDeviationFilter.CODE_MALFORMED));

        String line = onlyLineWith(CaliberDeviationFilter.CODE);
        assertTrue(line.contains("path=" + CaliberDeviationFilter.PATH),
                "登记没记在 /config/effective 名下:" + line);
        assertTrue(line.contains("blindspotOrderBy=3") && line.contains("blindspotTop=1"),
                "口径名与次数没落全 —— 只知道「发生过」而不知道「哪个口径几次」等于没登记:" + line);
    }

    @Test
    @DisplayName("没带这个头的请求什么都不记 —— 它是一次退让的痕迹,不是访问日志")
    void aRequestWithoutTheHeaderRecordsNothing() throws Exception {
        mockMvc.perform(get(CARRIER)).andExpect(status().isOk());

        assertEquals(0, linesWith(CaliberDeviationFilter.CODE));
        assertEquals(0, linesWith(CaliberDeviationFilter.CODE_MALFORMED));
    }

    // ---------------------------------------------------------------- 夹具

    /**
     * 数带这个 {@code code} 的日志行。
     *
     * <p>🔴 尾巴上那个空格不是手抖:{@code CONFIG_FALLBACK} 是
     * {@code CONFIG_FALLBACK_MALFORMED} 的前缀,不带分隔符地 {@code contains}
     * 会让「合法登记 0 行」这条断言<b>永远绿</b>。两种日志形状在 {@code code=} 之后都还有内容,
     * 所以这个空格一定在。
     */
    private long linesWith(String code) {
        return logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("code=" + code + " "))
                .count();
    }

    private String onlyLineWith(String code) {
        return logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("code=" + code + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("一行 code=" + code + " 的日志都没有"));
    }

    /** 断言这一串没出现在<b>任何级别</b>的日志里(含 logger 名、含异常栈)。 */
    private void assertNoLogContains(String needle) {
        for (ILoggingEvent event : List.copyOf(logs.list)) {
            String rendered = event.getFormattedMessage()
                    + " " + event.getLoggerName()
                    + " " + (event.getThrowableProxy() == null
                    ? "" : event.getThrowableProxy().getMessage());

            assertFalse(rendered.contains(needle), () -> """
                    🔴 偏离登记把请求头的原文打进了日志(级别 %s,logger %s)。

                    这个头装得下任意自由文本(判据里那条 x=1;DROP TABLE 就是),
                    而「不往磁盘上落用户送来的原文」是 ApiExceptionHandler 开头那条纪律 ——
                    畸形头只记字节数,内容对定位没有任何帮助:语法是写死的常量,错只可能错在端上。
                    """.formatted(event.getLevel(), event.getLoggerName()));
        }
    }
}
