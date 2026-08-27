package com.kaodian.server.collect;

import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选召回的规则测试 —— 打标管线第 ① 段(docs/13 §1.3)。
 *
 * <h2>为什么召回值得被逐条钉住,而不是「跑通就行」</h2>
 *
 * 召回决定了<b>模型看得见什么</b>。它多给一个,模型就多一个答错的机会;它少给一个,
 * 正确答案就压根不在选项里 —— 而后一种失败是<b>无声的</b>:界面上表现为「识别率低」,
 * 没人会想到去查召回。
 * <p>
 * 更要紧的是 docs/13 §1.3 那句:「<b>没有候选就没有『集』可闭</b>」。
 * 闭集不是「给模型一个提示」,是整条 R-07 的物理基础 —— 候选集就是模型被允许输出的全集。
 * 一旦有人给它加一条「召回为空就送整棵树」的兜底,闭集还在,但「闭」这件事已经没有意义了。
 * 那条兜底看起来只是提高覆盖,实际是把一次没有依据的猜测送进覆盖度。
 *
 * <h2>这个文件按「三步」组织,每步单独可红</h2>
 *
 * 切词 / 命中 / 截断三步各自有断言。合成一条「输入这个来源名应该出这几个候选」也能验,
 * 但那样一次规则改动会表现成「候选数从 6 变成 4」,<b>没人看得出改的是哪一步</b>。
 */
class CandidateRecallTest {

    private final CandidateRecall recall = new CandidateRecall();
    private final Syllabus syllabus = SyllabusLoader.loadDefault();

    private List<String> codesFor(String hint) {
        return recall.recall(syllabus, hint).stream().map(VisionTagger.Candidate::code).toList();
    }

    // ———————————————————— 第 ① 步:切词 ————————————————————

    @Test
    @DisplayName("切词:按「不是字母/数字/汉字」的字符切开,每个词出它的全部连续两字片段")
    void keywordsAreTwoCharacterSlicesOfEachWord() {
        assertEquals(List.of("增长", "长率", "率专", "专项"),
                CandidateRecall.keywordsOf("增长率专项"));

        // 分隔符不参与:「增长率」与「专项」是两个词,不会跨过分隔符拼出「率专」
        assertEquals(List.of("增长", "长率", "专项"),
                CandidateRecall.keywordsOf("增长率 · 专项"));
    }

    @Test
    @DisplayName("不足两字的词整个丢掉 —— 一个字的关键词会命中半棵树,那等于回落到整棵树")
    void singleCharacterWordsAreDropped() {
        assertEquals(List.of(), CandidateRecall.keywordsOf("课"));
        assertEquals(List.of(), CandidateRecall.keywordsOf("A · B · C"));
        // 混着来:短的丢掉,够长的照出片段
        assertEquals(List.of("增长", "长率"), CandidateRecall.keywordsOf("增长率 · 课"));
    }

    @Test
    @DisplayName("空线索直接出空 —— 「没有线索」不该走成「什么都匹配」")
    void aBlankHintYieldsNoKeywords() {
        for (String blank : new String[]{null, "", "   ", " · · ", "\t\n"}) {
            assertEquals(List.of(), CandidateRecall.keywordsOf(blank), "[" + blank + "]");
            assertEquals(List.of(), codesFor(blank), "[" + blank + "]");
        }
    }

    @Test
    @DisplayName("片段去重且保序 —— 顺序丢了,下面的断言就只能写「包含」而不是「等于」")
    void keywordsAreDeduplicatedInOrder() {
        assertEquals(List.of("增长", "长率"), CandidateRecall.keywordsOf("增长率 · 增长率"));
    }

    // ———————————————————— 第 ② 步:命中 ————————————————————

    @Test
    @DisplayName("考点名包含任一两字片段即命中;一个考点不进两遍候选")
    void aNodeIsRecalledWhenItsNameContainsAnySlice() {
        // 「增长率专项」出「增长 / 长率 / 率专 / 专项」;「增长」命中 6 个,「长率」是其中 4 个的子集。
        // 同时命中两个片段的考点(如「增长率计算」)只能出现一次。
        List<String> codes = codesFor("自己刷题 · 增长率专项");

        assertEquals(List.of(
                        "growth-rate",          // 增长率计算 9
                        "growth-amount",        // 增长量计算 8
                        "interval-growth",      // 间隔增长率 6
                        "annual-avg-growth",    // 年均增长率 4
                        "pull-growth",          // 拉动增长 3
                        "mixed-growth"),        // 混合增长率 2
                codes);
        assertEquals(codes.size(), codes.stream().distinct().count(), "同一个考点不能进两遍");
    }

    @Test
    @DisplayName("🔴 来源名里没有线索 → 召回为空。空就是空,不回落到整棵树")
    void aSourceNameWithNoSignalRecallsNothing() {
        // 这五个是种子里真实存在的来源名。它们全都召回不出东西 —— 这不是实现将就,
        // 是当前数据形态的真实上限:服务端手里关于一条记录的字只有来源名(01 §2.2 不碰内容)。
        // 诚实地出空,让调用方停在「未分类」,好过送整棵树去让模型猜一个。
        for (String sourceName : List.of(
                "粉笔 · 资料分析系统班 L12",
                "华图 · 资料速算网课",
                "自己刷题 · 2023 国考真题",
                "B站 · 资料分析技巧",
                "中公 · 资料分析专项")) {
            assertEquals(List.of(), codesFor(sourceName), sourceName);
        }
    }

    @Test
    @DisplayName("🔴 已归档的考点不进候选 —— 进了就等于让模型有机会把新记录挂到一个已停用的考点上")
    void archivedNodesAreNeverRecalled() {
        Syllabus withArchived = archive(syllabus, "growth-rate");
        List<String> codes = withArchived.allNodes().stream().map(Syllabus.Node::code).toList();
        assertFalse(codes.contains("growth-rate"), "前提:归档之后它确实退出了差集");

        List<String> recalled = new CandidateRecall().recall(withArchived, "自己刷题 · 增长率专项")
                .stream().map(VisionTagger.Candidate::code).toList();

        assertFalse(recalled.contains("growth-rate"),
                "归档的意思正是「这个考点不再使用了」,继续送它进候选会让归档变成一句空话");
        assertTrue(recalled.contains("growth-amount"), "对照:同一批里没归档的照样在");
    }

    // ———————————————————— 第 ③ 步:截断 ————————————————————

    @Test
    @DisplayName("🔴 超过上限时按近五年频次降序截断,同分按树序 —— 同样的输入必须给同样的候选集")
    void tooManyHitsAreCutByFrequencyThenTreeOrder() {
        // 「增长 · 计算」命中 11 个,超过 MAX_CANDIDATES = 10。
        // 砍掉的是考得最少的「混合增长率」(2 次)—— 如果非砍不可,留下更可能真的考到的。
        List<String> codes = codesFor("增长 · 计算");

        assertEquals(CandidateRecall.MAX_CANDIDATES, codes.size());
        assertEquals(List.of(
                        "growth-rate",          // 9
                        "growth-amount",        // 8,树序在前
                        "share-calc",           // 8,树序在后
                        "base-value",           // 7
                        "interval-growth",      // 6,树序在前
                        "average-calc",         // 6,树序在后
                        "current-value",        // 5,树序在前
                        "multiple-calc",        // 5,树序在后
                        "annual-avg-growth",    // 4
                        "pull-growth"),         // 3
                codes);
        assertFalse(codes.contains("mixed-growth"), "被砍掉的应该是频次最低的那个(混合增长率 2 次)");
    }

    @Test
    @DisplayName("同样的输入连续跑两次结果完全一致 —— 召回不许因为遍历顺序而抖动")
    void recallIsDeterministic() {
        assertEquals(codesFor("增长 · 计算"), codesFor("增长 · 计算"));
    }

    @Test
    @DisplayName("上限就是 docs/13 说的「缩到 5–10 个」的上界,不是一个可以随手往上调的数")
    void theCandidateCeilingIsTen() {
        // 这是一条绊线。改它本身是允许的,但候选一多,模型「在里面挑一个」就退化成「在里面猜一个」,
        // 而阈值挡不住一次自信的猜测 —— 所以改之前得先有评测数据,不是因为「识别老是不出结果」顺手放宽。
        assertEquals(10, CandidateRecall.MAX_CANDIDATES);
        assertEquals(2, CandidateRecall.MIN_KEYWORD_LENGTH);
    }

    @Test
    @DisplayName("候选里只有 code 与名称 —— prompt 里出现的每个字都是模型的可用素材")
    void candidatesCarryNothingButCodeAndName() {
        List<VisionTagger.Candidate> candidates = recall.recall(syllabus, "自己刷题 · 增长率专项");
        assertFalse(candidates.isEmpty(), "前提:这个线索确实召回得出东西");

        for (VisionTagger.Candidate candidate : candidates) {
            Syllabus.Node node = syllabus.node(candidate.code());
            assertEquals(node.name(), candidate.name(), "名字必须原样来自树,不是这里编的");
        }
    }

    /** 把一棵树里的某个考点标成已归档,其余原样。 */
    private static Syllabus archive(Syllabus tree, String nodeCode) {
        return new Syllabus(tree.subject(), tree.groups().stream()
                .map(g -> new Syllabus.Group(g.code(), g.name(), g.nodes().stream()
                        .map(n -> n.code().equals(nodeCode)
                                ? new Syllabus.Node(n.code(), n.name(), n.recent5yCount(), true)
                                : n)
                        .toList()))
                .toList());
    }
}
