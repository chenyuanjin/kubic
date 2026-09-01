package com.kaodian.server.collect;

import com.kaodian.server.collect.TaggingService.MountResult;
import com.kaodian.server.collect.TaggingService.Outcome;
import com.kaodian.server.collect.TaggingService.Suggestion;
import com.kaodian.server.recognize.RecognitionResult;
import com.kaodian.server.recognize.RecognitionUnavailableException;
import com.kaodian.server.recognize.StubVisionTagger;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.SyllabusLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打标管线的行为测试 —— docs/technical/后端系统设计与组件接入.md §1.3 那张图从上到下走一遍。
 *
 * <h2>立场:每条用例先回答「这一步<b>没有</b>发生会怎样」</h2>
 *
 * 这条管线里有三段的作用是「丢掉」,而丢掉这件事在界面上看不见 ——
 * 一次不该发生的模型调用、一条不该复活的标签、一个不该被改写的 origin,
 * 全都<b>不会报错</b>。所以下面凡是「不该发生」的,一律用一个「一旦发生就炸」的替身钉住,
 * 而不是断言某个返回值。
 */
class TaggingServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final byte[] MATERIAL = {1, 2, 3};

    /** 这个来源名召回得出 6 个候选(见 {@code CandidateRecallTest})。 */
    private static final String RECALLING_SOURCE = "自己刷题 · 增长率专项";

    /** 这个来源名一个候选都召回不出来 —— 种子里真实存在的那种。 */
    private static final String SILENT_SOURCE = "粉笔 · 资料分析系统班 L12";

    private final InMemoryTouchStore touches = new InMemoryTouchStore();
    private final InMemoryRecordTagStore tags = new InMemoryRecordTagStore();

    private TaggingService serviceWith(VisionTagger tagger) {
        return new TaggingService(touches, tags, SyllabusLoader.loadDefault(),
                new CandidateRecall(), tagger, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Touch given(String id, String nodeCode, String sourceName) {
        Touch t = new Touch(id, nodeCode, sourceName, TouchKind.PHOTO, NOW.minusSeconds(60), null);
        touches.add(t);
        return t;
    }

    /** 说什么就答什么的假模型,顺带数一数自己被调了几次。 */
    private static final class CountingTagger implements VisionTagger {
        private final RecognitionResult answer;
        private final AtomicInteger calls = new AtomicInteger();

        CountingTagger(RecognitionResult answer) {
            this.answer = answer;
        }

        @Override
        public RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates) {
            calls.incrementAndGet();
            return answer;
        }
    }

    /** 一调用就炸。<b>它一旦被调用,那条用例就红</b> —— 这本身就是断言。 */
    private record ForbiddenTagger() implements VisionTagger {
        @Override
        public RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates) {
            throw new AssertionError("这条路不该调用模型");
        }
    }

    private record DeadTagger() implements VisionTagger {
        @Override
        public RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates) {
            throw new RecognitionUnavailableException("模型超时");
        }
    }

    /** 直接把 code 硬塞回去,绕开 RecognitionResult.of 的阈值 —— 模拟一个不老实的实现类。 */
    private record RogueTagger(String nodeCode) implements VisionTagger {
        @Override
        public RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates) {
            return new RecognitionResult(nodeCode, 0.99, true);
        }
    }

    // ———————————————— 一、召回为空就不调模型 ————————————————

    @Test
    @DisplayName("🔴 召回为空 → 标为未分类,连模型都不调(docs/technical/后端系统设计与组件接入.md §1.3:调了也只能瞎猜)")
    void anEmptyRecallNeverReachesTheModel() {
        Touch touch = given("t-1", "growth-rate", SILENT_SOURCE);
        Suggestion suggestion = serviceWith(new ForbiddenTagger()).suggest(touch, MATERIAL, "image/jpeg");

        assertEquals(Outcome.NOT_RECALLED, suggestion.outcome());
        assertEquals(0, suggestion.candidateCount(), "candidateCount 是 0 就是「压根没送进去看」");
        assertNull(suggestion.tag());
        assertEquals(0, tags.count(), "什么都不该落库");
    }

    @Test
    @DisplayName("🔴 有候选但服务端没有素材 → 也不调模型,而且理由与「没认出来」分得开")
    void withoutMaterialTheModelIsNotCalledEither() {
        // 拿零字节去调一次视觉模型是「假装成功」的另一种写法。诚实的做法是说清:
        // 不是模型没认出来,是这条记录已经没有可再看一遍的东西了(原图与转写都不留存)。
        Touch touch = given("t-1", "growth-rate", RECALLING_SOURCE);
        TaggingService service = serviceWith(new ForbiddenTagger());

        for (byte[] nothing : new byte[][]{null, new byte[0]}) {
            Suggestion suggestion = service.suggest(touch, nothing, "image/jpeg");
            assertEquals(Outcome.NO_MATERIAL, suggestion.outcome());
            assertEquals(6, suggestion.candidateCount(), "召回是成功的,停的是下一步");
            assertNotEquals(Outcome.NO_MATCH, suggestion.outcome(),
                    "「没素材」和「没认出来」在界面上说的话不一样,不能合成一个");
        }
        assertEquals(0, tags.count());
    }

    // ———————————————— 二、走完四段:命中、不命中、集外、挂了 ————————————————

    @Test
    @DisplayName("🔴 命中 → 落一条 origin=auto 的标签,而且 confirmedAt 必须是空的")
    void aMatchLandsAnUnconfirmedAutoTag() {
        Touch touch = given("t-1", "growth-rate", RECALLING_SOURCE);
        CountingTagger tagger = new CountingTagger(RecognitionResult.of("interval-growth", 0.91));

        Suggestion suggestion = serviceWith(tagger).suggest(touch, MATERIAL, "image/jpeg");

        assertEquals(Outcome.SUGGESTED, suggestion.outcome());
        assertEquals(1, tagger.calls.get(), "召回出了候选就该真的调一次");
        assertEquals(6, suggestion.candidateCount());

        RecordTag tag = suggestion.tag();
        assertEquals("interval-growth", tag.nodeCode());
        assertEquals(TagOrigin.AUTO, tag.origin());
        assertEquals(0.91, tag.confidence(), 1e-9);
        // 顺手填上「等于现在」会让 1.2.5.2 的准确率口径(标对的/标了的)分子恒等于分母。
        assertNull(tag.confirmedAt(), "这条是模型挑的,还没有人认过");
        assertFalse(tag.discarded());
        assertEquals(1, tags.count(), "而且它真的落库了,不只是返回给调用方看看");
    }

    @Test
    @DisplayName("模型说不匹配 → 不落标签,但置信度带回来 —— 「0.42 被阈值丢掉」和「什么都没看见」得分开")
    void aNoMatchLandsNothingButKeepsTheConfidence() {
        Touch touch = given("t-1", "growth-rate", RECALLING_SOURCE);
        Suggestion suggestion = serviceWith(new CountingTagger(RecognitionResult.noMatch(0.42)))
                .suggest(touch, MATERIAL, "image/jpeg");

        assertEquals(Outcome.NO_MATCH, suggestion.outcome());
        assertEquals(0.42, suggestion.confidence(), 1e-9, "降级不等于清零");
        assertNull(suggestion.tag());
        assertEquals(0, tags.count());
    }

    @Test
    @DisplayName("🔴 模型编了一个候选集外的 code → 出口自检拦掉,一条标签都不落(docs/data/识别链路选型.md 坑一)")
    void aHallucinatedCodeNeverBecomesATag() {
        // 「机构标准表述-增长率速算」听上去像个正经考点,这正是幻觉的危险之处。
        // 唯一的判据是它在不在这次送进去的候选集里 —— 不是它看起来合不合理。
        Touch touch = given("t-1", "growth-rate", RECALLING_SOURCE);
        Suggestion suggestion = serviceWith(new RogueTagger("机构标准表述-增长率速算"))
                .suggest(touch, MATERIAL, "image/jpeg");

        assertEquals(Outcome.NO_MATCH, suggestion.outcome());
        assertEquals(0.99, suggestion.confidence(), 1e-9,
                "置信度留着 —— 它是「召回没覆盖到」和「模型在乱答」唯一的区分线索");
        assertEquals(0, tags.count(), "库里出现了一条不是自己命名的标签(R-07)");
    }

    @Test
    @DisplayName("🔴 code 在候选集里但置信度不够 → 照样不挂(宁缺毋滥)")
    void aBelowThresholdAnswerLandsNothing() {
        Touch touch = given("t-1", "growth-rate", RECALLING_SOURCE);
        Suggestion suggestion = serviceWith(new CountingTagger(
                RecognitionResult.of("interval-growth", RecognitionResult.MIN_CONFIDENCE - 0.01)))
                .suggest(touch, MATERIAL, "image/jpeg");

        assertEquals(Outcome.NO_MATCH, suggestion.outcome());
        assertEquals(0, tags.count(), "差一丝也是不够 —— 不硬凑最接近的考点");
    }

    @Test
    @DisplayName("🔴 模型挂了 → 记录还在、标签一条没动、还能手动挂(docs/execution/INDEX.md §1.3.7.1)")
    void anUnavailableModelBreaksNothing() {
        Touch touch = given("t-1", "growth-rate", RECALLING_SOURCE);
        TaggingService service = serviceWith(new DeadTagger());

        Suggestion suggestion = service.suggest(touch, MATERIAL, "image/jpeg");
        assertEquals(Outcome.UNAVAILABLE, suggestion.outcome());
        assertNull(suggestion.tag());

        // 这三条才是这条用例真正要说的话:降级方向是「少功能」,不是「少记录」。
        assertEquals(1, touches.findAll().size(), "记录不能因为模型挂了而消失");
        assertEquals(1, service.tagsOf(touch).size(), "主标签还在 —— 覆盖度不掉");
        assertInstanceOf(MountResult.Mounted.class, service.mount(touch, "share-calc"),
                "手动挂载这条路永不受识别故障影响");
    }

    // ———————————————— 三、手动挂载 ————————————————

    @Test
    @DisplayName("🔴 树外的 code 一律拒绝,不会顺手建一个新考点(R-07)")
    void mountingRejectsCodesOutsideTheTree() {
        Touch touch = given("t-1", "growth-rate", SILENT_SOURCE);
        TaggingService service = serviceWith(new StubVisionTagger());

        for (String outside : new String[]{null, "  ", "我自己起的考点名", "资料分析·增长率"}) {
            assertInstanceOf(MountResult.NotInSyllabus.class, service.mount(touch, outside),
                    "[" + outside + "]");
        }
        assertEquals(0, tags.count());
    }

    @Test
    @DisplayName("手动挂载:落一条 origin=manual 且当场已确认的标签 —— 亲手挑的那一下就是确认本身")
    void mountingLandsAConfirmedManualTag() {
        Touch touch = given("t-1", "growth-rate", SILENT_SOURCE);
        MountResult.Mounted mounted = assertInstanceOf(MountResult.Mounted.class,
                serviceWith(new StubVisionTagger()).mount(touch, "share-calc"));

        assertTrue(mounted.created());
        assertEquals(TagOrigin.MANUAL, mounted.tag().origin());
        assertEquals(RecordTag.MANUAL_CONFIDENCE, mounted.tag().confidence(), 1e-9,
                "手动标签没有「有多确定」这回事");
        assertEquals(NOW, mounted.tag().confirmedAt());
        assertFalse(mounted.tag().primary(), "它是加挂的,不是采集时那条");
    }

    @Test
    @DisplayName("同一个考点挂第二次 → 返回原来那条,不新建(created=false)")
    void mountingTheSameNodeTwiceIsIdempotent() {
        Touch touch = given("t-1", "growth-rate", SILENT_SOURCE);
        TaggingService service = serviceWith(new StubVisionTagger());

        MountResult.Mounted first = assertInstanceOf(MountResult.Mounted.class,
                service.mount(touch, "share-calc"));
        MountResult.Mounted again = assertInstanceOf(MountResult.Mounted.class,
                service.mount(touch, "share-calc"));

        assertFalse(again.created(), "什么都没新建,调用方据此把 201 降成 200");
        assertEquals(first.tag().id(), again.tag().id());
        assertEquals(1, tags.count());
    }

    @Test
    @DisplayName("挂到记录本来那个考点上 → 命中的是推出来的主标签,不会凭空多一条")
    void mountingTheRecordsOwnNodeHitsThePrimaryTag() {
        // 主标签不在库里存着,所以这条最容易写错成「查库没有 → 新建一条」,
        // 结果同一个考点上出现两条标签,而覆盖度按记录去重之后看不出任何异常。
        Touch touch = given("t-1", "growth-rate", SILENT_SOURCE);
        MountResult.Mounted mounted = assertInstanceOf(MountResult.Mounted.class,
                serviceWith(new StubVisionTagger()).mount(touch, "growth-rate"));

        assertFalse(mounted.created());
        assertTrue(mounted.tag().primary());
        assertEquals(0, tags.count(), "一行都不该落");
    }

    // ———————————————— 四、确认与丢弃 ————————————————

    @Test
    @DisplayName("🔴 确认一条自动标签:只写 confirmed_at,origin 仍然是 auto(docs/technical/INDEX.md §6.3)")
    void confirmingDoesNotRewriteOrigin() {
        Touch touch = given("t-1", "growth-rate", RECALLING_SOURCE);
        TaggingService service = serviceWith(new CountingTagger(RecognitionResult.of("interval-growth", 0.91)));
        RecordTag suggested = service.suggest(touch, MATERIAL, "image/jpeg").tag();

        RecordTag confirmed = service.confirm(touch, suggested.id());

        assertEquals(TagOrigin.AUTO, confirmed.origin(),
                "确认把 auto 改成了 manual —— 准确率口径的分母会随每次确认缩水,指标恒等于 0");
        assertEquals(NOW, confirmed.confirmedAt());
        assertEquals(TagOrigin.AUTO, tags.find(suggested.id()).origin(), "落库的那一行也得是 auto");
        assertEquals(1, tags.count(), "确认是改一行,不是加一行");
    }

    @Test
    @DisplayName("确认主标签:库里落一行,而它仍然是 primary、仍然是 manual")
    void confirmingThePrimaryTagMaterialisesTheRow() {
        Touch touch = given("t-1", "growth-rate", SILENT_SOURCE);
        TaggingService service = serviceWith(new StubVisionTagger());
        String primaryId = RecordTag.primaryIdOf("t-1");

        RecordTag confirmed = service.confirm(touch, primaryId);

        assertTrue(confirmed.primary());
        assertEquals(TagOrigin.MANUAL, confirmed.origin());
        assertEquals(1, tags.count(), "主标签本来不占行,被确认之后才需要一行来记住这个状态");
    }

    @Test
    @DisplayName("🔴 丢弃 → 仍然查得到、看得见,只是不计覆盖度(P1-7)")
    void discardingKeepsTheTagVisible() {
        Touch touch = given("t-1", "growth-rate", SILENT_SOURCE);
        TaggingService service = serviceWith(new StubVisionTagger());

        RecordTag discarded = service.discard(touch, RecordTag.primaryIdOf("t-1"));

        assertTrue(discarded.discarded());
        assertFalse(discarded.countsInCoverage());
        assertEquals(1, service.tagsOf(touch).size(), "丢弃不是删除 —— 它还得在列表上待着");
        assertEquals(TagOrigin.MANUAL, discarded.origin(), "丢弃同样不许动 origin");
    }

    @Test
    @DisplayName("🔴 丢弃过的考点不会被下一次补标悄悄复活")
    void aDiscardedNodeIsNotResurrectedByTheNextSuggestion() {
        // 用户已经说过「不是这个」。一次自动识别没有资格推翻它 ——
        // 否则每补标一次就复活一次,而他不会知道自己丢过的东西又回到了覆盖度里。
        Touch touch = given("t-1", "growth-rate", RECALLING_SOURCE);
        TaggingService service = serviceWith(new CountingTagger(RecognitionResult.of("interval-growth", 0.91)));

        RecordTag suggested = service.suggest(touch, MATERIAL, "image/jpeg").tag();
        service.discard(touch, suggested.id());

        Suggestion again = service.suggest(touch, MATERIAL, "image/jpeg");

        assertEquals(Outcome.ALREADY_TAGGED, again.outcome());
        assertTrue(again.tag().discarded(), "指回的是那条丢弃过的,不是一条崭新的");
        assertEquals(1, tags.count(), "没有新增");
        assertFalse(service.tagsOf(touch).stream()
                        .anyMatch(t -> t.nodeCode().equals("interval-growth") && t.countsInCoverage()),
                "它不能重新计进覆盖度");
    }

    @Test
    @DisplayName("丢弃过之后用户又亲手挂了一次 → 给一条干净的新标签,而丢弃那条仍然留着")
    void aManualRemountAfterDiscardCreatesAFreshTag() {
        // 与上一条相对:自动识别没资格推翻用户,用户自己有。
        // 但翻的方式是「新挂一条」而不是「把丢弃那条翻过来」——
        // 「我曾经把它丢掉过」这件事得留着,否则同一个错标会被反复建议而他不知道自己丢过。
        Touch touch = given("t-1", "growth-rate", SILENT_SOURCE);
        TaggingService service = serviceWith(new StubVisionTagger());

        RecordTag first = assertInstanceOf(MountResult.Mounted.class,
                service.mount(touch, "share-calc")).tag();
        service.discard(touch, first.id());

        MountResult.Mounted remounted = assertInstanceOf(MountResult.Mounted.class,
                service.mount(touch, "share-calc"));

        assertTrue(remounted.created());
        assertNotEquals(first.id(), remounted.tag().id());
        assertTrue(tags.find(first.id()).discarded(), "丢弃那条一个字都没被改");
        assertEquals(2, tags.count());
    }

    @Test
    @DisplayName("🔴 拿一个不属于这条记录的 tagId 来确认/丢弃 → null,不会改到别人的标签")
    void aTagIdFromAnotherRecordIsNotFound() {
        // 今天是单用户所以看不出区别,而多用户是已经排好期的事(docs/technical/INDEX.md §7)。
        // 直接拿 id 查库的写法会让「拿着别人记录的 tagId 来确认」成功一次。
        Touch mine = given("t-1", "growth-rate", SILENT_SOURCE);
        Touch other = given("t-2", "share-calc", SILENT_SOURCE);
        TaggingService service = serviceWith(new StubVisionTagger());
        RecordTag othersTag = assertInstanceOf(MountResult.Mounted.class,
                service.mount(other, "average-calc")).tag();

        assertNull(service.confirm(mine, othersTag.id()));
        assertNull(service.discard(mine, othersTag.id()));
        assertNull(service.confirm(mine, "tag-不存在"));
        assertNull(service.confirm(mine, null));

        assertEquals(othersTag, tags.find(othersTag.id()), "别人那条一个字都没被动");
        assertFalse(tags.find(othersTag.id()).discarded());
    }

    @Test
    @DisplayName("级联删标签:删记录时把它名下的行一起收走(docs/technical/INDEX.md §6.2)")
    void deletingARecordTakesItsTagsAlong() {
        Touch touch = given("t-1", "growth-rate", SILENT_SOURCE);
        Touch other = given("t-2", "share-calc", SILENT_SOURCE);
        TaggingService service = serviceWith(new StubVisionTagger());
        service.mount(touch, "average-calc");
        service.mount(other, "average-calc");

        assertEquals(1, service.deleteTagsOf("t-1"));
        assertEquals(1, tags.count(), "别人的标签不该被顺手带走");
        assertEquals(0, service.deleteTagsOf("t-1"), "删一次不存在的返回 0,不抛");
    }

    @Test
    @DisplayName("按 id 找不到记录时返回 null —— 「这条记录不存在」是调用方要分辨的情况,不是故障")
    void findRecordReturnsNullForUnknownIds() {
        given("t-1", "growth-rate", SILENT_SOURCE);
        TaggingService service = serviceWith(new StubVisionTagger());

        assertEquals("t-1", service.findRecord("t-1").id());
        assertNull(service.findRecord("t-nope"));
        assertNull(service.findRecord(null));
        assertNull(service.findRecord("  "));
    }

    /** 最简行为层替身:这个文件只关心标签,记录怎么落地由 {@code CaptureServiceTest} 管。 */
    private static final class InMemoryTouchStore implements TouchStore {
        private final List<Touch> all = new ArrayList<>();

        void add(Touch t) {
            all.add(t);
        }

        @Override
        public List<Touch> findAll() {
            return List.copyOf(all);
        }

        @Override
        public List<Touch> findByNode(String nodeCode) {
            return all.stream().filter(t -> t.nodeCode().equals(nodeCode)).toList();
        }

        @Override
        public Touch findByClientToken(String clientToken) {
            return null;
        }

        @Override
        public Touch append(Touch touch) {
            all.add(touch);
            return touch;
        }

        @Override
        public Touch delete(String id) {
            return all.stream().filter(t -> t.id().equals(id)).findFirst()
                    .map(t -> {
                        all.remove(t);
                        return t;
                    }).orElse(null);
        }

        @Override
        public int count() {
            return all.size();
        }

        @Override
        public int reassign(String fromNodeCode, String toNodeCode) {
            throw new UnsupportedOperationException("打标不改挂记录");
        }
    }
}
