package com.kaodian.server.collect;

import com.kaodian.server.recognize.RecognitionResult;
import com.kaodian.server.recognize.RecognitionUnavailableException;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusSource;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 「记一笔」的唯一入口 —— 五种记录方式在这里收口。
 *
 * <h2>🔴 记录动作永不失败</h2>
 *
 * docs/08 §1.3.7.1 的原文:识别服务不可用时,<b>记录动作本身永不失败</b>;
 * §1.3.7.2:打标服务不可用时,<b>记录先落地,标签异步补</b>。
 * <p>
 * 落到这里是一条很具体的分支顺序:<b>先看用户挑没挑考点,再看模型认没认出来。</b>
 * 用户已经挑了,识别结果无论成败都只是锦上添花,记录照样落地。
 * 只有「用户没挑 + 模型也没认出来」才拒绝 —— 因为 {@link Touch} 必须挂在一个考点上,
 * 没有考点的记录进不了差集,存下来也只是一条谁都用不上的数据。
 * <p>
 * 拒绝时返回的 {@link Rejection} 会说清是哪种情况:模型说了不匹配、模型压根没跑成、
 * 还是用户给了一个树里没有的 code。这三句话在界面上该说的下一步完全不同。
 *
 * <h2>🔴 挂载只认考点树里的 code</h2>
 *
 * {@code R-07} / docs/10 §6.3:「body <b>只接受 {@code nodeId}</b>,不接受 {@code name}。
 * 从树里选,不能新建。」这里用 {@link Syllabus#node} 校验一遍 ——
 * <b>不在树里就拒绝,不会顺手建一个新考点</b>。
 * 只要没有一条通道能写进自由文本标签,自由生成的考点就进不了库,无论模型输出什么。
 *
 * <h2>这里没有任何学科判断</h2>
 *
 * 做题数是照抄用户敲进来的两个整数,不判对错、不算难度、不预测掌握度(01 §2.2)。
 */
@Service
public class CaptureService {

    private final TouchStore store;
    private final VisionTagger visionTagger;
    private final SyllabusSource syllabus;
    private final Clock clock;

    /**
     * 骨架树与时钟都从外面注入,不在这里 {@code SyllabusLoader.loadDefault()} 或 {@code Instant.now()}。
     *
     * <p>树:阶段 0/1 只有一棵(01 §2.2「一个模块、一个科目起步」——
     * 两棵树同时冷启动对 2-3 人团队是灾难)。<b>「一棵」的意思是全进程共用同一份</b>,
     * 不是每个类各自加载一份看起来一样的。
     * <p>
     * 🔴 注入的是 {@link SyllabusSource} 而不是 {@link Syllabus} 本身:骨架层现在<b>可写</b>,
     * 持有一棵 record 树等于持有进程启动那一刻的快照 —— 用户刚建的考点会被这里判成
     * 「不在树里」,而且不会报错。所以每次校验前都重新问一次「现在的树长什么样」。
     *
     * <p>时钟:「多久前」是这个产品仅有的三个维度之一,「现在几点」因此必须是一个
     * <b>可替换的依赖</b> —— 散落的 {@code Instant.now()} 会让「32 天前练过」这类场景没法回放。
     */
    public CaptureService(TouchStore store, VisionTagger visionTagger, SyllabusSource syllabus, Clock clock) {
        this.store = store;
        this.visionTagger = visionTagger;
        this.syllabus = syllabus;
        this.clock = clock;
    }

    /**
     * 一次采集请求。
     *
     * <h2>🔴 这里没有装内容的字段,和 {@link Touch} 是同一条线</h2>
     *
     * 没有 {@code text}、没有 {@code note}、没有 {@code transcript}。
     * 入参里若能装下一段文字,它迟早会被存下来 —— 01 §2.2 不碰内容,
     * docs/10 §5.1「不是不往里填,是不建这个列」。
     *
     * @param kind       怎么记的
     * @param sourceName 来源名,如「粉笔 · 资料分析系统班 L12」。<b>只是个名字</b>
     * @param nodeCode   用户自己从树里挑的考点;可空,空则等模型识别
     * @param practiced  练了几道;可空
     * @param correct    <b>用户自己说</b>对了几道;可空
     */
    public record CaptureRequest(
            TouchKind kind,
            String sourceName,
            String nodeCode,
            Integer practiced,
            Integer correct
    ) {
        /** 只挂一个考点,不带做题数。 */
        public static CaptureRequest manual(TouchKind kind, String sourceName, String nodeCode) {
            return new CaptureRequest(kind, sourceName, nodeCode, null, null);
        }
    }

    /** 这个考点是谁挂上去的。<b>AI 与手动的唯一区别就在这一列</b>(见 {@link TouchKind})。 */
    public enum Mounting {
        /** 用户自己从树里挑的。永不消耗额度,永远可用。 */
        USER_PICKED("你自己挑的"),
        /** 模型从候选集里选的,且置信度过了阈值。 */
        RECOGNIZED("识别挑的");

        private final String label;

        Mounting(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 为什么没落地。<b>三种情况在界面上该说的下一步完全不同,所以不能合成一个。</b> */
    public enum Rejection {
        /** 手动记录却没给考点。界面:请从树里挑一个。 */
        MISSING_NODE_CODE("没挑考点"),
        /** 给的 code 不在树里。界面:这个考点不存在 —— 🔴 不会顺手建一个(R-07)。 */
        NODE_NOT_IN_SYLLABUS("这个考点不在树里"),
        /** 模型看了,说不匹配(或置信度不够,按宁缺毋滥丢弃)。界面:自己挑一个。 */
        NO_MATCH_AND_NO_USER_NODE("没认出来,请自己挑一个考点"),
        /** 模型压根没跑成(没配密钥/超时/限流)。界面:稍后重试,或自己挑一个。 */
        RECOGNIZER_UNAVAILABLE_AND_NO_USER_NODE("识别服务暂时不可用,请自己挑一个考点");

        private final String label;

        Rejection(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 采集结果。用 sealed 是为了让调用方<b>必须</b>把「拒绝」这一支处理掉 ——
     * 这条路径不是异常情况,它是产品的正常状态之一(识别本来就会认不出来)。
     */
    public sealed interface CaptureResult {

        /** 落地了。 */
        record Recorded(Touch touch, Mounting mounting, RecognitionResult recognition) implements CaptureResult {}

        /**
         * 没落地。
         *
         * @param recognition 识别的原始结果,带着置信度 ——
         *                    「0.42 分被阈值丢掉」和「什么都没认出来」要能分得开
         */
        record Rejected(Rejection reason, RecognitionResult recognition) implements CaptureResult {}

        default boolean recorded() {
            return this instanceof Recorded;
        }
    }

    /**
     * 手动记一笔 —— {@link TouchKind#PASTE} / {@link TouchKind#DRILL} / {@link TouchKind#MANUAL}。
     *
     * <p><b>这条路不碰任何模型,因此永不消耗额度、永不受识别故障影响。</b>
     * docs/11 §二「额度用尽 ≠ 记不了」的实现就是它一直在这儿。
     */
    public CaptureResult capture(CaptureRequest request) {
        if (isBlank(request.nodeCode())) {
            return new CaptureResult.Rejected(Rejection.MISSING_NODE_CODE, RecognitionResult.noMatch());
        }
        return mountAndAppend(request, request.nodeCode(), Mounting.USER_PICKED, RecognitionResult.noMatch());
    }

    /**
     * 拍照记一笔 —— 闭集分类挑考点。
     *
     * <h2>🔴 图片只在内存里过一次</h2>
     *
     * 字节进来、转 base64 内联送给模型、方法返回即释放。
     * <b>不落盘、不进对象存储、不建图片桶、不打进任何级别的日志</b>(docs/10 §8.1 五条禁令),
     * 也不调用厂商的 Files API(docs/09 坑二 —— 它免费,看起来像白送的优化,
     * 而 01 §2.3 那条红线「第一天不定,后面改不回来」)。
     * <p>
     * 这个方法里刻意<b>没有任何请求日志</b>:一次 {@code log.debug(request)}
     * 就等于把原图落了盘,而且落在最不容易想到的地方。
     *
     * <h2>识别怎么失败,记录都不会丢</h2>
     *
     * 用户已经挑了考点 → 无论识别成败照样落地;识别的结果原样带回去供界面提示。
     *
     * @param image    原图字节
     * @param mimeType 如 {@code image/jpeg}
     */
    public CaptureResult captureFromPhoto(CaptureRequest request, byte[] image, String mimeType) {
        List<VisionTagger.Candidate> candidates = candidates();

        RecognitionResult recognition = RecognitionResult.noMatch();
        boolean available = true;
        try {
            // 出口处再核一遍候选集:模型可能吐回一个树里没有的 code(docs/09 坑一的「编造考点」)。
            // 08 §1.2.5.1.6:不是靠 prompt 里写一句,是在输出侧检。
            recognition = VisionTagger.enforceClosedSet(
                    visionTagger.classify(image, mimeType, candidates), candidates);
        } catch (RecognitionUnavailableException e) {
            available = false;      // 识别挂了 ≠ 记录挂了(docs/08 §1.3.7.1)
        }

        // 🔴 顺序是红线本身:先看用户挑没挑,再看模型认没认出来。
        if (!isBlank(request.nodeCode())) {
            return mountAndAppend(request, request.nodeCode(), Mounting.USER_PICKED, recognition);
        }
        if (recognition.matched()) {
            return mountAndAppend(request, recognition.nodeCode(), Mounting.RECOGNIZED, recognition);
        }
        return new CaptureResult.Rejected(
                available ? Rejection.NO_MATCH_AND_NO_USER_NODE
                          : Rejection.RECOGNIZER_UNAVAILABLE_AND_NO_USER_NODE,
                recognition);
    }

    /**
     * 送给模型的候选集。
     *
     * <p>现在是整棵树(单模块 18 个考点),够小。08 §1.2.5.1.2 要的
     * 「先缩小到 5-10 个候选」是<b>省钱手段</b>,等考点上量再做;
     * 而<b>闭集本身是红线</b>,一天都不能等 —— docs/09 坑一说的
     * 「候选召回不只是省钱手段,更是合规与准确性的实现方式」就是这个区别。
     */
    private List<VisionTagger.Candidate> candidates() {
        return syllabus.current().allNodes().stream()
                .map(n -> new VisionTagger.Candidate(n.code(), n.name()))
                .toList();
    }

    /**
     * 校验 → 落库。
     *
     * <p>校验只有一条:<b>这个 code 必须真的在树里</b>。
     * 挂不上就说明它不是考点树里的节点,而这个产品不接受树外的标签(R-07)。
     * <p>
     * {@code Syllabus#node} 查的是<b>未归档</b>的考点,所以归档的考点也挂不上新记录 ——
     * 归档的意思正是「这个考点不再使用了」,继续往上挂会让归档变成一句空话。
     */
    private CaptureResult mountAndAppend(CaptureRequest request, String nodeCode,
                                         Mounting mounting, RecognitionResult recognition) {
        if (syllabus.current().node(nodeCode) == null) {
            return new CaptureResult.Rejected(Rejection.NODE_NOT_IN_SYLLABUS, recognition);
        }

        Touch touch = new Touch(
                newId(),
                nodeCode,
                request.sourceName(),
                request.kind(),
                clock.instant().truncatedTo(ChronoUnit.MILLIS),
                drillOf(request));

        return new CaptureResult.Recorded(store.append(touch), mounting, recognition);
    }

    /**
     * 做题数 —— <b>原样照抄用户敲进来的两个整数</b>。
     *
     * <p>没填 practiced 就是没有做题这回事(仅接触),不是 0 道。
     * 这里没有、也不会有任何判题、正确率预测或难度模型(01 §2.2)。
     */
    private static Touch.Drill drillOf(CaptureRequest request) {
        if (request.practiced() == null) {
            return null;
        }
        return new Touch.Drill(request.practiced(), request.correct() == null ? 0 : request.correct());
    }

    private static String newId() {
        return "t-" + UUID.randomUUID();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
