package com.kaodian.server.collect;

import com.kaodian.server.collect.CaptureService.CaptureRequest;
import com.kaodian.server.collect.CaptureService.CaptureResult;
import com.kaodian.server.collect.CaptureService.Mounting;
import com.kaodian.server.collect.CaptureService.Rejection;
import com.kaodian.server.recognize.AsrClient;
import com.kaodian.server.recognize.RecognitionResult;
import com.kaodian.server.recognize.RecognitionUnavailableException;
import com.kaodian.server.recognize.StubAsrClient;
import com.kaodian.server.recognize.StubVisionTagger;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.SyllabusLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「记一笔」的红线测试。
 *
 * <p>三条被钉住的东西:<b>识别失败不导致记录丢失</b>(docs/08 §1.3.7.1)、
 * <b>挂载只认树里的 code</b>(R-07)、<b>闭集分类的形状长在签名上</b>(docs/09 坑一/坑二)。
 */
class CaptureServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final byte[] IMAGE = {1, 2, 3};

    @TempDir
    Path dataDir;

    private FileTouchStore store;

    private CaptureService serviceWith(VisionTagger tagger) {
        store = new FileTouchStore(dataDir.resolve("touches.json"));
        return new CaptureService(store, tagger, SyllabusLoader.loadDefault(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 说什么就返回什么的假识别 —— 用来构造各种「模型这么答了会怎样」。 */
    private record FakeTagger(RecognitionResult answer) implements VisionTagger {
        @Override
        public RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates) {
            return answer;
        }
    }

    /** 直接把 code 硬塞回去,绕开 RecognitionResult.of 的阈值 —— 模拟一个不老实的实现类。 */
    private record RogueTagger(String nodeCode) implements VisionTagger {
        @Override
        public RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates) {
            return new RecognitionResult(nodeCode, 0.99, true);
        }
    }

    private record DeadTagger() implements VisionTagger {
        @Override
        public RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates) {
            throw new RecognitionUnavailableException("模型超时");
        }
    }

    // ——————————————————— 手动路径 ———————————————————

    @Test
    @DisplayName("手动挂载:落地,挂载来源是「用户自己挑的」,不消耗任何模型")
    void manualCaptureLands() {
        CaptureService service = serviceWith(new StubVisionTagger());
        CaptureResult result = service.capture(
                CaptureRequest.manual(TouchKind.MANUAL, "粉笔 · 资料分析系统班 L12", "average-calc"));

        CaptureResult.Recorded recorded = assertInstanceOf(CaptureResult.Recorded.class, result);
        assertEquals("average-calc", recorded.touch().nodeCode());
        assertEquals(Mounting.USER_PICKED, recorded.mounting());
        assertEquals(NOW, recorded.touch().occurredAt());
        assertEquals(9, store.count(), "种子 8 条 + 新记的这条");
    }

    @Test
    @DisplayName("记做题:两个整数原样存下来,不做任何判断")
    void drillNumbersAreCopiedVerbatim() {
        CaptureService service = serviceWith(new StubVisionTagger());
        CaptureResult result = service.capture(
                new CaptureRequest(TouchKind.DRILL, "自己刷题", "yoy-mom", 10, 3));

        Touch t = assertInstanceOf(CaptureResult.Recorded.class, result).touch();
        assertEquals(10, t.drill().practiced());
        assertEquals(3, t.drill().correct(), "30% —— 是用户填的数,产品没有判过任何一道题");
    }

    @Test
    @DisplayName("🔴 挂载只认考点树里的 code:树外的 code 一律拒绝,不会顺手建一个新考点(R-07)")
    void freeTextTagsCannotEnter() {
        CaptureService service = serviceWith(new StubVisionTagger());
        CaptureResult result = service.capture(
                CaptureRequest.manual(TouchKind.MANUAL, "某来源", "我自己起的考点名"));

        assertEquals(Rejection.NODE_NOT_IN_SYLLABUS,
                assertInstanceOf(CaptureResult.Rejected.class, result).reason());
        assertEquals(8, store.count(), "被拒的记录不该落库");
    }

    @Test
    @DisplayName("手动记录没挑考点 → 拒绝,而且理由说的是「没挑」不是「没认出来」")
    void manualWithoutNodeIsRejectedWithItsOwnReason() {
        CaptureService service = serviceWith(new StubVisionTagger());
        CaptureResult result = service.capture(
                CaptureRequest.manual(TouchKind.MANUAL, "某来源", "   "));

        assertEquals(Rejection.MISSING_NODE_CODE,
                assertInstanceOf(CaptureResult.Rejected.class, result).reason());
    }

    // ——————————————————— 🔴 识别失败不导致记录丢失 ———————————————————

    @Test
    @DisplayName("🔴 识别说不匹配,但用户已经挑了考点 → 照样落地(docs/08 §1.3.7.1)")
    void noMatchDoesNotLoseTheRecordWhenUserPicked() {
        CaptureService service = serviceWith(new FakeTagger(RecognitionResult.noMatch(0.31)));
        CaptureResult result = service.captureFromPhoto(
                CaptureRequest.manual(TouchKind.PHOTO, "自己刷题", "growth-rate"), IMAGE, "image/jpeg");

        CaptureResult.Recorded recorded = assertInstanceOf(CaptureResult.Recorded.class, result);
        assertEquals("growth-rate", recorded.touch().nodeCode());
        assertEquals(Mounting.USER_PICKED, recorded.mounting(), "用户挑的优先,识别只是锦上添花");
        assertEquals(0.31, recorded.recognition().confidence(), 1e-9, "识别结果原样带回,供界面提示");
        assertEquals(9, store.count());
    }

    @Test
    @DisplayName("🔴 识别服务整个挂了,但用户已经挑了考点 → 照样落地")
    void recognizerDownDoesNotLoseTheRecordWhenUserPicked() {
        CaptureService service = serviceWith(new DeadTagger());
        CaptureResult result = service.captureFromPhoto(
                CaptureRequest.manual(TouchKind.PHOTO, "自己刷题", "growth-rate"), IMAGE, "image/jpeg");

        assertInstanceOf(CaptureResult.Recorded.class, result);
        assertEquals(9, store.count(), "模型挂了,记录动作本身不能失败");
    }

    @Test
    @DisplayName("识别命中且用户没挑 → 挂模型选的那个,挂载来源标成「识别挑的」")
    void recognizedMountIsRecordedAsSuch() {
        CaptureService service = serviceWith(new FakeTagger(RecognitionResult.of("truncate-divide", 0.91)));
        CaptureResult result = service.captureFromPhoto(
                CaptureRequest.manual(TouchKind.PHOTO, "自己刷题", null), IMAGE, "image/jpeg");

        CaptureResult.Recorded recorded = assertInstanceOf(CaptureResult.Recorded.class, result);
        assertEquals("truncate-divide", recorded.touch().nodeCode());
        assertEquals(Mounting.RECOGNIZED, recorded.mounting());
    }

    @Test
    @DisplayName("🔴 两种拒绝要分得开:「没认出来」和「服务不可用」在界面上说的话不一样")
    void twoKindsOfRejectionAreDistinguishable() {
        CaptureRequest noNode = CaptureRequest.manual(TouchKind.PHOTO, "自己刷题", null);

        CaptureResult noMatch = serviceWith(new FakeTagger(RecognitionResult.noMatch()))
                .captureFromPhoto(noNode, IMAGE, "image/jpeg");
        assertEquals(Rejection.NO_MATCH_AND_NO_USER_NODE,
                assertInstanceOf(CaptureResult.Rejected.class, noMatch).reason());
        assertEquals(8, store.count(), "挂不上考点的记录不入库");

        CaptureResult dead = serviceWith(new DeadTagger()).captureFromPhoto(noNode, IMAGE, "image/jpeg");
        assertEquals(Rejection.RECOGNIZER_UNAVAILABLE_AND_NO_USER_NODE,
                assertInstanceOf(CaptureResult.Rejected.class, dead).reason());
    }

    // ——————————————————— 🔴 闭集与阈值 ———————————————————

    @Test
    @DisplayName("🔴 模型编了一个树里没有的考点 → 出口处被拦掉,不入库(docs/09 坑一)")
    void hallucinatedNodeIsRejectedAtTheOutput() {
        CaptureService service = serviceWith(new RogueTagger("机构标准表述-增长率速算"));
        CaptureResult result = service.captureFromPhoto(
                CaptureRequest.manual(TouchKind.PHOTO, "自己刷题", null), IMAGE, "image/jpeg");

        CaptureResult.Rejected rejected = assertInstanceOf(CaptureResult.Rejected.class, result);
        assertEquals(Rejection.NO_MATCH_AND_NO_USER_NODE, rejected.reason());
        assertFalse(rejected.recognition().matched(), "候选集之外的 code 一律降级为 NO_MATCH");
        assertEquals(0.99, rejected.recognition().confidence(), 1e-9, "置信度留着,便于排查是召回问题还是模型乱答");
        assertEquals(8, store.count());
    }

    @Test
    @DisplayName("🔴 宁缺毋滥:置信度低于阈值一律 NO_MATCH,不硬凑最接近的考点")
    void belowThresholdIsAlwaysNoMatch() {
        RecognitionResult low = RecognitionResult.of("growth-rate", RecognitionResult.MIN_CONFIDENCE - 0.01);
        assertFalse(low.matched(), "低于阈值不允许带出 nodeCode");
        assertNull(low.nodeCode());

        RecognitionResult ok = RecognitionResult.of("growth-rate", RecognitionResult.MIN_CONFIDENCE);
        assertTrue(ok.matched(), "刚好到线算过");

        // 不变式挡住「硬塞一个低置信度的 code」这种写法
        assertThrows(IllegalArgumentException.class, () -> new RecognitionResult("growth-rate", 0.1, true));
        assertThrows(IllegalArgumentException.class, () -> new RecognitionResult("growth-rate", 0.99, false));
        assertThrows(IllegalArgumentException.class, () -> new RecognitionResult(null, 0.99, true));
    }

    @Test
    @DisplayName("🔴 Stub 诚实失败:视觉 stub 一律 NO_MATCH,ASR stub 直接抛 —— 都不假装成功")
    void stubsFailHonestly() {
        VisionTagger vision = new StubVisionTagger();
        RecognitionResult r = vision.classify(IMAGE, "image/jpeg",
                List.of(new VisionTagger.Candidate("growth-rate", "增长率计算")));
        assertFalse(r.matched(), "没接模型就不该挑出考点 —— 假装成功比诚实失败危险得多");
        assertEquals(0.0, r.confidence(), 1e-9);

        assertThrows(IllegalArgumentException.class,
                () -> vision.classify(IMAGE, "image/jpeg", List.of()), "没有候选就没有『集』可闭");

        AsrClient asr = new StubAsrClient();
        assertThrows(RecognitionUnavailableException.class, () -> asr.transcribe(new byte[]{1}, "audio/wav"),
                "失败就抛,不返回空串这种半成品");
    }

    // ——————————————————— 🔴 形状层面的红线 ———————————————————

    @Test
    @DisplayName("🔴 识别结果的形状里没有自由文本标签的位置(docs/10 §3.1)")
    void recognitionResultHasNoLabelField() {
        List<String> fields = Arrays.stream(RecognitionResult.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertEquals(List.of("nodeCode", "confidence", "aboveThreshold"), fields);

        for (String forbidden : List.of("label", "tag", "tagName", "name", "text", "content", "explanation")) {
            assertFalse(fields.contains(forbidden),
                    "识别结果不允许出现自由文本标签字段(docs/09 坑一):" + forbidden);
        }
    }

    @Test
    @DisplayName("🔴 识别接口上不能出现 URL / fileId / 存储路径 —— 图片只能以字节形式过一次(docs/09 坑二)")
    void recognizeInterfacesTakeBytesNotReferences() throws Exception {
        Method classify = VisionTagger.class.getMethod("classify", byte[].class, String.class, List.class);
        assertEquals(byte[].class, classify.getParameterTypes()[0], "图片必须是字节,不是引用");
        assertEquals(RecognitionResult.class, classify.getReturnType());
        assertEquals(List.class, classify.getParameterTypes()[2], "候选集是必填入参 —— 闭集不是可选项");

        Method transcribe = AsrClient.class.getMethod("transcribe", byte[].class, String.class);
        assertEquals(byte[].class, transcribe.getParameterTypes()[0]);

        for (Class<?> type : List.of(VisionTagger.class, AsrClient.class)) {
            for (Method m : type.getDeclaredMethods()) {
                for (Class<?> p : m.getParameterTypes()) {
                    assertNotEquals(java.net.URL.class, p, type.getSimpleName() + " 不许收 URL");
                    assertNotEquals(java.net.URI.class, p, type.getSimpleName() + " 不许收 URI");
                    assertNotEquals(java.nio.file.Path.class, p, type.getSimpleName() + " 不许收文件路径");
                }
                String name = m.getName().toLowerCase();
                assertFalse(name.contains("file") || name.contains("url") || name.contains("upload"),
                        "方法名里出现 file/url/upload 就是在开「先存起来再引用」那条路:" + m.getName());
            }
        }
    }

    @Test
    @DisplayName("🔴 采集入参里同样没有装内容的字段 —— 与 Touch 是同一条线")
    void captureRequestCarriesNoContent() {
        List<String> fields = Arrays.stream(CaptureRequest.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertEquals(List.of("kind", "sourceName", "nodeCode", "practiced", "correct"), fields);
    }
}
