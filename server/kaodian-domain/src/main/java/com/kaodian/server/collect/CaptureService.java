package com.kaodian.server.collect;

import com.kaodian.server.recognize.RecognitionResult;
import com.kaodian.server.recognize.RecognitionUnavailableException;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusSource;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 「记一笔」的唯一入口 —— 五种记录方式在这里收口。
 *
 * <h2>🔴 记录动作永不失败</h2>
 *
 * docs/execution/INDEX.md §1.3.7.1 的原文:识别服务不可用时,<b>记录动作本身永不失败</b>;
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
 * {@code R-07} / docs/technical/INDEX.md §6.3:「body <b>只接受 {@code nodeId}</b>,不接受 {@code name}。
 * 从树里选,不能新建。」这里用 {@link Syllabus#node} 校验一遍 ——
 * <b>不在树里就拒绝,不会顺手建一个新考点</b>。
 * 只要没有一条通道能写进自由文本标签,自由生成的考点就进不了库,无论模型输出什么。
 *
 * <h2>这里没有任何学科判断</h2>
 *
 * 做题数是照抄用户敲进来的两个整数,不判对错、不算难度、不预测掌握度(决策记录 §2.2)。
 */
@Service
public class CaptureService {

    /**
     * 用 JDK 自带的 {@link System.Logger},不引 slf4j。
     *
     * <p>{@code kaodian-domain} 的依赖表是空的,而且那是<b>有意的</b>(见模块划分那张表)——
     * 为了一条 WARN 给它加第一个第三方依赖,代价与收益不成比例。
     */
    private static final System.Logger LOG = System.getLogger(CaptureService.class.getName());

    private final TouchStore store;
    private final VisionTagger visionTagger;
    private final SyllabusSource syllabus;
    private final Clock clock;

    /**
     * 骨架树与时钟都从外面注入,不在这里 {@code SyllabusLoader.loadDefault()} 或 {@code Instant.now()}。
     *
     * <p>树:阶段 0/1 只有一棵(决策记录 §2.2「一个模块、一个科目起步」——
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
     * 入参里若能装下一段文字,它迟早会被存下来 —— 决策记录 §2.2 不碰内容,
     * docs/technical/INDEX.md §5.1「不是不往里填,是不建这个列」。
     *
     * @param kind        怎么记的
     * @param sourceName  来源名,如「粉笔 · 资料分析系统班 L12」。<b>只是个名字</b>
     * @param nodeCode    用户自己从树里挑的考点;可空,空则等模型识别
     * @param practiced   练了几道;可空
     * @param correct     <b>用户自己说</b>对了几道;可空
     * @param clientToken 去重键;可空。<b>只有离线队列补传那条路会给</b>,契约见 {@link TouchStore#append}
     * @param occurredAt  记录<b>落本地那一刻</b>;可空。见下面那段 —— <b>只有补传那条路会给</b>
     */
    public record CaptureRequest(
            TouchKind kind,
            String sourceName,
            String nodeCode,
            Integer practiced,
            Integer correct,

            @Size(max = Touch.MAX_CLIENT_TOKEN_LENGTH)
            String clientToken,

            /*
             * 🔴 可空,而两条路各走各的(M1-记录采集与离线补传 §3.6):
             *
             *   POST /records        —— 不带,服务端 clock.instant() 打戳。
             *                           在线时「落本地那一刻」与「服务端收到那一刻」相差毫秒级,
             *                           服务端打戳既准确又不可伪造。
             *   POST /records/batch  —— 每个条目必带。补传路径上两个时刻相差可以是两周,
             *                           服务端打戳会把用户断网那几天记的东西【全部】落进补传当天的分组,
             *                           而用户会用「昨天第三条」定位一条记录 —— 顺序一变他的结论是
             *                           「数据变了」,不是「排序规则变了」。
             *
             * 这不是「客户端自报时间」的例外:那条规则挡的是【补记】(界面上没有时间选择器),
             * 而这个值是端在落本地那一刻记下的,用户没有任何入口能改它。
             * 防伪造靠钳制不靠信任 —— 见 mountAndAppend 里的上界钳制。
             */
            Instant occurredAt
    ) {
        /**
         * 在线直接记 —— 没有去重键,也不自报时间。
         *
         * <p>与 {@link Touch#clientToken()} 那段同一个理由:在线记一笔的成败当场就知道,
         * 不需要去重键;强迫它编一个,只会让这个字段可以是任何东西。
         */
        public CaptureRequest(TouchKind kind, String sourceName, String nodeCode,
                              Integer practiced, Integer correct) {
            this(kind, sourceName, nodeCode, practiced, correct, null, null);
        }

        /** 在线直接记,带去重键。 */
        public CaptureRequest(TouchKind kind, String sourceName, String nodeCode,
                              Integer practiced, Integer correct, String clientToken) {
            this(kind, sourceName, nodeCode, practiced, correct, clientToken, null);
        }

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

        /**
         * 落地了。
         *
         * @param replayed 这一次<b>没有新建任何东西</b>,返回的是同一个 {@code clientToken}
         *                 之前已经落下的那条。调用方据此把 HTTP 状态从 201 降成 200、
         *                 把批量里那一条标成「重复」—— 而不是当成一次新的写入去邀功
         */
        record Recorded(Touch touch, Mounting mounting, RecognitionResult recognition,
                        boolean replayed) implements CaptureResult {}

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
     * docs/product/商业化与额度设计.md §二「额度用尽 ≠ 记不了」的实现就是它一直在这儿。
     *
     * <h2>🔴 {@code userId} 是参数,不是这一层自己去拿的东西(B0 §4.3)</h2>
     *
     * {@code app} 从鉴权上下文取出来递进来。这个包里没有任何「当前用户」类型的入口
     * (禁词全集见 {@link Tenant}),也不依赖 {@code kaodian-auth} 的任何类型 ——
     * 那条边由 {@code kaodian-domain/pom.xml} 上的 enforcer 在构建期拦着。
     * 这里只校验它 {@code > 0}(在 {@link Touch} 的构造器里),<b>不查这个用户存不存在</b>。
     */
    public CaptureResult capture(long userId, CaptureRequest request) {
        CaptureResult replay = replayOf(userId, request);
        if (replay != null) {
            return replay;
        }
        if (isBlank(request.nodeCode())) {
            return new CaptureResult.Rejected(Rejection.MISSING_NODE_CODE, RecognitionResult.noMatch());
        }
        return mountAndAppend(userId, request, request.nodeCode(),
                Mounting.USER_PICKED, RecognitionResult.noMatch());
    }

    /**
     * 补传命中已落地的那条 → 直接把它交回去,<b>后面一步都不走</b>。
     *
     * <h2>为什么判重要在这里,而不只在 {@link TouchStore#append} 里</h2>
     *
     * {@code append} 那道是<b>原子性</b>的锁(同一把写锁里查+写,两个并发补传不会各写一条)。
     * 这一道管的是<b>语义</b>:一次补传如果走完全程,会先撞上校验,而那些校验的答案可能已经变了 ——
     * 用户断网期间记的那个考点,回到线上时可能已经被他自己归档或删掉了。
     * 那时 {@code mountAndAppend} 会回一句「这个考点不在树里」,而<b>那条记录明明早就落下了</b>。
     * 一次成功的写入不能因为重发一遍就变成失败,否则离线队列会永远卡在那一条上重试。
     * <p>
     * 🔴 顺带挡住的是钱:{@link #captureFromPhoto} 里这一步在<b>调模型之前</b>,
     * 所以补传重发不会再花一次识别(docs/technical/INDEX.md §6.7.1「同一 idempotencyKey 重试不重复扣」)。
     *
     * @return 命中就返回 {@code Recorded(replayed = true)};没有去重键或没命中返回 {@code null}
     */
    private CaptureResult replayOf(long userId, CaptureRequest request) {
        // 🔴 判重按 (userId, clientToken):去重键是客户端自己生成的,两个人之间没有任何约定。
        Touch existing = store.findByClientToken(userId, request.clientToken());
        if (existing == null) {
            return null;
        }
        // 挂载来源按原样报 USER_PICKED / RECOGNIZED 是做不到的 —— 那是第一次落地时的事,没有存下来。
        // 与其编一个,不如让 replayed 这个标志说话:调用方要的是「这次什么都没发生」,不是挂载来源。
        return new CaptureResult.Recorded(existing, Mounting.USER_PICKED, RecognitionResult.noMatch(), true);
    }

    /**
     * 拍照记一笔 —— 闭集分类挑考点。
     *
     * <h2>🔴 图片只在内存里过一次</h2>
     *
     * 字节进来、转 base64 内联送给模型、方法返回即释放。
     * <b>不落盘、不进对象存储、不建图片桶、不打进任何级别的日志</b>(docs/technical/INDEX.md §8.1 五条禁令),
     * 也不调用厂商的 Files API(docs/data/识别链路选型.md 坑二 —— 它免费,看起来像白送的优化,
     * 而 决策记录 §2.3 那条红线「第一天不定,后面改不回来」)。
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
    public CaptureResult captureFromPhoto(long userId, CaptureRequest request, byte[] image, String mimeType) {
        // 🔴 判重在调模型【之前】—— 补传重发一次不该再花一次识别(见 replayOf)
        CaptureResult replay = replayOf(userId, request);
        if (replay != null) {
            return replay;
        }

        List<VisionTagger.Candidate> candidates = candidates();

        RecognitionResult recognition = RecognitionResult.noMatch();
        boolean available = true;
        try {
            // 出口处再核一遍候选集:模型可能吐回一个树里没有的 code(docs/data/识别链路选型.md 坑一的「编造考点」)。
            // 总路线图 §1.2.5.1.6:不是靠 prompt 里写一句,是在输出侧检。
            recognition = VisionTagger.enforceClosedSet(
                    visionTagger.classify(image, mimeType, candidates), candidates);
        } catch (RecognitionUnavailableException e) {
            available = false;      // 识别挂了 ≠ 记录挂了(docs/execution/INDEX.md §1.3.7.1)
        }

        // 🔴 顺序是红线本身:先看用户挑没挑,再看模型认没认出来。
        if (!isBlank(request.nodeCode())) {
            return mountAndAppend(userId, request, request.nodeCode(), Mounting.USER_PICKED, recognition);
        }
        if (recognition.matched()) {
            return mountAndAppend(userId, request, recognition.nodeCode(), Mounting.RECOGNIZED, recognition);
        }
        return new CaptureResult.Rejected(
                available ? Rejection.NO_MATCH_AND_NO_USER_NODE
                          : Rejection.RECOGNIZER_UNAVAILABLE_AND_NO_USER_NODE,
                recognition);
    }

    /**
     * 送给模型的候选集。
     *
     * <p>现在是整棵树(单模块 18 个考点),够小。总路线图 §1.2.5.1.2 要的
     * 「先缩小到 5-10 个候选」是<b>省钱手段</b>,等考点上量再做;
     * 而<b>闭集本身是红线</b>,一天都不能等 —— docs/data/识别链路选型.md 坑一说的
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
    private CaptureResult mountAndAppend(long userId, CaptureRequest request, String nodeCode,
                                         Mounting mounting, RecognitionResult recognition) {
        if (syllabus.current().node(nodeCode) == null) {
            return new CaptureResult.Rejected(Rejection.NODE_NOT_IN_SYLLABUS, recognition);
        }

        Touch touch = new Touch(
                newId(),
                userId,
                nodeCode,
                request.sourceName(),
                request.kind(),
                occurredAtOf(request),
                drillOf(request),
                request.clientToken());

        Touch stored = store.append(touch);

        // append 命中去重键时返回的是【原来那条】。用 id 是否还是我们刚生成的那个来判断,
        // 比让 store 多返回一个布尔值可靠:它问的是事实(库里那条是不是我这次造的),
        // 而不是相信调用链上某一层记得把标志传下来。
        return new CaptureResult.Recorded(stored, mounting, recognition, !stored.id().equals(touch.id()));
    }

    /**
     * 做题数 —— <b>原样照抄用户敲进来的两个整数</b>。
     *
     * <p>没填 practiced 就是没有做题这回事(仅接触),不是 0 道。
     * 这里没有、也不会有任何判题、正确率预测或难度模型(决策记录 §2.2)。
     */
    /**
     * 这一笔算<b>什么时候</b>发生的 —— 服务端打戳 vs 端自报,以及自报时的上界钳制。
     *
     * <h2>🔴 钳制上界,不钳下界,而且不拒绝</h2>
     *
     * 设备时钟被改到未来是真实会发生的事(用户为了别的 app 手动调时间、时区数据过期)。
     * 一条落在未来的记录在时间线上没有意义 —— 它会永远待在列表最上面,而且「多久前」是负数。
     * <b>但它不该让这条记录失败</b>:记录本身是真的,错的只是那台设备的时钟。所以钳到 {@code now},
     * 记一条 WARN 留痕,记录照落。
     * <p>
     * 🚧 <b>下界暂不设</b>:「多久以前的补传还该被接受」只能由本地队列长度上限推出来,而那个数还没有实测过。
     * 在这里替它拍一个数,后果是用户离线超过那个数的那一批记录被静默拒收 —— 不在这里猜。
     */
    private Instant occurredAtOf(CaptureRequest request) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (request.occurredAt() == null) {
            return now;                                  // POST /records:服务端打戳
        }
        Instant claimed = request.occurredAt().truncatedTo(ChronoUnit.MILLIS);
        if (claimed.isAfter(now)) {
            // 🔴 只带 clientToken,不带 sourceName、不带 nodeCode —— 日志里不许出现用户送来的自由文本。
            LOG.log(System.Logger.Level.WARNING,
                    "补传的 occurredAt 落在未来,已钳到当前时刻:claimed=" + claimed + " now=" + now
                            + " clientToken=" + request.clientToken());
            return now;
        }
        return claimed;
    }

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
