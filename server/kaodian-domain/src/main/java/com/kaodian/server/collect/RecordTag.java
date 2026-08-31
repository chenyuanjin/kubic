package com.kaodian.server.collect;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条记录挂在一个考点上 —— <b>覆盖度的分子就是这些行数出来的</b>。
 *
 * <h2>🔴 这里没有 label、没有 name、没有 text</h2>
 *
 * 与 {@link com.kaodian.server.recognize.RecognitionResult} 同一条线:标签指向的是
 * <b>考点树里的一个 code</b>,不是一段字。R-07 要的「不沿用机构既有体系与措辞」在这里的形态是
 * ——<b>库里根本没有能装下一个自行输入的标签名的位置</b>,所以无论模型输出什么、
 * 无论接口收到什么,进得来的只有一个 code。
 *
 * <h2>🔴 {@code origin} 写入后不可变</h2>
 *
 * 理由写在 {@link TagOrigin} 上,这里只说落地形态 —— <b>三道锁</b>:
 * <ol>
 *   <li>这是 record,没有 setter</li>
 *   <li>{@link #confirm} 与 {@link #discard} 是仅有的两个变更入口,它们把 {@code origin}
 *       从 {@code this} 原样带过去 —— <b>不是「记得别改」,是签名上没有改它的位置</b></li>
 *   <li>{@link RecordTagStore#put} 在写入侧再核一遍:同一个 id 的行,{@code origin} 变了就抛</li>
 * </ol>
 * 三道是冗余的,冗余是有意的 —— 与 docs/技术架构 §6.5 那四道 MCP 只读锁同一条纪律:
 * 一道失效不该导致整条线失守。
 *
 * <h2>{@code discarded} 是「可见但不计覆盖度」,不是删除</h2>
 *
 * {@code P1-7} / docs/技术架构 §5.2:「{@code discarded=1} 即宁缺毋滥的落地:<b>可见,但不计覆盖度</b>」。
 * 所以它是一个标志位而不是一次删除 —— 用户得看得见「这条我丢过」,否则同一个错标会被反复建议、
 * 反复丢弃,而他不知道自己已经丢过一次。
 *
 * @param id          标签 id。主标签的 id 由 {@link #primaryIdOf} 从记录 id 推出,不是随机的
 * @param recordId    挂在哪条记录上({@link Touch#id()})
 * @param nodeCode    挂到哪个考点。🔴 只可能是考点树里的 code
 * @param confidence  模型自报的置信度。手动标签恒为 {@link #MANUAL_CONFIDENCE}
 * @param origin      这条标签从哪来。<b>写入后不可变</b>
 * @param confirmedAt 用户确认的时刻;{@code null} 表示还没人确认过
 * @param discarded   丢弃标志。{@code true} 时这条标签仍然可见,但<b>不计覆盖度</b>
 */
public record RecordTag(

        @Size(max = RecordTag.MAX_ID_LENGTH)
        String id,

        @Size(max = RecordTag.MAX_ID_LENGTH)
        String recordId,

        @Size(max = RecordTag.MAX_ID_LENGTH)
        String nodeCode,

        double confidence,

        TagOrigin origin,

        Instant confirmedAt,

        boolean discarded
) {

    /**
     * 三个 id 类字段的共同上限。
     *
     * <p>64 是跟着 {@code CreateRecordRequest.nodeCode} 的 {@code @Size(max = 64)} 与
     * {@link Touch#MAX_CLIENT_TOKEN_LENGTH} 走的 —— 全仓库的「这是个 id」都用同一个数量级。
     * 它的作用不是精确,是<b>把「放个 id」和「放段内容」分在两边</b>(R-01)。
     * <p>
     * 注解与下面构造器里的检查引用同一个常量:注解是形状声明(这个 record 不过 Validator),
     * 真正拦得住的是构造器那几行。两者共用一个数,所以不会出现「注解说 64、代码放 200」。
     */
    public static final int MAX_ID_LENGTH = 64;

    /**
     * 手动标签的置信度 —— <b>恒为 1.0</b>。
     *
     * <p>不是「我们有多确定」,是「这条根本不是猜出来的」。写死它有一个具体作用:
     * 一个带着 0.83 分的 {@code MANUAL} 标签只可能来自一次识别结果被换了 origin 存进来,
     * 而那正是 {@link TagOrigin} 那段「准确率再也算不出来」在防的事。构造器会当场拒掉。
     */
    public static final double MANUAL_CONFIDENCE = 1.0;

    /**
     * 主标签的 id 前缀。
     *
     * <h2>为什么主标签的 id 是推出来的,不是随机签发的</h2>
     *
     * 主标签是采集那一刻就成立的事实({@link Touch#nodeCode()}),它<b>不需要存一行也存在</b>
     * (见 {@link #effectiveTagsOf})。既然它可以不存在于库里,它的 id 就不能来自一次库写入 ——
     * 否则「用户要丢弃主标签」这个动作会先需要一个 id,而那个 id 只有写过库才有。
     * <p>
     * 推出来的 id 让两侧对得上:界面上永远拿得到 {@code primary-<recordId>},
     * 服务端拿它去库里找,找不到就现推一条。
     */
    public static final String PRIMARY_ID_PREFIX = "primary-";

    public RecordTag {
        id = requireId(id, "标签 id");
        recordId = requireId(recordId, "记录 id");
        nodeCode = requireId(nodeCode, "考点 code");

        if (origin == null) {
            throw new IllegalArgumentException("标签必须说明它从哪来 —— origin 是来源,不是状态");
        }

        // 🔴 NaN 单独挡一次,理由与 RecognitionResult 的构造器逐字相同(R-72):
        // 它跟任何数比较都为 false,于是范围校验和阈值裁决会一起放它过去,变成一次「高置信度命中」。
        // 这里再挡一遍不是重复:识别结果是一次调用的产物,标签是【落进库里的那一行】——
        // 前者错了下次重来,后者错了会一直被数进覆盖度。
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("置信度必须是 0~1 的实数:" + confidence);
        }
        if (origin == TagOrigin.MANUAL && confidence != MANUAL_CONFIDENCE) {
            throw new IllegalArgumentException(
                    "手动标签不存在「有多确定」,置信度只能是 " + MANUAL_CONFIDENCE + ":" + confidence
                            + " —— 带着模型分的手动标签,只可能是一次识别结果被换了 origin 存进来");
        }
    }

    /** 主标签的 id —— 由记录 id 推出,不签发。 */
    public static String primaryIdOf(String recordId) {
        return PRIMARY_ID_PREFIX + requireId(recordId, "记录 id");
    }

    /**
     * 采集那一刻就成立的那条标签。
     *
     * <p>{@code confirmedAt} 直接取记录的发生时刻:用户在采集时<b>亲手从树里挑了这个考点</b>,
     * 那一下就是确认本身,不该再要求他事后对自己刚挑的东西点一次「确认」。
     *
     * <p>{@code origin} 恒为 {@link TagOrigin#MANUAL},<b>今天这仍然是对的</b>,
     * 但理由已经比原先窄了一档,得说清楚是哪一档:
     * <ul>
     *   <li><b>已经不成立的那半句</b>:「{@code origin=auto} 今天没有 HTTP 产出路径」——
     *       docs/技术架构 §6.2 的 {@code POST /records/{id}/image} 已落地
     *       ({@code RecognitionController#recognizePhotos} → {@link TaggingService#suggest}),
     *       命中时会<b>真的往库里落一行 {@code TagOrigin#AUTO}</b>。
     *       但它落的是<b>另一条标签</b>,不是主标签:主标签的 {@code nodeCode} 永远取自
     *       {@link Touch#nodeCode()},而那是用户自己挑的</li>
     *   <li>⚪ <b>仍然悬着的那半句</b>:{@code CaptureService#captureFromPhoto} 的
     *       {@code Mounting.RECOGNIZED} —— 模型挑的考点<b>直接成为 {@code Touch#nodeCode}</b>
     *       的那条路,<b>至今没有 HTTP 端点</b>(现存每条采集路径都要求用户先挑好考点)。
     *       它落地那天,那一支必须真的落一行 origin=auto 的主标签,<b>不能继续走这里</b> ——
     *       否则模型挂上去的考点会被记成手动的,{@code 1.2.5.2} 的准确率口径当场失真。
     *       落点见 {@link RecordTagStore#put}</li>
     * </ul>
     */
    public static RecordTag primaryOf(Touch touch) {
        return new RecordTag(
                primaryIdOf(touch.id()),
                touch.id(),
                touch.nodeCode(),
                MANUAL_CONFIDENCE,
                TagOrigin.MANUAL,
                touch.occurredAt(),
                false);
    }

    /** 这条是不是那条主标签。 */
    public boolean primary() {
        return id.equals(primaryIdOf(recordId));
    }

    /**
     * 确认 —— <b>只写 {@code confirmedAt}</b>。
     *
     * <p>🔴 {@code origin} 从 {@code this} 原样带过去。这不是「记得别改」:
     * 这个方法的签名里<b>没有能传进一个新 origin 的位置</b>,所以调用方连改的机会都没有。
     * docs/技术架构 §6.3 对 confirm 那一行的约束是「<b>不改 origin</b> —— 它是来源不是状态」。
     */
    public RecordTag confirm(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("确认必须有时刻 —— confirmed_at 是这条标签唯一的状态");
        }
        return new RecordTag(id, recordId, nodeCode, confidence, origin, at, discarded);
    }

    /**
     * 丢弃 —— 置 {@code discarded}。<b>可见,但不计覆盖度</b>({@code P1-7})。
     *
     * <p>{@code confirmedAt} 不清空:「我确认过,后来又觉得不对」是一段真实经过,
     * 抹掉它等于让这条标签装成从没被确认过。同理 {@code origin} 也原样带过去。
     */
    public RecordTag discard() {
        return new RecordTag(id, recordId, nodeCode, confidence, origin, confirmedAt, true);
    }

    /**
     * 这条标签算不算进覆盖度。
     *
     * <h2>判据只有 {@code discarded},没有 {@code confirmedAt}</h2>
     *
     * docs/技术架构 §6.4 的原文是「分子 = <b>{@code discarded=0}</b> 的触达节点数」。
     * 一条过了阈值、过了出口自检的自动标签,哪怕用户还没点过确认,也已经是一次分类 ——
     * 把「没点确认」也算成不覆盖,等于要求用户对每一条自动标签点一次才承认他学过,
     * 而那会让覆盖率变成「点击率」。
     * <p>
     * 反过来,{@code confirm} 那一行契约说的「计入覆盖度」不是新增了一个条件,
     * 是在陈述<b>确认不会让它掉出覆盖度</b> —— 与丢弃相对。
     */
    public boolean countsInCoverage() {
        return !discarded;
    }

    /**
     * 一条记录当前的全部标签 = <b>那条主标签 + 库里存着的其余标签</b>。
     *
     * <h2>为什么主标签是推出来的,而不是采集时写一行</h2>
     *
     * 采集时多写一行是最自然的写法,但它有一个不报错的失败模式:<b>任何一条没配上标签行的记录
     * 会静默地从覆盖度里消失</b>。而这样的记录一定会出现 —— 种子数据、历史数据、
     * 以及任何一条绕过采集服务直接落库的记录。用户看到的是覆盖率无缘无故掉了几格,
     * 没有一行日志、没有一次报错。
     * <p>
     * 推出来的方向相反:<b>没配上行的记录照常计数</b>,失败方向是「多算」不是「少算」。
     * 这与 {@link Touch} 构造器里那句「判重的失败方向只能是多一条,不能是少一条 ——
     * 多一条用户看得见、删得掉,少一条是他记了却没记上,而他不会知道」是同一条纪律。
     *
     * <h2>库里那一行覆盖的只有「状态」,落点仍然跟着记录走</h2>
     *
     * 存下来的主标签行只贡献 {@code confidence / origin / confirmedAt / discarded};
     * {@code nodeCode} <b>永远取自 {@link Touch#nodeCode()}</b>。
     * 否则一次改挂({@code TouchStore.reassign} —— 删考点前把记录搬走的那条路)之后,
     * 记录挂在新考点上、而它的主标签还指着旧考点,覆盖度会算到一个用户已经搬离的格子里。
     *
     * @param touch  这条记录
     * @param stored 库里属于这条记录的标签行,可以为空
     */
    public static List<RecordTag> effectiveTagsOf(Touch touch, List<RecordTag> stored) {
        String primaryId = primaryIdOf(touch.id());
        List<RecordTag> result = new ArrayList<>();

        RecordTag storedPrimary = null;
        if (stored != null) {
            for (RecordTag tag : stored) {
                if (tag.id().equals(primaryId)) {
                    storedPrimary = tag;
                    break;
                }
            }
        }

        result.add(storedPrimary == null
                ? primaryOf(touch)
                : new RecordTag(primaryId, touch.id(), touch.nodeCode(),
                        storedPrimary.confidence(), storedPrimary.origin(),
                        storedPrimary.confirmedAt(), storedPrimary.discarded()));

        if (stored != null) {
            for (RecordTag tag : stored) {
                if (!tag.id().equals(primaryId)) {
                    result.add(tag);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * 全库的有效标签,<b>按记录顺序摊平</b> —— 覆盖度的输入。
     *
     * <p>顺序要紧:{@code CoverageService} 用它算「最近一次触达」与来源名集合,
     * 而来源名集合是<b>按首次出现顺序</b>出接口的。换个遍历顺序,界面上那一列的次序就变了,
     * 而且不会有任何一条断言红。
     *
     * @param touches   行为层全部记录,按发生时间升序({@code TouchStore.findAll} 的契约)
     * @param allStored 库里的全部标签行,可以为空
     */
    public static List<RecordTag> effectiveTagsOf(List<Touch> touches, List<RecordTag> allStored) {
        Map<String, List<RecordTag>> byRecord = new LinkedHashMap<>();
        if (allStored != null) {
            for (RecordTag tag : allStored) {
                byRecord.computeIfAbsent(tag.recordId(), k -> new ArrayList<>()).add(tag);
            }
        }
        List<RecordTag> result = new ArrayList<>();
        for (Touch touch : touches) {
            result.addAll(effectiveTagsOf(touch, byRecord.get(touch.id())));
        }
        return List.copyOf(result);
    }

    private static String requireId(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + "不能为空");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(
                    what + "最长 " + MAX_ID_LENGTH + " 个字符 —— 它是个 id,不是放内容的地方:" + value.length());
        }
        return value;
    }
}
