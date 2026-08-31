package com.kaodian.server.collect;

import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签这个形状本身的红线测试 —— <b>立场是「假设有人会去改 origin」。</b>
 *
 * <p>docs/技术架构 §5.2 那一行把话说死了:「{@code origin} 记的是这条标签<b>从哪来</b>,
 * 不是它现在什么状态 —— 用户确认只写 {@code confirmed_at},不把 {@code auto} 改成 {@code manual}。
 * 改了,{@code 1.2.5.2} 那套准确率口径(标对的/标了的)在真实数据上就再也算不出来了。」
 *
 * <h2>为什么这条值得单独一个文件</h2>
 *
 * 因为破它<b>看起来完全合理</b>:「用户都确认了,那不就是手动的吗」是每个人第一反应会想的一句话,
 * 而它写出来是一行代码、评审时读起来像是在修 bug。等到有人去算准确率的那天,
 * 分母里只剩下模型标错的那些(标对的都被确认成 manual 了),指标恒等于 0 ——
 * <b>而且历史数据里已经没有任何东西能把它还原回来</b>。
 * <p>
 * 所以下面钉的不是「代码写对了」,是「这条改动会在哪一步被拦下来」。
 */
class RecordTagTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    private static Touch touch(String id, String nodeCode) {
        return new Touch(id, nodeCode, "自己刷题", TouchKind.MANUAL, NOW, null);
    }

    private static RecordTag auto(String id, String recordId, String nodeCode, double confidence) {
        return new RecordTag(id, recordId, nodeCode, confidence, TagOrigin.AUTO, null, false);
    }

    // ———————————————————— 一、origin 写入后不可变 ————————————————————

    @Test
    @DisplayName("🔴 确认一条自动标签:只写 confirmed_at,origin 仍然是 auto")
    void confirmingAnAutoTagLeavesItsOriginAuto() {
        RecordTag suggested = auto("tag-1", "t-1", "growth-rate", 0.91);
        assertNull(suggested.confirmedAt(), "刚建出来时还没人确认过");

        RecordTag confirmed = suggested.confirm(NOW);

        assertEquals(TagOrigin.AUTO, confirmed.origin(),
                "确认把 auto 改成了 manual —— 准确率口径(标对的/标了的)的分母会随每次确认缩水");
        assertEquals(NOW, confirmed.confirmedAt(), "确认要写的只有这一个字段");
        assertEquals(0.91, confirmed.confidence(), 1e-9, "模型自报的分不因为被确认而变");
        assertEquals("growth-rate", confirmed.nodeCode());
        assertFalse(confirmed.discarded());
    }

    @Test
    @DisplayName("🔴 丢弃一条自动标签:origin 同样不变,confirmedAt 也不被抹掉")
    void discardingAnAutoTagLeavesItsOriginAndConfirmation() {
        // 「我确认过,后来又觉得不对」是一段真实经过。抹掉 confirmedAt 等于让这条标签
        // 装成从没被确认过,而那恰恰是评估「用户确认得准不准」时最要紧的一批样本。
        RecordTag confirmed = auto("tag-1", "t-1", "growth-rate", 0.91).confirm(NOW);
        RecordTag discarded = confirmed.discard();

        assertEquals(TagOrigin.AUTO, discarded.origin());
        assertEquals(NOW, discarded.confirmedAt(), "丢弃不该把「曾经确认过」一起抹掉");
        assertTrue(discarded.discarded());
    }

    @Test
    @DisplayName("🔴 confirm / discard 的签名里根本没有能传进一个新 origin 的位置")
    void theOnlyTwoMutatorsCannotTakeAnOrigin() throws Exception {
        // 上面两条验的是「这次调用没改」。这一条验的是「压根改不了」——
        // 前者会被一次实现改动推翻,后者要推翻得先改签名,而改签名是一次显式的决定。
        for (var method : List.of(
                RecordTag.class.getMethod("confirm", Instant.class),
                RecordTag.class.getMethod("discard"))) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(TagOrigin.class.isAssignableFrom(parameter),
                        method.getName() + " 收下了一个 origin —— 那就等于开了改它的口子");
            }
        }

        // 反面对照:这个 record 上没有任何 withXxx / setXxx 之类的通用改写口。
        // 没有它,上面那两条断言可以靠「大家都很自觉」蒙混过关。
        for (var method : RecordTag.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase();
            assertFalse(name.startsWith("set") || name.startsWith("with"),
                    "出现了一个通用改写口,origin 迟早从这儿被改掉:" + method.getName());
        }
    }

    @Test
    @DisplayName("🔴 存储层再核一遍:同一个 id 换了 origin 就抛 —— 挡的是自己 new 一个再写进来的写法")
    void theStoreRefusesToRewriteOrigin() {
        // 前两道锁(没有 setter、confirm/discard 的签名)只挡得住顺着现有 API 走的人。
        // 绕过它们只要一行:new RecordTag(同一个 id, ..., MANUAL, ...) 再 put。这一道挡的就是它。
        RecordTagStore store = new InMemoryRecordTagStore();
        store.put(auto("tag-1", "t-1", "growth-rate", 0.91));

        RecordTag flipped = new RecordTag("tag-1", "t-1", "growth-rate",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> store.put(flipped));
        assertTrue(e.getMessage().contains("origin"), () -> "报错得说清是哪条线:" + e.getMessage());

        assertEquals(TagOrigin.AUTO, store.find("tag-1").origin(), "库里那条一个字都不许变");
    }

    @Test
    @DisplayName("存储层同时挡住换宿主记录与原地改挂考点 —— 两者都会让覆盖度悄悄算错")
    void theStoreRefusesToRewriteTheRecordOrTheNode() {
        RecordTagStore store = new InMemoryRecordTagStore();
        store.put(auto("tag-1", "t-1", "growth-rate", 0.91));

        assertThrows(IllegalArgumentException.class,
                () -> store.put(auto("tag-1", "t-2", "growth-rate", 0.91)),
                "换了宿主记录 —— 覆盖度按记录去重,一条记录会数进两个考点");
        assertThrows(IllegalArgumentException.class,
                () -> store.put(auto("tag-1", "t-1", "share-calc", 0.91)),
                "原地改挂 —— 「我曾经把它标成 growth-rate」这件事会凭空消失");
    }

    // ———————————————— 二、手动标签不许带着一个模型分进来 ————————————————

    @Test
    @DisplayName("🔴 origin=manual 却带着 0.83 分:构造不出来 —— 那只可能是识别结果换了个 origin")
    void aManualTagCannotCarryAModelConfidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new RecordTag("tag-1", "t-1", "growth-rate", 0.83, TagOrigin.MANUAL, NOW, false));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordTag("tag-1", "t-1", "growth-rate", 0.0, TagOrigin.MANUAL, NOW, false),
                "0 也不行 —— 手动标签没有「有多确定」这回事,不是「不太确定」");

        // 对照组:1.0 能过。没有它,上面两条可以靠「manual 一律构造不出来」蒙混过关。
        assertEquals(TagOrigin.MANUAL, new RecordTag("tag-1", "t-1", "growth-rate",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false).origin());
    }

    @Test
    @DisplayName("🔴 NaN 置信度被构造器拒收 —— 它不是「低于线」,是「根本没在线上」(R-72)")
    void nanConfidenceIsRejectedOutright() {
        // 与 RecognitionResult 的构造器同一条,理由也同一条:NaN 跟任何数比较都为 false,
        // 范围校验放它过、阈值裁决也放它过。识别结果错了下次重来,而【落进库里的那一行】
        // 会一直被数进覆盖度 —— 所以这一层必须自己再挡一次,不能指望上游已经挡过。
        assertThrows(IllegalArgumentException.class,
                () -> auto("tag-1", "t-1", "growth-rate", Double.NaN));
        for (double bad : new double[]{-0.01, 1.01, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> auto("tag-1", "t-1", "growth-rate", bad), String.valueOf(bad));
        }
    }

    @Test
    @DisplayName("id / recordId / nodeCode 空或超长一律拒收 —— 它们是 id,不是放内容的地方(R-01)")
    void idFieldsAreBoundedAndNonBlank() {
        String tooLong = "x".repeat(RecordTag.MAX_ID_LENGTH + 1);
        for (String bad : new String[]{null, "", "  ", tooLong}) {
            assertThrows(IllegalArgumentException.class,
                    () -> auto(bad, "t-1", "growth-rate", 0.9), "id:[" + bad + "]");
            assertThrows(IllegalArgumentException.class,
                    () -> auto("tag-1", bad, "growth-rate", 0.9), "recordId:[" + bad + "]");
            assertThrows(IllegalArgumentException.class,
                    () -> auto("tag-1", "t-1", bad, 0.9), "nodeCode:[" + bad + "]");
        }
        assertNotNull(auto("tag-1", "t-1", "x".repeat(RecordTag.MAX_ID_LENGTH), 0.9),
                "刚好到上限要能过,否则拦的不是长度是别的");
    }

    // ———————————————— 三、形状:没有任何位置能装下一个自己起的标签名 ————————————————

    @Test
    @DisplayName("🔴 RecordTag 上只有三个文本分量,而且全是 id 类、全都有上限(R-07 / R-01)")
    void theTagRecordHasNoFreeTextOutlet() {
        // 断言的是【形状】,不是某次赋值。下一个人加一个 String label「给用户看个提示」完全说得通,
        // 而那一刻 R-07 就破了:模型生成的措辞、机构的既有措辞,都有了一条合法通路进库。
        List<String> textComponents = new ArrayList<>();
        for (RecordComponent component : RecordTag.class.getRecordComponents()) {
            if (CharSequence.class.isAssignableFrom(component.getType())) {
                textComponents.add(component.getName());
            }
        }
        assertEquals(List.of("id", "recordId", "nodeCode"), textComponents,
                "标签只认考点树里的 code,不认名字 —— 多出来的那个文本分量装得下什么?");

        for (RecordComponent component : RecordTag.class.getRecordComponents()) {
            if (component.getType() != String.class) {
                continue;
            }
            Size size = sizeOf(component);
            assertNotNull(size, component.getName() + " 没有上限");
            assertTrue(size.max() <= RecordTag.MAX_ID_LENGTH,
                    component.getName() + " 的上限是 " + size.max() + ",够放一段材料了");
        }
    }

    /**
     * record 分量上的 {@code @Size} 会按注解自身的 {@code @Target} 落到分量、后备字段、访问器
     * 里的某几处 —— 三处都看一遍,别因为落点不同就当成「没写」。
     *
     * <p>做法与 {@code NoStemFieldTest.sizeOf} 一致。写第二遍不是重复:那边扫的是整包的形状,
     * 这边钉的是这一个 record 的上限口径,两条断言的失效方式不一样。
     */
    private static Size sizeOf(RecordComponent component) {
        Size onComponent = component.getAnnotation(Size.class);
        if (onComponent != null) {
            return onComponent;
        }
        Size onAccessor = component.getAccessor().getAnnotation(Size.class);
        if (onAccessor != null) {
            return onAccessor;
        }
        try {
            return RecordTag.class.getDeclaredField(component.getName()).getAnnotation(Size.class);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    @Test
    @DisplayName("TagOrigin 只有两个取值,而且没有可以被拿去装别的东西的字段")
    void tagOriginHasExactlyTwoValuesAndNoState() {
        assertEquals(List.of("AUTO", "MANUAL"),
                java.util.Arrays.stream(TagOrigin.values()).map(Enum::name).toList());
        assertEquals("auto", TagOrigin.AUTO.wireName(), "契约里是小写(docs/技术架构 §5.2)");
        assertEquals("manual", TagOrigin.MANUAL.wireName());
        assertEquals(TagOrigin.AUTO, TagOrigin.ofWireName("auto"));
        assertThrows(IllegalArgumentException.class, () -> TagOrigin.ofWireName("Auto"),
                "一个存坏了的 origin 不该被悄悄当成别的");

        for (var field : TagOrigin.class.getDeclaredFields()) {
            assertTrue(field.isEnumConstant() || field.isSynthetic(),
                    "TagOrigin 上出现了一个字段,它迟早会被拿去装给用户看的字:" + field.getName());
        }
    }

    // ———————————————— 四、主标签是推出来的,不是存出来的 ————————————————

    @Test
    @DisplayName("🔴 库里一行都没有的记录,照样带着一条已确认的主标签 —— 覆盖度的失败方向是「多算」")
    void aRecordWithNoStoredRowStillCarriesItsPrimaryTag() {
        // 这一条是整个设计的地基。反过来写(采集时写一行)有一个不报错的失败模式:
        // 任何没配上标签行的记录会静默地从覆盖度里消失 —— 种子数据、历史数据、
        // 以及任何一条绕过采集服务直接落库的记录。用户看到覆盖率无缘无故掉了几格,
        // 没有一行日志、没有一次报错。
        Touch t = touch("t-1", "growth-rate");
        List<RecordTag> tags = RecordTag.effectiveTagsOf(t, List.of());

        assertEquals(1, tags.size());
        RecordTag primary = tags.get(0);
        assertTrue(primary.primary());
        assertEquals("primary-t-1", primary.id(), "主标签的 id 由记录 id 推出,不签发");
        assertEquals("growth-rate", primary.nodeCode());
        assertEquals(TagOrigin.MANUAL, primary.origin());
        assertEquals(NOW, primary.confirmedAt(), "采集时亲手挑的,那一下就是确认本身");
        assertTrue(primary.countsInCoverage());
    }

    @Test
    @DisplayName("库里存了主标签那一行时,状态用库里的 —— 否则「丢弃主标签」根本存不下来")
    void aStoredPrimaryRowOverridesTheDerivedState() {
        Touch t = touch("t-1", "growth-rate");
        RecordTag stored = RecordTag.primaryOf(t).discard();

        List<RecordTag> tags = RecordTag.effectiveTagsOf(t, List.of(stored));

        assertEquals(1, tags.size());
        assertTrue(tags.get(0).discarded(), "库里说丢了,推出来的那条不能把它盖回去");
        assertFalse(tags.get(0).countsInCoverage());
    }

    @Test
    @DisplayName("🔴 主标签的落点永远跟着记录走 —— 记录改挂之后,那一行不会还指着旧考点")
    void aStoredPrimaryRowNeverPinsTheOldNode() {
        // 触发路径不是空想:删考点之前必须先把记录搬走(TouchStore#reassign),
        // 搬完记录挂在新考点上,而库里那行主标签还写着旧 code。
        // 若拿库里的 nodeCode 算,覆盖度会算到一个用户已经搬离的格子里,而且旧考点马上就要被删。
        Touch afterReassign = touch("t-1", "share-calc");
        RecordTag storedBefore = new RecordTag("primary-t-1", "t-1", "growth-rate",
                RecordTag.MANUAL_CONFIDENCE, TagOrigin.MANUAL, NOW, false);

        List<RecordTag> tags = RecordTag.effectiveTagsOf(afterReassign, List.of(storedBefore));

        assertEquals("share-calc", tags.get(0).nodeCode(), "落点取自记录,不取自那一行");
        assertEquals(TagOrigin.MANUAL, tags.get(0).origin(), "而 origin 仍然取自那一行 —— 它是来源");
    }

    @Test
    @DisplayName("主标签排在最前,其余按写入顺序跟在后面 —— 顺序是接口上看得见的东西")
    void thePrimaryTagComesFirst() {
        Touch t = touch("t-1", "growth-rate");
        RecordTag extra1 = auto("tag-a", "t-1", "share-calc", 0.88);
        RecordTag extra2 = auto("tag-b", "t-1", "average-calc", 0.80);

        List<RecordTag> tags = RecordTag.effectiveTagsOf(t, List.of(extra1, RecordTag.primaryOf(t), extra2));

        assertEquals(List.of("primary-t-1", "tag-a", "tag-b"), tags.stream().map(RecordTag::id).toList());
    }

    @Test
    @DisplayName("整库摊平时按记录顺序 —— 来源名集合是按首次出现顺序出接口的,顺序丢了不会有人红")
    void flatteningKeepsTheRecordOrder() {
        List<Touch> touches = List.of(touch("t-1", "growth-rate"), touch("t-2", "share-calc"));
        List<RecordTag> stored = List.of(auto("tag-b", "t-2", "average-calc", 0.9));

        assertEquals(List.of("primary-t-1", "primary-t-2", "tag-b"),
                RecordTag.effectiveTagsOf(touches, stored).stream().map(RecordTag::id).toList());
    }

    // ———————————————— 五、计不计覆盖度,判据只有一个 ————————————————

    @Test
    @DisplayName("🔴 判据只有 discarded,没有「确认过没有」(docs/技术架构 §6.4)")
    void onlyDiscardDecidesWhetherATagCounts() {
        // 把「没点确认」也算成不覆盖,等于要求用户对每一条自动标签点一次才承认他学过 ——
        // 覆盖率会变成点击率,而北极星指标看的正是这一屏。
        assertTrue(auto("tag-1", "t-1", "growth-rate", 0.91).countsInCoverage(),
                "过了阈值、过了出口自检的自动标签,哪怕还没人确认,也已经是一次分类");
        assertTrue(auto("tag-1", "t-1", "growth-rate", 0.91).confirm(NOW).countsInCoverage(),
                "确认不会让它掉出覆盖度 —— 这才是契约那句「计入覆盖度」的意思");
        assertFalse(auto("tag-1", "t-1", "growth-rate", 0.91).discard().countsInCoverage(),
                "丢弃的可见,但不计覆盖度(P1-7)");
        assertFalse(auto("tag-1", "t-1", "growth-rate", 0.91).confirm(NOW).discard().countsInCoverage(),
                "确认过又丢弃,以丢弃为准");
    }

    @Test
    @DisplayName("签发的标签 id 不会撞上主标签的前缀 —— 撞上就等于凭空多出一条主标签")
    void issuedTagIdsNeverCollideWithThePrimaryPrefix() {
        assertEquals("primary-t-1", RecordTag.primaryIdOf("t-1"));
        assertFalse(auto("tag-1", "t-1", "growth-rate", 0.9).primary());
        assertTrue(RecordTag.primaryOf(touch("t-1", "growth-rate")).primary());

        // 一条推出来的主标签必须与「按同样字段自己 new 一条」完全相等 —— 否则两处推的不是同一条
        assertEquals(RecordTag.primaryOf(touch("t-1", "growth-rate")),
                RecordTag.effectiveTagsOf(touch("t-1", "growth-rate"), null).get(0));
    }

    @Test
    @DisplayName("stored 传 null 与传空表现一致 —— 「还没查过库」不该和「库里没有」走出两种结果")
    void nullStoredListBehavesLikeAnEmptyOne() {
        Touch t = touch("t-1", "growth-rate");
        assertEquals(RecordTag.effectiveTagsOf(t, List.of()), RecordTag.effectiveTagsOf(t, null));
    }

    @Test
    @DisplayName("confirm(null) 直接拒绝 —— 一个没有时刻的确认等于把这条标签的唯一状态抹掉")
    void confirmRequiresAnInstant() {
        RecordTag tag = auto("tag-1", "t-1", "growth-rate", 0.91);
        assertThrows(IllegalArgumentException.class, () -> tag.confirm(null));
        assertSame(TagOrigin.AUTO, tag.origin());
    }
}
