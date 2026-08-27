package com.kaodian.server.recognize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闭集打标的对抗性测试 —— <b>立场是「假设模型会乱答」,不是「验证代码写对了」。</b>
 *
 * <p>{@link VisionTagger} 与 {@link RecognitionResult} 把 R-07(不沿用机构既有考点体系与措辞)
 * 和「宁缺毋滥」钉在了类型与构造器上。钉在类型上是好事,但类型只保证「构造不出来」,
 * 不保证「拦得住」—— 拦截发生在 {@link VisionTagger#enforceClosedSet} 那一句 {@code equals} 里,
 * 而那一句从来没被任何测试跑过。这个文件补的是这一段。
 *
 * <h2>为什么每条用例都从「模型这样答了」开始写</h2>
 *
 * docs/09 坑一:<b>「OCR 出错是漏字,LLM 出错是编造一个不存在的考点」</b>。
 * 编造出来的东西长什么样是可以预演的 —— 树里没有的 code、机构宣传册上的措辞、
 * 候选的中文名而不是 code、大小写和空白抖动过的 code。这些都不是假想:
 * 它们是让一个自由生成的模型去填一个闭集槽位时最常见的四种失手方式。
 * 所以下面的输入全部按「模型真会这么答」构造,而不是按「代码有哪些分支」构造。
 *
 * <h2>失守的代价不对称,所以测试的立场也不对称</h2>
 *
 * 漏判(该命中的没命中)= 用户自己从树里挑一个,代价是一次点击;
 * 误判(不该命中的命中了)= 覆盖度里多出一个假的「学过」,而<b>覆盖度就是整个产品</b>。
 * 因此下面凡是边界模糊的地方,一律钉「严」的那一侧,并在
 * {@link #caseAndWhitespaceVariantsAreOutsideTheSet} 上单独说明这个取舍。
 */
class ClosedSetTaggingTest {

    /** 送进 prompt 的候选集。取自 {@code seed/syllabus-ziliao.json},命名为自行归纳(R-07)。 */
    private static final List<VisionTagger.Candidate> CANDIDATES = List.of(
            new VisionTagger.Candidate("growth-rate", "增长率计算"),
            new VisionTagger.Candidate("growth-amount", "增长量计算"),
            new VisionTagger.Candidate("base-value", "基期量计算"),
            new VisionTagger.Candidate("interval-growth", "间隔增长率"));

    private static final byte[] IMAGE = "不会被读到的一张图".getBytes();

    /**
     * 说什么就答什么的假模型。
     *
     * <p>用它而不是直接调 {@code enforceClosedSet},是为了让每条用例都走
     * {@link com.kaodian.server.collect.CaptureService} 里那个真实形状:
     * <b>classify 的结果必须先过一遍出口自检,才允许被任何人使用</b>。
     * 哪天有人在调用链上把 {@code enforceClosedSet} 摘掉,这里的写法能让人一眼看出摘了什么。
     */
    private record ScriptedTagger(RecognitionResult answer) implements VisionTagger {
        @Override
        public RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates) {
            return answer;
        }
    }

    /** 模型答了 {@code answer},结果按真实调用链过一遍出口自检。 */
    private static RecognitionResult afterModelSays(RecognitionResult answer,
                                                    List<VisionTagger.Candidate> candidates) {
        VisionTagger tagger = new ScriptedTagger(answer);
        return VisionTagger.enforceClosedSet(tagger.classify(IMAGE, "image/jpeg", candidates), candidates);
    }

    private static RecognitionResult afterModelSays(RecognitionResult answer) {
        return afterModelSays(answer, CANDIDATES);
    }

    // ———————————————————— 一、模型答了候选集之外的东西 ————————————————————

    @Test
    @DisplayName("🔴 模型编了一个树里不存在的 code —— 出口处降级为 NO_MATCH,而不是照单全收")
    void aHallucinatedCodeNeverReachesTheCoverageMetric() {
        // 「weighted-average」听上去像个正经考点,这正是幻觉的危险之处:它不会长得很离谱。
        // 唯一的判据是它在不在这次送进去的候选集里 —— 不是它看起来合不合理。
        RecognitionResult result = afterModelSays(RecognitionResult.of("weighted-average", 0.97));

        assertFalse(result.matched(), "候选集里没有 weighted-average,它不该挂上任何记录");
        assertNull(result.nodeCode());
        assertFalse(result.aboveThreshold());
    }

    @Test
    @DisplayName("降级时置信度必须留着 —— 「召回没覆盖到」和「模型在乱答」得能分开")
    void confidenceSurvivesTheDowngradeSoTheTwoFailureModesStayDistinguishable() {
        // 这两个结果在界面上都是「没认出来,请自己挑一个」,但对产品的含义完全相反:
        // 高置信度的降级说明考点树的候选召回漏了东西(该扩树);
        // 0.0 说明图糊了或根本不是题(该改拍照引导)。压成同一个数,这条排查线索就没了。
        RecognitionResult recallMiss = afterModelSays(RecognitionResult.of("weighted-average", 0.93));
        RecognitionResult nothingSeen = RecognitionResult.noMatch();

        assertFalse(recallMiss.matched());
        assertFalse(nothingSeen.matched());
        assertEquals(0.93, recallMiss.confidence(), "降级不等于清零");
        assertNotEquals(nothingSeen.confidence(), recallMiss.confidence(),
                "两种失败被压成同一个 0.0,事后就分不清是树的问题还是模型的问题");
    }

    @Test
    @DisplayName("🔴 R-07:模型把机构的标准表述当 code 吐回来 —— 一个都进不来")
    void institutionalWordingUsedAsACodeIsRejected() {
        // 模型的预训练语料里全是机构的措辞,它在没把握时最容易回落到这些说法上。
        // 一旦这类字符串被当成 code 收下,机构的体系与措辞就从识别这个口子渗进了我们的库 ——
        // 而 R-07 要求的是「自行命名 + 留推导过程」,渗进来的东西没有推导过程。
        List<String> institutionalWording = List.of(
                "资料分析·增长率",
                "行测 > 资料分析 > 增长率速算",
                "【某机构】增长率的三种秒杀技巧",
                "速算技巧之增长率",
                "资料分析第二章 增长率");

        for (String wording : institutionalWording) {
            RecognitionResult result = afterModelSays(RecognitionResult.of(wording, 0.99));
            assertFalse(result.matched(), "机构措辞被当成 code 收下了:" + wording);
            assertNull(result.nodeCode(), "库里出现了一条不是自己命名的标签:" + wording);
        }
    }

    @Test
    @DisplayName("🔴 模型回的是候选的中文名而不是 code —— 也在集外,照样挡")
    void answeringWithTheCandidateNameInsteadOfItsCodeIsAlsoOutsideTheSet() {
        // 这条最容易被认为「其实答对了,兼容一下呗」。不能兼容:
        // 一旦允许按 name 匹配,匹配对象就从「一组 ID」变成了「一组人话」,
        // 模型下一步就会答「增长率计算题」「增长率(计算)」,然后是模糊匹配,然后闭集就没了。
        // 闭集之所以成立,是因为可选项是一组不可编辑的 ID。
        for (VisionTagger.Candidate candidate : CANDIDATES) {
            RecognitionResult result = afterModelSays(RecognitionResult.of(candidate.name(), 0.99));
            assertFalse(result.matched(), "按考点名匹配上了,闭集就退化成模糊匹配了:" + candidate.name());
        }
    }

    // ———————————————— 二、相等性判定有多严:实测钉住,不擅自改 ————————————————

    @Test
    @DisplayName("大小写 / 空白 / 全角变体一律算集外 —— 当前实现是精确 equals,这里如实钉住")
    void caseAndWhitespaceVariantsAreOutsideTheSet() {
        // enforceClosedSet 用的是 c.code().equals(result.nodeCode()),没有 trim、没有忽略大小写、
        // 没有全半角归一。下面这些「只差一点点」的答案,现在全部落到 NO_MATCH。
        //
        // 这个取舍是对的,理由是代价不对称:
        //   放宽 → 需要一套归一化规则,而归一化规则一旦存在就会被继续放宽(先 trim,再忽略大小写,
        //          再去标点),最后「在不在集里」变成一个有弹性的判断 —— 闭集的价值正好在于它没有弹性。
        //   收严 → 用户多点一次「自己从树里挑」。
        // 但它有代价,而且代价是隐形的:一个只多了个空格的正确答案会被静默丢掉,
        // 表现为识别率偏低而不是报错。真接上模型后,应当在 1.2.5.2 的评测里单独统计
        // 「只差空白/大小写」的比例;如果它不小,该修的是 prompt 与解析,不是这里的 equals。
        List<String> nearMisses = List.of(
                "GROWTH-RATE",          // 模型习惯把 ID 大写
                "Growth-Rate",          // 驼峰化
                " growth-rate",         // 解析时前导空白没剥干净
                "growth-rate ",         // 尾随空白
                "\tgrowth-rate\n",      // 从代码块里抠出来的
                "ｇｒｏｗｔｈ－ｒａｔｅ",   // 全角
                "growth_rate",          // 下划线
                "growth rate");         // 连字符变空格

        for (String nearMiss : nearMisses) {
            RecognitionResult result = afterModelSays(RecognitionResult.of(nearMiss, 0.99));
            assertFalse(result.matched(),
                    "当前实现是精确 equals,这个变体本应落到 NO_MATCH:[" + nearMiss + "]");
            assertEquals(0.99, result.confidence(),
                    "变体被丢掉时置信度要留着 —— 它正是「解析有毛病」这类问题的唯一线索:[" + nearMiss + "]");
        }

        // 与之对照:一字不差的那个,现在能过。这行是上面八条的对照组,
        // 没有它,上面的断言可以靠「什么都不匹配」蒙混过关。
        assertTrue(afterModelSays(RecognitionResult.of("growth-rate", 0.99)).matched(),
                "精确相等的 code 必须能过,否则闭集不是严,是坏了");
    }

    // ———————————————— 三、候选集本身缺失时的行为 ————————————————

    @Test
    @DisplayName("candidates 为 null:出口处一律 NO_MATCH —— 没有集,就没有「在集里」")
    void aNullCandidateListMatchesNothing() {
        // 这是防「调用方偷懒」而不是防模型:某天有人为了让链路跑通,传了个 null 进来。
        // 此时正确的行为是全部拒收 —— 若反过来「没有候选就不检查」,闭集就有了一个后门,
        // 而且是最容易被顺手打开的那一个。
        RecognitionResult result = afterModelSays(RecognitionResult.of("growth-rate", 0.99), null);

        assertFalse(result.matched(), "没有候选集时不允许放行任何 code");
        assertEquals(0.99, result.confidence(), "置信度照样留着");
    }

    @Test
    @DisplayName("candidates 为空列表:同样一律 NO_MATCH")
    void anEmptyCandidateListMatchesNothing() {
        RecognitionResult result = afterModelSays(RecognitionResult.of("growth-rate", 0.99), List.of());

        assertFalse(result.matched());
        assertEquals(0.99, result.confidence());
    }

    @Test
    @DisplayName("模型本来就答了「不匹配」:原样返回,不因为候选集缺失而炸")
    void anAlreadyNegativeAnswerPassesThroughUntouched() {
        // enforceClosedSet 对 NO_MATCH 是短路返回的,所以它在 candidates 为 null 时也不会 NPE。
        // 钉住这一点,是因为「识别没认出来」绝不该升级成一个异常 ——
        // docs/08 §1.3.7.1:识别怎么失败,用户的记录动作都不能失败。
        RecognitionResult declined = RecognitionResult.noMatch(0.42);

        assertSame(declined, VisionTagger.enforceClosedSet(declined, null));
        assertSame(declined, VisionTagger.enforceClosedSet(declined, List.of()));
        assertSame(declined, VisionTagger.enforceClosedSet(declined, CANDIDATES));
    }

    @Test
    @DisplayName("入口那道:不给候选集根本调不动 —— 闭集不是可选项")
    void theStubRefusesToClassifyWithoutACandidateSet() {
        // 出口自检是第二道。第一道在实现类的入口:没有候选就没有「集」可闭,
        // 这种调用是写错了,应当当场炸掉,而不是悄悄返回一个 NO_MATCH 让人以为模型没认出来。
        VisionTagger stub = new StubVisionTagger();

        assertThrows(IllegalArgumentException.class,
                () -> stub.classify(IMAGE, "image/jpeg", null));
        assertThrows(IllegalArgumentException.class,
                () -> stub.classify(IMAGE, "image/jpeg", List.of()));
    }

    // ———————————————————— 四、阈值:宁缺毋滥的那条线 ————————————————————

    @Test
    @DisplayName("恰好压在阈值上:算过 —— 线是闭区间,不留一条谁也说不清的缝")
    void exactlyAtTheThresholdCounts() {
        RecognitionResult result = RecognitionResult.of("growth-rate", RecognitionResult.MIN_CONFIDENCE);

        assertTrue(result.matched());
        assertTrue(result.aboveThreshold());
        assertEquals("growth-rate", result.nodeCode());
    }

    @Test
    @DisplayName("🔴 差一丝没到阈值:code 必须在 of() 里就被丢掉,不许带出来")
    void justBelowTheThresholdTheCodeIsDropped() {
        // 用 nextDown 而不是写死 0.74,是因为阈值本身是待标定的占位数(1.2.5.2)。
        // 这条用例钉的是「有一条线,低于线一律丢」这个形状,不是 0.75 这个值。
        double justBelow = Math.nextDown(RecognitionResult.MIN_CONFIDENCE);
        RecognitionResult result = RecognitionResult.of("growth-rate", justBelow);

        assertFalse(result.matched(), "差一丝也是不够 —— 不硬凑最接近的考点");
        assertNull(result.nodeCode(), "code 泄出来了,下游就可能把它挂上去");
        assertFalse(result.aboveThreshold());
        assertEquals(justBelow, result.confidence(), "丢的是 code,不是置信度");
    }

    @Test
    @DisplayName("低置信度的答案连出口自检都到不了 —— 阈值在 of() 里,不在每个实现类里")
    void aLowConfidenceAnswerIsAlreadyNoMatchBeforeTheClosedSetCheck() {
        // 换厂商换的是实现类,换不掉这条线(docs/09 坑三)。哪怕 code 是候选集里货真价实的一个,
        // 0.4 分也不许挂上去 —— 否则覆盖度里会多出一批「模型自己都不确定」的学过。
        RecognitionResult result = afterModelSays(RecognitionResult.of("growth-rate", 0.40));

        assertFalse(result.matched(), "code 在集里,但置信度不够,照样不挂");
        assertEquals(0.40, result.confidence());
    }

    @Test
    @DisplayName("of() 收到空 code:降级但保留置信度,不是抛异常")
    void ofDegradesGracefullyOnAnEmptyCode() {
        // 模型返回空串/空白是很常见的一种「我不知道」。这不是故障,是识别失败,
        // 不该把用户的记录动作一起带走。
        for (String empty : new String[]{null, "", "   ", "\n"}) {
            RecognitionResult result = RecognitionResult.of(empty, 0.99);
            assertFalse(result.matched(), "空 code 不该被当成命中:[" + empty + "]");
            assertEquals(0.99, result.confidence());
        }
    }

    @Test
    @DisplayName("阈值不许被悄悄调成 0 —— 那等于把宁缺毋滥关掉,而且没人会发现")
    void theThresholdIsNotDegenerate() {
        // 这是一条绊线,不是一条断言正确性的用例。改阈值本身是允许的,
        // 但它意味着覆盖度的口径变了,得走 1.2.5.2 的评测集标定 ——
        // 而不是因为「识别老是不出结果」顺手往下调。改了这个数,这条用例会红,
        // 红的目的就是逼一次显式确认。
        assertTrue(RecognitionResult.MIN_CONFIDENCE > 0.0,
                "阈值为 0 = 任何猜测都算命中 = 覆盖度失真");
        assertTrue(RecognitionResult.MIN_CONFIDENCE <= 1.0);
        assertEquals(0.75, RecognitionResult.MIN_CONFIDENCE,
                "阈值当前是 0.75(占位值)。要改它,请先做 1.2.5.2 的评测集标定,再连同这条用例一起改");
    }

    // ———————————————————— 五、非法组合根本构造不出来 ————————————————————

    @Test
    @DisplayName("🔴 「低置信度还带着 code」这种对象,写不出来")
    void aLowConfidenceResultCannotCarryACode() {
        // 出口自检可以被绕过(有人不调它),阈值裁决也可以被绕过(有人不走 of())。
        // 构造器是最后一道,它谁也绕不过 —— 只要还想造出一个 RecognitionResult。
        assertThrows(IllegalArgumentException.class,
                () -> new RecognitionResult("growth-rate", 0.10, true));
        assertThrows(IllegalArgumentException.class,
                () -> new RecognitionResult("growth-rate", Math.nextDown(RecognitionResult.MIN_CONFIDENCE), true));
    }

    @Test
    @DisplayName("🔴 「有 code 但 aboveThreshold=false」这种自相矛盾的对象,也写不出来")
    void theCodeAndTheThresholdFlagCannotDisagree() {
        // 不变式是「有 code ⇔ 过了阈值」。两个方向都得堵:
        // 只堵一边,下游那些 if (aboveThreshold) 和 if (nodeCode != null) 就会读出两种结论。
        assertThrows(IllegalArgumentException.class,
                () -> new RecognitionResult("growth-rate", 0.99, false), "有 code 却说没过阈值");
        assertThrows(IllegalArgumentException.class,
                () -> new RecognitionResult(null, 0.99, true), "没 code 却说过了阈值");
    }

    @Test
    @DisplayName("空串 code 不是「没有 code」—— 不允许存在第三种状态")
    void aBlankCodeIsNotAValidThirdState() {
        // 要么是树里的一个 code,要么是 null,没有第三种。空串会一路装成「有 code」往下走,
        // 直到某个 join 悄悄查不到东西为止。
        for (String blank : new String[]{"", " ", "\t", "\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RecognitionResult(blank, 0.99, true), "[" + blank + "]");
        }
    }

    @Test
    @DisplayName("置信度越界直接拒收 —— 它只用来过阈值,越界说明解析已经错了")
    void confidenceOutsideZeroToOneIsRejected() {
        for (double bad : new double[]{-0.01, 1.01, 100.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RecognitionResult(null, bad, false), String.valueOf(bad));
        }
    }

    @Test
    @DisplayName("⚠️ NaN 置信度当前能穿过阈值并成为一次命中 —— 钉住的是现状,不是应有行为")
    void nanConfidenceCurrentlySlipsThroughTheThreshold() {
        // 这条用例是唯一一条「钉住现状而不是钉住红线」的:实测发现的口子,主代码不由测试来改。
        //
        // 口子在哪:范围校验写的是 confidence < 0.0 || confidence > 1.0,阈值裁决写的是
        // confidence < MIN_CONFIDENCE。NaN 与任何数比较都是 false,于是它两道都过 ——
        // 一个「模型没给出置信度、解析时算成了 NaN」的答案,会变成一次高置信度命中,
        // 而 NaN 恰恰是最该被当成「不知道」的那种值。这是宁缺毋滥的一个反向缺口:
        // 不是低于线被放行,是根本没在线上却被当成过了线。
        //
        // 触发条件不是空想:Double.parseDouble("NaN")、0.0/0.0 的归一化、
        // 缺字段时的默认计算,任何一处都能产出它,而且不会报错。
        //
        // 需要人来判怎么修(构造器加 Double.isNaN 拒收,还是在 of() 里当 0.0 处理),
        // 所以这里只把现状钉住:哪天有人修了,这条会红,那是好事,连同注释一起删。
        RecognitionResult nan = RecognitionResult.of("growth-rate", Double.NaN);
        assertTrue(nan.matched(), "现状:NaN 没被拦住。修了这个口子请一并删掉这条用例");
        assertTrue(Double.isNaN(nan.confidence()));

        // 出口自检拦不住它 —— code 确实在候选集里,闭集这道没问题,漏的是阈值那道。
        assertTrue(afterModelSays(nan).matched(), "闭集检查管不着置信度,这个洞只能在阈值那层堵");
    }

    @Test
    @DisplayName("带着置信度的 NO_MATCH 是合法的 —— 这是「认出来了但不够分」的唯一表示法")
    void aNoMatchMayLegitimatelyCarryConfidence() {
        RecognitionResult result = new RecognitionResult(null, 0.42, false);

        assertFalse(result.matched());
        assertEquals(0.42, result.confidence());
    }

    @Test
    @DisplayName("没有 code 的候选造不出来 —— 只有名字的选项等于引诱模型回名字")
    void aCandidateWithoutACodeCannotBeBuilt() {
        // 候选是送进 prompt 的东西。一个只有 name 没有 code 的候选,
        // 会让模型面对一个「无 ID 可选」的选项,而它一定会拿名字来填 ——
        // 那正是上面 answeringWithTheCandidateName 那条用例在防的失守。
        for (String bad : new String[]{null, "", "  "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new VisionTagger.Candidate(bad, "增长率计算"), "[" + bad + "]");
        }
    }

    // ———————————————————— 六、形状:自由文本没有出口 ————————————————————

    @Test
    @DisplayName("🔴 RecognitionResult 上除了 nodeCode 没有任何文本字段 —— 断言的是形状,不是某次赋值")
    void theResultRecordHasNoFreeTextOutlet() {
        // docs/10 §3.1 的原话是「返回类型里根本没有 String label 字段」。
        // 那句话现在成立,但它是靠人记得而成立的 —— 下一个人加个 label 用来「给用户看个提示」
        // 完全说得通,而那一刻 R-07 就破了:模型生成的措辞有了一条合法通路。
        // 这条用例把那句话机械化:任何文本类型的新分量都会在这里红一次,逼一次显式确认。
        RecordComponent[] components = RecognitionResult.class.getRecordComponents();
        assertEquals(3, components.length,
                "分量数变了。加分量本身不是错,但每加一个都要回答「它能不能装下模型生成的字」");

        List<String> textComponents = new ArrayList<>();
        for (RecordComponent component : components) {
            if (CharSequence.class.isAssignableFrom(component.getType())) {
                textComponents.add(component.getName());
            }
        }
        assertEquals(List.of("nodeCode"), textComponents,
                "唯一允许的文本分量是 nodeCode,而它的取值范围由 enforceClosedSet 限死在候选集里");

        // 顺带堵掉换个类型装文本的写法:byte[]、char[]、Optional<String> 也能装下一句讲解。
        for (RecordComponent component : components) {
            Class<?> type = component.getType();
            assertFalse(type == char[].class || type == byte[].class,
                    "字符/字节数组同样能装下自由文本:" + component.getName());
        }
    }

    @Test
    @DisplayName("🔴 Candidate 上只有 code 与 name —— prompt 里出现的每个字都是模型的可用素材")
    void theCandidateRecordCarriesNothingButCodeAndName() {
        // 候选集是唯一进入 prompt 的业务数据。给它加一个 description / example / explanation,
        // 相当于把讲解喂给模型,再指望它别讲解 —— 而 01 §2.2 是「不做教研」,
        // 08 §1.2.5.1.6 是「不是靠 prompt 里写一句『不要讲解』,是在输出侧检」。
        // 输入侧不给素材,输出侧才检得干净。
        List<String> names = Arrays.stream(VisionTagger.Candidate.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertEquals(List.of("code", "name"), names,
                "候选只能有 code 与名称,不能捎带讲解、例题、解析");
    }
}
