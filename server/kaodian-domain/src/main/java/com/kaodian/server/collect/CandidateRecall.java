package com.kaodian.server.collect;

import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.Syllabus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 打标管线的<b>第 ① 段:候选召回</b>(docs/13 §1.3)。
 *
 * <h2>🔴 这一段不是省钱技巧,是闭集的前提</h2>
 *
 * docs/13 §1.3 ① 的原话:「关键词/规则,缩到 5–10 个。<b>便宜手段先做。
 * 不是省钱技巧,是闭集的前提 —— 没有候选就没有『集』可闭</b>」。
 * <p>
 * 这句话在代码上的落点是两条,少一条这一段就白写:
 * <ol>
 *   <li>{@link VisionTagger#classify} 的 {@code candidates} 是<b>必填入参</b>,
 *       {@code StubVisionTagger} 会对空候选当场抛 —— 也就是说,召回不出东西时
 *       <b>根本调不动模型</b></li>
 *   <li>召回为空时调用方必须<b>直接标为未分类</b>,而不是回落到整棵树。
 *       docs/13 §1.3 的原话:「召回不出来就不调模型。<b>调了也只能瞎猜</b>」</li>
 * </ol>
 * 第二条是这里最容易被「优化」掉的:回落到整棵树看起来更有用(至少模型有机会答对),
 * 实际是把一次没有依据的猜测送进覆盖度,而<b>覆盖度失真的话,这个产品就没有指标了</b>。
 *
 * <h2>关键词从哪来 —— 只可能是来源名,这一点是被红线逼出来的</h2>
 *
 * 服务端手里关于一条记录的全部东西是:考点 code、<b>来源名</b>、记录方式、时间戳、做题数。
 * 没有转写文本、没有笔记摘要、没有原图 —— 01 §2.2「不碰内容」/ docs/10 §5.1
 * 「不是不往里填,是不建这个列」。所以能拿来当关键词的<b>只有来源名</b>
 * (「粉笔 · 资料分析系统班 L12」),它有 {@code @Size(max = 60)} 兜着,是个名字不是内容。
 * <p>
 * ⚪ 这个信号很弱,而且弱得可预测:大多数来源名里只有机构名和课程序号,
 * 召回结果会是空的,于是记录停在「未分类」等用户自己挑。
 * <b>这不是实现将就,是当前数据形态的真实上限</b> ——
 * 要让它变强,得先有一个「能装下一句话摘要」的字段,而那是 docs/10 §5.2 的
 * {@code extracted_text} 与本仓库红线之间没有解决的那个冲突(已在交付说明里报出)。
 *
 * <h2>为什么规则写得这么死板</h2>
 *
 * 「用关键词/规则缩到 5–10 个」很容易写成一坨随手调的启发式,而启发式的问题不是它不准,
 * 是<b>它不可复现</b>:今天召回 7 个、明天召回 3 个,而没有人说得清中间改了什么。
 * 所以这里只有三步,每一步都能被一条断言单独钉住(见 {@code CandidateRecallTest}):
 * <ol>
 *   <li><b>切词</b> —— 按「不是字母/数字/汉字」的字符切开</li>
 *   <li><b>取两字片段</b> —— 每个词的全部<b>连续两字</b>子串就是关键词;
 *       考点名包含其中任何一个即命中</li>
 *   <li><b>按频次截断</b> —— 超过 {@link #MAX_CANDIDATES} 时按近五年频次降序留下</li>
 * </ol>
 *
 * <h2>为什么是两字片段,而不是拿整个词去匹配</h2>
 *
 * 因为方向对不上。来源名切出来的是长词(「增长率专项」),考点名是短词(「增长率计算」),
 * <b>谁都不是谁的子串</b> —— 拿整词匹配的结果是几乎永远召回为空,那不是「严」,是坏了。
 * 两字片段让「增长率专项」出「增长 / 长率 / 率专 / 专项」,而「增长率计算」包含「增长」,于是命中。
 * <p>
 * 这条规则会带进假阳性(「计算」一下子命中七个考点),而<b>那正是它该有的样子</b>:
 * 召回的职责是把 18 个缩到 10 个以内,不是挑出对的那个 —— 挑出对的那个是模型的事,
 * 而挑错了有阈值和出口自检兜着。反过来,召回收得太严的代价是<b>无声的</b>:
 * 正确的考点压根没进候选,模型再准也答不出来。
 */
public class CandidateRecall {

    /**
     * 候选上限 —— docs/13 §1.3 的「缩到 5–10 个」取上界。
     *
     * <p>上限的作用不是省 token,是<b>让闭集真的是个小集合</b>:
     * 候选一多,模型「在里面挑一个」就退化成「在里面猜一个」,而阈值挡不住一次自信的猜测。
     */
    public static final int MAX_CANDIDATES = 10;

    /**
     * 关键词的长度 —— <b>正好两个字</b>(按<b>码点</b>数,不是 UTF-16 长度)。
     *
     * <p>一个字太噪:「数」「率」「比」几乎命中半棵树,而它们不携带任何信息。
     * 放它们进来的后果不是多召回几个,是<b>召回结果恒等于按频次排序的前十个</b> ——
     * 那正好是这一段最想避免的「回落到整棵树」。
     * <p>
     * 三个字太严:考点名平均只有四五个字,三字片段基本只在「完全说对了」时才命中,
     * 而那种时候用户本来就会自己挑。
     */
    public static final int MIN_KEYWORD_LENGTH = 2;

    /**
     * 从骨架树里缩出这次要送进模型的候选。
     *
     * @param syllabus 当前的骨架树。<b>只看未归档的考点</b>({@link Syllabus#allNodes()}) ——
     *                 归档的意思正是「这个考点不再使用了」,把它送进候选等于让模型有机会挂上去
     * @param hint     召回线索,今天是记录的来源名;{@code null} 或空白一律召回空
     * @return 候选集,<b>可能为空</b>。空就是空 —— 调用方必须据此不调模型,不许回落到整棵树
     */
    public List<VisionTagger.Candidate> recall(Syllabus syllabus, String hint) {
        List<String> keywords = keywordsOf(hint);
        if (keywords.isEmpty()) {
            return List.of();
        }

        List<Syllabus.Node> hits = new ArrayList<>();
        for (Syllabus.Node node : syllabus.allNodes()) {
            String name = node.name().toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (name.contains(keyword)) {
                    hits.add(node);
                    break;      // 命中一次就够,一个考点不进两遍候选
                }
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }

        // 超过上限时按近五年频次降序截断,同分按树序 —— 与 CoverageService.blindSpots 的
        // 「同分按树序」同一条:同样的输入必须得到同样的候选集,不能因为遍历顺序而抖动。
        // 砍掉的是「考得少的那些」:如果非砍不可,留下更可能真的考到的。
        List<Syllabus.Node> ordered = new ArrayList<>(hits);
        ordered.sort(Comparator
                .comparingInt(Syllabus.Node::recent5yCount).reversed()
                .thenComparingInt(hits::indexOf));

        return ordered.stream()
                .limit(MAX_CANDIDATES)
                // 🔴 候选里只有 code 与名称,没有讲解、例题、解析 ——
                //    prompt 里出现的每一个字都会变成模型的可用素材(见 VisionTagger.Candidate)
                .map(n -> new VisionTagger.Candidate(n.code(), n.name()))
                .toList();
    }

    /**
     * 切词 + 取两字片段 —— 召回的第 ①② 步。
     *
     * <p>包级可见是为了让 {@code CandidateRecallTest} 直接钉住它:关键词一变,
     * 召回结果就变,而召回结果决定了<b>模型看得见什么</b>。
     * 这一步不该只能通过整条管线间接观察 —— 那样一次规则改动会表现成
     * 「某个用例的候选数从 6 变成 4」,没人看得出改的是切词还是排序。
     *
     * <p>去重用 {@link LinkedHashSet},并保留出现顺序:同一个片段重复参与匹配不会改变结果,
     * 但会让报错信息和调试输出没法读;而顺序丢了,断言就只能写成「包含」而不是「等于」。
     */
    static List<String> keywordsOf(String hint) {
        if (hint == null || hint.isBlank()) {
            return List.of();
        }
        Set<String> keywords = new LinkedHashSet<>();
        List<Integer> current = new ArrayList<>();
        for (int i = 0; i < hint.length(); ) {
            int codePoint = hint.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                current.add(codePoint);
            } else {
                flush(keywords, current);
            }
        }
        flush(keywords, current);
        return List.copyOf(keywords);
    }

    /** 一个词 → 它的全部连续两字片段。不足两字的词整个丢掉。 */
    private static void flush(Set<String> keywords, List<Integer> word) {
        for (int i = 0; i + MIN_KEYWORD_LENGTH <= word.size(); i++) {
            StringBuilder slice = new StringBuilder();
            for (int j = i; j < i + MIN_KEYWORD_LENGTH; j++) {
                slice.appendCodePoint(word.get(j));
            }
            keywords.add(slice.toString().toLowerCase(Locale.ROOT));
        }
        word.clear();
    }
}
