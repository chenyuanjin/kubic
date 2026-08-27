package com.kaodian.server.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kaodian.server.api.dto.PhotoRecognitionRequest;
import com.kaodian.server.collect.CandidateRecall;
import com.kaodian.server.collect.InMemoryRecordTagStore;
import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.RecordTagStore;
import com.kaodian.server.collect.TagOrigin;
import com.kaodian.server.collect.TaggingService;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.recognize.AsrClient;
import com.kaodian.server.recognize.RecognitionResult;
import com.kaodian.server.recognize.RecognitionUnavailableException;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.SyllabusLoader;
import com.kaodian.server.syllabus.SyllabusSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/10 §6.2 采集表最后两行的接口契约:{@code POST /records/{id}/image} 与 {@code /audio}。
 *
 * <h2>这个文件验的大半是「送不进去」与「留不下来」,不是「功能可用」</h2>
 *
 * 这两个端点是 R-04 的落点,而 R-04 在 docs/08 §四 标着「<b>第一天不定就改不回来</b>」。
 * 所以下面的用例按三条线组织:
 * <ol>
 *   <li><b>形态</b> —— 图片必须是 JSON + base64,<b>multipart 打到 {@code /image} 上要被拒</b>。
 *       两个端点形态不同是刻意的(docs/10 §6.2 原文逐字不同),而<b>「统一一下」是最自然的重构</b></li>
 *   <li><b>不留存</b> —— 字节不进响应体、不进日志。日志那条是<b>运行时抓的</b>,
 *       不只是源码扫描({@code ImageRetentionTest} 那两条守的是源码形状)</li>
 *   <li><b>不失败</b> —— 识别怎么挂,那条记录都完好、都还能手动挂考点(docs/13 §1.5)</li>
 * </ol>
 *
 * <h2>顺带钉住 {@code origin=auto} 这条路真的通了</h2>
 *
 * {@code RecordTag.primaryOf} 上原先那处 ⚪ 写的是「{@code origin=auto} 今天没有 HTTP 产出路径」。
 * {@link #imageHitLandsAnAutoTag} 就是那条路 —— 而且它验的不是「返回了 SUGGESTED」,
 * 是<b>库里真的多了一行 {@code origin=auto}</b>。
 */
@WebMvcTest(controllers = RecognitionController.class)
@Import(ApiBeans.class)     // web 切片不扫 @Configuration,领域装配要显式带进来
class RecognitionApiTest {

    /** 这个来源名召回得出 6 个候选(见 {@code CandidateRecallTest}),所以模型真的会被调到。 */
    private static final String RECALLING_SOURCE = "自己刷题 · 增长率专项";

    /** 种子里真实存在的来源名,一个候选都召回不出来 —— 用来验「召回为空就不调模型」。 */
    private static final String SILENT_SOURCE = "粉笔 · 资料分析系统班 L12";

    /** 记录挂在这个考点上;它<b>不在</b> {@link #RECALLING_SOURCE} 的候选里,所以模型挑的必然是另一个。 */
    private static final String RECORD_NODE = "share-calc";

    /** 召回结果里的第一个(树序)—— 模型替身会挑它。 */
    private static final String RECALLED_NODE = "growth-rate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryTouchStore store;

    @Autowired
    private RecordTagStore tags;

    @Autowired
    private ScriptedVisionTagger tagger;

    @Autowired
    private ScriptedAsrClient asr;

    @Autowired
    private TaggingService tagging;

    /** 抓所有级别的日志 —— 「不进日志的<b>任何级别</b>」这句话要能被验一次。 */
    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger rootLogger;
    private Level originalLevel;

    @BeforeEach
    void reset() {
        Instant now = Instant.now();
        store.reset(List.of(
                new Touch("t-1", RECORD_NODE, RECALLING_SOURCE, TouchKind.PHOTO, now.minusSeconds(600), null),
                new Touch("t-2", RECORD_NODE, SILENT_SOURCE, TouchKind.VOICE, now.minusSeconds(1200), null)));
        tags.findAll().forEach(t -> tags.deleteByRecord(t.recordId()));
        tagger.reset();
        asr.reset();

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

    // ======================================================== 一、形态:图片是 JSON,不是 multipart

    @Test
    @DisplayName("🔴 /image 是 JSON + base64 内联 —— 一张图进去,库里落下一条 origin=auto 的标签")
    void imageHitLandsAnAutoTag() throws Exception {
        tagger.script(candidates -> RecognitionResult.of(candidates.get(0).code(), 0.91));

        mockMvc.perform(post("/api/records/t-1/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(jpeg("one"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUGGESTED"))
                .andExpect(jsonPath("$.confidence").value(0.91))
                .andExpect(jsonPath("$.candidateCount").value(6))
                .andExpect(jsonPath("$.tag.nodeCode").value(RECALLED_NODE))
                // 🔴 origin 是 auto,不是 manual —— 模型挑的考点被记成手动的,
                //    1.2.5.2 的准确率口径(标对的/标了的)当场失真
                .andExpect(jsonPath("$.tag.origin").value("auto"));

        List<RecordTag> stored = tags.findByRecord("t-1");
        assertEquals(1, stored.size(), "命中应当【真的往库里落一行】,不是只在响应里说一声");
        assertEquals(TagOrigin.AUTO, stored.get(0).origin(),
                "🔴 这一行的 origin 必须是 auto —— RecordTag.primaryOf 上那处 ⚪ 说的就是这条路");
        assertEquals(RECALLED_NODE, stored.get(0).nodeCode());
    }

    @Test
    @DisplayName("🔴 multipart 打到 /image 上 → 415,它不是一个会落盘的上传接口")
    void imageRefusesMultipart() throws Exception {
        // docs/10 §6.2 原文:「🔴 JSON body,base64 内联,【不是 multipart 落盘】」。
        // multipart 的默认行为是把 part 写成临时文件 —— 这个端点连收都不收。
        mockMvc.perform(multipart("/api/records/t-1/image")
                        .file(new MockMultipartFile("image", "a.jpg", "image/jpeg", jpeg("x"))))
                .andExpect(status().isUnsupportedMediaType());

        assertEquals(0, tagger.calls(), "被形态拦下的请求不该花掉一次模型调用");
    }

    @Test
    @DisplayName("🔴 单次最多 6 张(连拍合并)—— 第 7 张让整个请求 400")
    void imageRejectsMoreThanSixPhotos() throws Exception {
        byte[][] seven = new byte[7][];
        for (int i = 0; i < seven.length; i++) {
            seven[i] = jpeg("p" + i);
        }
        mockMvc.perform(post("/api/records/t-1/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(seven)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertEquals(0, tagger.calls(), "整批被拒时,一次模型都不该调 —— 拒绝要发生在花钱之前");
        assertTrue(tags.findAll().isEmpty());
    }

    @Test
    @DisplayName("🔴 R-07:请求体里塞一个标签名 → 400,不是被静默忽略")
    void imageRejectsAnyExtraField() throws Exception {
        String body = "{\"photos\":[\"" + base64(jpeg("x")) + "\"],\"tag\":\"我自己起的考点\"}";
        mockMvc.perform(post("/api/records/t-1/image")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));

        assertEquals(0, tagger.calls());
        assertTrue(tags.findAll().isEmpty(), "被拒的请求一条标签都不该落");
    }

    @Test
    @DisplayName("认不出格式的字节 → 400,而且是在调模型【之前】拒的")
    void imageRejectsUnknownFormatBeforeSpendingAnything() throws Exception {
        mockMvc.perform(post("/api/records/t-1/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(jpeg("ok"), "这不是一张图".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_FORMAT"))
                // 报错里只有「第几张」,没有那张图的任何内容
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("第 2 张")));

        assertEquals(0, tagger.calls(),
                "第 2 张不合法就整个请求 400 —— 第 1 张的模型钱不该已经花出去了");
    }

    @Test
    @DisplayName("六张加起来也有预算 —— 单张上限管不住「每张都刚好合法」这种凑法")
    void totalBudgetIsCheckedNotJustPerPhoto() {
        // 这一条不走 HTTP:验的是形状上那条规则本身,而把十几 MB 的 base64 塞进 MockMvc
        // 只会让一条断言变成一次内存压测。
        int perPhoto = 5_000_000;   // 单张合法(< 4 MiB 那条由 @Size 管,这里只看总量这一条)
        assertTrue(new PhotoRecognitionRequest(List.of(new byte[perPhoto], new byte[perPhoto]))
                        .isWithinTotalBudget(),
                "一千万字节还在 12 MiB 预算内");
        assertFalse(new PhotoRecognitionRequest(
                        List.of(new byte[perPhoto], new byte[perPhoto], new byte[perPhoto]))
                        .isWithinTotalBudget(),
                "🔴 一千五百万字节超了 12 MiB —— 原图只在内存里过一次,这也是这次请求的内存预算");
    }

    // ======================================================== 二、连拍:命中即停 / 不匹配才换下一张

    @Test
    @DisplayName("连拍 3 张,第一张就命中 → 只调一次模型,不给同一条记录挂第二个考点")
    void burstStopsAtTheFirstHit() throws Exception {
        tagger.script(candidates -> RecognitionResult.of(candidates.get(0).code(), 0.88));

        mockMvc.perform(post("/api/records/t-1/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(jpeg("a"), jpeg("b"), jpeg("c"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUGGESTED"));

        assertEquals(1, tagger.calls(), "挂上考点之后继续送,只是再花钱 —— 连拍是同一份材料的多张");
        assertEquals(1, tags.findByRecord("t-1").size(), "一条记录不该因为连拍而挂上三个考点");
    }

    @Test
    @DisplayName("连拍 3 张都没认出来 → 三张都送了,一条标签都不落")
    void burstKeepsTryingOnNoMatch() throws Exception {
        tagger.script(candidates -> RecognitionResult.noMatch(0.42));

        mockMvc.perform(post("/api/records/t-1/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(jpeg("a"), jpeg("b"), jpeg("c"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NO_MATCH"))
                // 🔴 0.42 分被阈值丢掉 ≠ 什么都没认出来。压成同一个数,排查线索就没了
                .andExpect(jsonPath("$.confidence").value(0.42));

        assertEquals(3, tagger.calls(), "这一张没认出来,下一张可能拍得更清楚 —— 连拍的意义就在这");
        assertTrue(tags.findAll().isEmpty(), "宁缺毋滥:没认出来就不挂,不硬凑最接近的考点");
    }

    @Test
    @DisplayName("召回为空 → 一次模型都不调,而且不会因为图多而调六次")
    void noRecallMeansNoModelCall() throws Exception {
        mockMvc.perform(post("/api/records/t-2/image")     // t-2 的来源名召回不出候选
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(jpeg("a"), jpeg("b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_RECALLED"))
                .andExpect(jsonPath("$.candidateCount").value(0));

        assertEquals(0, tagger.calls(), "「召回不出来就不调模型,调了也只能瞎猜」(docs/13 §1.3)");
    }

    // ======================================================== 三、降级:识别挂了 ≠ 记录挂了

    @Test
    @DisplayName("🔴 视觉模型挂了 → 200 而不是 503;记录完好,而且还挂得上手动考点")
    void visionOutageNeverBreaksTheRecord() throws Exception {
        tagger.script(candidates -> {
            throw new RecognitionUnavailableException("测试:厂商挂了");
        });

        mockMvc.perform(post("/api/records/t-1/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(jpeg("a"), jpeg("b"), jpeg("c"))))
                // 回 503 会让前端把它当成一次失败去重试,而它没有失败(docs/13 §1.5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNAVAILABLE"));

        assertEquals(1, tagger.calls(), "厂商挂了就别再试 5 次 —— 用户只会多等 5 倍时间");

        Touch survivor = tagging.findRecord("t-1");
        assertNotNull(survivor, "🔴 识别不可用 ≠ 记录失败(docs/08 §1.3.7.1)");
        // 「还能手动挂载」不是一句安慰:直接走打标服务验一次
        TaggingService.MountResult mounted = tagging.mount(survivor, RECALLED_NODE);
        assertTrue(mounted instanceof TaggingService.MountResult.Mounted,
                "降级方向是「少功能」,不是「少记录」—— 手动挂载必须一直可用");
    }

    @Test
    @DisplayName("记录不存在 → 404,而且消息里不回显那个 id")
    void missingRecordIsFourOhFour() throws Exception {
        mockMvc.perform(post("/api/records/t-does-not-exist/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(jpeg("a"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("t-does-not-exist"))));
    }

    // ======================================================== 四、🔴 字节不进日志的任何级别

    @Test
    @DisplayName("🔴 一整轮成功的图片识别之后,任何级别的日志里都没有那张图的一个字节")
    void imageBytesNeverReachAnyLogLevel() throws Exception {
        captureEveryLevel();
        tagger.script(candidates -> RecognitionResult.of(candidates.get(0).code(), 0.9));

        String marker = "KAODIAN-PIXEL-MARKER";
        byte[] photo = jpeg(marker);

        mockMvc.perform(post("/api/records/t-1/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(photo)))
                .andExpect(status().isOk());

        assertNoLogContains(marker, base64(photo));
    }

    @Test
    @DisplayName("🔴 被拒的图片请求同样不进日志 —— 出错时最容易顺手把请求体打出来")
    void rejectedImageBytesNeverReachAnyLogLevel() throws Exception {
        captureEveryLevel();
        String marker = "KAODIAN-REJECTED-MARKER";
        byte[] notAnImage = ("XXXX" + marker).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/records/t-1/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(notAnImage)))
                .andExpect(status().isBadRequest());

        assertNoLogContains(marker, base64(notAnImage));
    }

    @Test
    @DisplayName("🔴 音频字节同样不进日志的任何级别")
    void audioBytesNeverReachAnyLogLevel() throws Exception {
        captureEveryLevel();
        String marker = "KAODIAN-AUDIO-MARKER";
        byte[] clip = wav(3, marker);

        mockMvc.perform(multipart("/api/records/t-1/audio")
                        .file(new MockMultipartFile("audio", "clip.wav", "audio/wav", clip)))
                .andExpect(status().isOk());

        assertNoLogContains(marker, base64(clip));
    }

    // ======================================================== 五、音频:≤60s 由服务端算,不留存

    @Test
    @DisplayName("🔴 ASR 没接入 → 200 + UNAVAILABLE,提示重录;记录完好,一条标签都不落")
    void asrOutageAsksForARetake() throws Exception {
        asr.script(clip -> {
            throw new RecognitionUnavailableException("测试:ASR 未接入");
        });

        mockMvc.perform(multipart("/api/records/t-1/audio")
                        .file(new MockMultipartFile("audio", "clip.wav", "audio/wav", wav(10, "hi"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNAVAILABLE"))
                // 契约原文:「失败提示重录」
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重录")));

        assertNotNull(tagging.findRecord("t-1"), "转写失败不该动那条记录");
        assertTrue(tags.findAll().isEmpty());
    }

    @Test
    @DisplayName("🔴 61 秒的录音 → 413,而且 ASR 一次都没被调到(校验在花钱之前)")
    void audioLongerThanSixtySecondsIsRejectedServerSide() throws Exception {
        mockMvc.perform(multipart("/api/records/t-1/audio")
                        .file(new MockMultipartFile("audio", "clip.wav", "audio/wav", wav(61, "x"))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("AUDIO_TOO_LONG"));

        assertEquals(0, asr.calls(),
                "🔴 60 秒这条线是服务端自己从 WAV 头算出来的,而且要在调 ASR 之前算");
    }

    @Test
    @DisplayName("59 秒的录音正常放行 —— 上限拦的是超长,不是「凡是长的都拦」")
    void audioJustUnderTheLimitPassesThrough() throws Exception {
        asr.script(clip -> "这段话不该出现在任何地方");

        mockMvc.perform(multipart("/api/records/t-1/audio")
                        .file(new MockMultipartFile("audio", "clip.wav", "audio/wav", wav(59, "x"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NO_TEXT_TAGGER"));

        assertEquals(1, asr.calls());
    }

    @Test
    @DisplayName("🔴 转写成功时,那段文字不出现在响应体的任何一个字段里")
    void transcriptNeverLeavesTheProcess() throws Exception {
        captureEveryLevel();
        String spoken = "刚才那道题问的是增长率";
        asr.script(clip -> spoken);

        String body = mockMvc.perform(multipart("/api/records/t-1/audio")
                        .file(new MockMultipartFile("audio", "clip.wav", "audio/wav", wav(5, "x"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NO_TEXT_TAGGER"))
                .andReturn().getResponse().getContentAsString();

        // 🔴 「不留存」如果只兑现在库那一侧是守不住的:原样吐回响应体,那段文字就进了
        //    访问日志、进了前端缓存、进了任何一个把响应体存下来的中间层。
        assertFalse(body.contains(spoken),
                "转写文本进了响应体 —— AudioRecognitionResponse 里本来就没有能装下它的位置,"
                        + "出现它说明有人加了一个。先去 docs/10 §5.2「不建的表」那一行看一眼。\n" + body);
        assertNoLogContains(spoken);
        assertTrue(tags.findAll().isEmpty(), "转写不产生标签 —— 「文字 → 考点」那一段还没建");
    }

    @Test
    @DisplayName("转写出来是空的 → NOTHING_HEARD,与「没跑成」分开说")
    void emptyTranscriptAsksForARetake() throws Exception {
        asr.script(clip -> "   ");

        mockMvc.perform(multipart("/api/records/t-1/audio")
                        .file(new MockMultipartFile("audio", "clip.wav", "audio/wav", wav(5, "x"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOTHING_HEARD"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重录")));
    }

    @Test
    @DisplayName("🔴 不是 WAV 的字节 → 415,不放行也不「那就不校验时长了」")
    void nonWavIsRejectedRatherThanSkippingTheDurationCheck() throws Exception {
        // 「算不出时长就不校验时长」是这段代码里最容易写出来的一句降级,
        // 而它正好把这条服务端校验变成一句空话 —— 送一段容器认不出的字节就绕过去了。
        mockMvc.perform(multipart("/api/records/t-1/audio")
                        .file(new MockMultipartFile("audio", "clip.m4a", "audio/mp4", jpeg("not audio"))))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_AUDIO_FORMAT"));

        assertEquals(0, asr.calls());
    }

    @Test
    @DisplayName("🔴 声明的 Content-Type 一概不作数 —— 判据是 WAV 头本身")
    void declaredContentTypeIsNotTrusted() throws Exception {
        // 客户端说这是 wav,字节说这是 jpeg。这个端点的全部要点就是不信前者。
        mockMvc.perform(multipart("/api/records/t-1/audio")
                        .file(new MockMultipartFile("audio", "lie.wav", "audio/wav", jpeg("lie"))))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_AUDIO_FORMAT"));

        // 反过来:字节是 wav,声明成 octet-stream —— 照样收
        asr.script(clip -> "ok");
        mockMvc.perform(multipart("/api/records/t-1/audio")
                        .file(new MockMultipartFile("audio", "clip.bin",
                                "application/octet-stream", wav(2, "x"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("自相矛盾的 WAV 头 → 415:自报的 byteRate 要能被别的数验一遍才算数")
    void inconsistentWavHeaderIsRejected() throws Exception {
        byte[] clip = wav(10, "x");
        // 把 byteRate 改小十倍:这样一段 10 秒的录音会「算成」100 秒 ——
        // 反过来也一样,改大就能让一段 10 分钟的录音假装成 60 秒。
        writeU32(clip, 28, 3_200);
        mockMvc.perform(multipart("/api/records/t-1/audio")
                        .file(new MockMultipartFile("audio", "clip.wav", "audio/wav", clip)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_AUDIO_FORMAT"));

        assertEquals(0, asr.calls());
    }

    @Test
    @DisplayName("没带 audio 这个 part → 400,而不是一个没有错误码的通用拒绝")
    void missingPartIsANamedError() throws Exception {
        mockMvc.perform(multipart("/api/records/t-1/audio"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_AUDIO"));
    }

    // ======================================================== 工具

    /**
     * 🔴 把根 logger 调到 TRACE。
     *
     * <p>要验的是「不进日志的<b>任何级别</b>」,而默认 INFO 下一句 {@code log.debug(base64)}
     * 根本不会被抓到 —— <b>那样的绿是假的</b>。只在需要的用例里调,是因为整类都跑 TRACE
     * 会把 Spring 内部的日志一起放出来,慢而且没有额外收获。
     */
    private void captureEveryLevel() {
        rootLogger.setLevel(Level.TRACE);
    }

    /** 断言这些串一个都没出现在<b>任何级别</b>的日志里(含参数、含异常栈)。 */
    private void assertNoLogContains(String... needles) {
        for (ILoggingEvent event : List.copyOf(logs.list)) {
            String rendered = event.getFormattedMessage()
                    + " " + event.getLoggerName()
                    + " " + (event.getThrowableProxy() == null
                            ? "" : event.getThrowableProxy().getMessage());
            for (String needle : needles) {
                assertFalse(rendered.contains(needle), () -> """
                        🔴 R-04 被破坏 —— 上传的字节出现在了日志里(级别 %s,logger %s)。

                        docs/10 §8.1 禁令 3:「不把 base64 打进日志的任何级别」——
                        一次 log.debug(request) 就等于把原图落了盘,而且落在最不容易想到的地方。
                        这条断言把根 logger 调到 TRACE 之后抓的,所以它管的是【任何级别】。
                        """.formatted(event.getLevel(), event.getLoggerName()));
            }
        }
    }

    /** {@code {"photos":["<base64>", ...]}} —— 线上就是这个形状。 */
    private static String bodyWith(byte[]... photos) {
        StringBuilder sb = new StringBuilder("{\"photos\":[");
        for (int i = 0; i < photos.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(base64(photos[i])).append('"');
        }
        return sb.append("]}").toString();
    }

    private static String base64(byte[] bytes) {
        // 🔴 JDK 自带的那个 —— 本轮硬约束:pom.xml 一个字不改
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 一张「JPEG」:三个魔数字节 + 一段可辨认的载荷。识别侧只看魔数。 */
    private static byte[] jpeg(String payload) {
        byte[] tail = payload.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[3 + tail.length];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xD8;
        out[2] = (byte) 0xFF;
        System.arraycopy(tail, 0, out, 3, tail.length);
        return out;
    }

    /**
     * 一段 16 kHz / 16 bit / 单声道的 PCM WAV,时长精确到给定秒数。
     *
     * <p>{@code marker} 写进音频数据区,用来验「这段字节没有出现在日志里」。
     */
    private static byte[] wav(int seconds, String marker) {
        int byteRate = 16_000 * 2;
        int dataLength = byteRate * seconds;
        byte[] out = new byte[44 + dataLength];

        writeAscii(out, 0, "RIFF");
        writeU32(out, 4, 36 + dataLength);
        writeAscii(out, 8, "WAVE");
        writeAscii(out, 12, "fmt ");
        writeU32(out, 16, 16);              // fmt 块长
        writeU16(out, 20, 1);               // PCM
        writeU16(out, 22, 1);               // 单声道
        writeU32(out, 24, 16_000);          // 采样率
        writeU32(out, 28, byteRate);
        writeU16(out, 32, 2);               // blockAlign
        writeU16(out, 34, 16);              // 位深
        writeAscii(out, 36, "data");
        writeU32(out, 40, dataLength);

        byte[] tail = marker.getBytes(StandardCharsets.UTF_8);
        if (tail.length <= dataLength) {
            System.arraycopy(tail, 0, out, 44, tail.length);
        }
        return out;
    }

    private static void writeAscii(byte[] out, int at, String tag) {
        for (int i = 0; i < tag.length(); i++) {
            out[at + i] = (byte) tag.charAt(i);
        }
    }

    private static void writeU16(byte[] out, int at, int value) {
        out[at] = (byte) (value & 0xFF);
        out[at + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeU32(byte[] out, int at, int value) {
        out[at] = (byte) (value & 0xFF);
        out[at + 1] = (byte) ((value >> 8) & 0xFF);
        out[at + 2] = (byte) ((value >> 16) & 0xFF);
        out[at + 3] = (byte) ((value >> 24) & 0xFF);
    }

    // ======================================================== 装配

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
                                      RecordTagStore tagStore, CoverageService coverage, Clock clock) {
            return new CoverageReader(syllabus, store, tagStore, coverage, clock);
        }

        @Bean
        TaggingService taggingService(TouchStore store, RecordTagStore tagStore, SyllabusSource syllabus,
                                      CandidateRecall recall, VisionTagger tagger, Clock clock) {
            return new TaggingService(store, tagStore, syllabus, recall, tagger, clock);
        }

        @Bean
        ScriptedVisionTagger visionTagger() {
            return new ScriptedVisionTagger();
        }

        @Bean
        ScriptedAsrClient asrClient() {
            return new ScriptedAsrClient();
        }
    }

    /**
     * 可编排的视觉替身。
     *
     * <p>它<b>数调用次数</b>,而那正是好几条断言的全部内容:「命中即停」「拒绝发生在花钱之前」
     * 这类话如果只验返回值,一个每张图都调一遍模型的实现会全绿。
     * <p>🔴 它<b>刻意不留下 image 的任何副本</b> —— 一个把字节存进 List 供断言的替身,
     * 会让「不留存」这件事在测试里先被破坏一次。
     */
    static final class ScriptedVisionTagger implements VisionTagger {

        private final AtomicInteger calls = new AtomicInteger();
        private Function<List<Candidate>, RecognitionResult> script = candidates -> RecognitionResult.noMatch();

        void reset() {
            calls.set(0);
            script = candidates -> RecognitionResult.noMatch();
        }

        void script(Function<List<Candidate>, RecognitionResult> script) {
            this.script = script;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates) {
            if (candidates == null || candidates.isEmpty()) {
                throw new IllegalArgumentException("闭集分类必须给候选集");
            }
            calls.incrementAndGet();
            return script.apply(candidates);
        }
    }

    /** 可编排的 ASR 替身。同样只数次数,不留副本。 */
    static final class ScriptedAsrClient implements AsrClient {

        private final AtomicInteger calls = new AtomicInteger();
        private Function<byte[], String> script = clip -> {
            throw new RecognitionUnavailableException("默认:ASR 未接入");
        };

        void reset() {
            calls.set(0);
            script = clip -> {
                throw new RecognitionUnavailableException("默认:ASR 未接入");
            };
        }

        void script(Function<byte[], String> script) {
            this.script = script;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public String transcribe(byte[] clip, String mimeType) {
            calls.incrementAndGet();
            return script.apply(clip);
        }
    }

    /** 与 {@code TagApiTest} 里那个同形 —— 接口契约测试不该被存储实现牵着走(docs/10 §2.2)。 */
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
            throw new UnsupportedOperationException("识别不改挂记录");
        }
    }
}
