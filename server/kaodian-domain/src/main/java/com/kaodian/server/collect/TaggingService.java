package com.kaodian.server.collect;

import com.kaodian.server.recognize.RecognitionResult;
import com.kaodian.server.recognize.RecognitionUnavailableException;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.SyllabusSource;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 打标管线的调用方 —— docs/technical/后端系统设计与组件接入.md §1.3 那张图从上到下走一遍。
 *
 * <h2>🔴 这个类是调用方,不是裁决者</h2>
 *
 * 阈值裁决({@link RecognitionResult#of})与出口自检({@link VisionTagger#enforceClosedSet})
 * 都写在<b>接口层的静态方法</b>上,理由是 docs/technical/后端系统设计与组件接入.md §1.3 那句:「换厂商换的是实现类,
 * 换不掉这条线」。所以这里<b>原样调用它们,一行都不重写</b> ——
 * 在这里补一个「顺手放宽一点」的判断,等于把红线从接口层搬到了一个业务类里,
 * 而 docs/data/识别链路选型.md 坑三要的切换点就顺带把红线也切换掉了。
 *
 * <h2>四段里有三段的作用是「丢掉」</h2>
 *
 * <ol>
 *   <li><b>候选召回</b>({@link CandidateRecall}) —— 召回为空就<b>不调模型</b>。
 *       「调了也只能瞎猜」</li>
 *   <li><b>阈值裁决</b>({@code RecognitionResult.of},在实现类里) —— 低于阈值 → NO_MATCH,
 *       不硬凑最接近的考点</li>
 *   <li><b>出口自检</b>({@code enforceClosedSet}) —— code 不在候选集里一律降级 NO_MATCH</li>
 * </ol>
 * 这不是保守,是 决策记录 §2.2 宁缺毋滥的技术形态:<b>覆盖度失真的话,这个产品就没有指标了。</b>
 *
 * <h2>⚪ 今天没有可以送进模型的素材,而这不是实现偷懒</h2>
 *
 * {@link #suggest} 的 {@code material} 是图片/音频字节。<b>服务端一份都没有留</b> ——
 * 原图内联送一次即弃(决策记录 §2.3 / docs/data/识别链路选型.md 坑二),转写文本用完即弃,
 * {@link Touch} 结构上就没有能装下它们的字段。
 * <p>
 * 于是 {@code POST /records/{id}/tags/suggest} 这条<b>事后补标</b>的路今天走到第 ② 段就停:
 * 召回有候选,但没有东西可送。它返回 {@link Outcome#NO_MATERIAL},<b>不调模型</b> ——
 * 拿零字节去调一次视觉模型是「假装成功」的另一种写法,而假装成功比诚实失败危险得多
 * ({@code StubVisionTagger} 的类注释写的就是这件事)。
 * <p>
 * 完整那条路(带着字节走完四段)在这个方法里是实现好的,而且<b>现在有 HTTP 入口了</b>:
 * docs/technical/INDEX.md §6.2 的 {@code POST /records/{id}/image} 已落地
 * ({@code RecognitionController#recognizePhotos}),它把这次上传的原图字节递进来,
 * 命中时<b>真的落一行 {@code origin=auto} 的标签</b>。
 * <p>
 * 也就是说 ⚪ 那一段说的是 <b>{@code /tags/suggest} 这一条路</b>:事后补标手里没有素材,
 * 而那不是实现偷懒,是红线的直接后果 —— 服务端一份都没留。
 * 契约层面的缺口已在交付说明里报出,本轮不自行改契约。
 */
@Service
public class TaggingService {

    private final TouchStore touches;
    private final RecordTagStore tags;
    private final SyllabusSource syllabus;
    private final CandidateRecall recall;
    private final VisionTagger visionTagger;
    private final Clock clock;

    /**
     * 🔴 注入的是 {@link SyllabusSource} 而不是一棵 {@code Syllabus} ——
     * 与 {@code CaptureService} / {@code CoverageReader} 同一条:骨架层可写,
     * 持有一棵 record 树等于持有进程启动那一刻的快照,用户刚建的考点会被判成「不在树里」,
     * <b>而且不会报错</b>。
     *
     * <p>时钟同理是注入的:{@code confirmed_at} 是这条标签唯一的状态,
     * 散落的 {@code Instant.now()} 会让「确认之后覆盖度怎么变」没法回放。
     */
    public TaggingService(TouchStore touches, RecordTagStore tags, SyllabusSource syllabus,
                          CandidateRecall recall, VisionTagger visionTagger, Clock clock) {
        this.touches = touches;
        this.tags = tags;
        this.syllabus = syllabus;
        this.recall = recall;
        this.visionTagger = visionTagger;
        this.clock = clock;
    }

    /** 一次补标走到了哪一步。<b>五种情况在界面上该说的下一步完全不同,所以不能合成一个。</b> */
    public enum Outcome {

        /** 落了一条 {@code origin=auto} 的标签。界面:显示考点名 + 一个「确认 / 丢弃」。 */
        SUGGESTED,

        /** 这个考点已经挂在这条记录上了(可能是之前丢弃过的那条)。界面:不重复挂,把已有那条指出来。 */
        ALREADY_TAGGED,

        /**
         * 候选召回为空 —— <b>没调模型</b>。界面:自己从树里挑一个。
         *
         * <p>与 {@link #NO_MATCH} 必须分开:那是「模型看了说不像」,这是「压根没送进去看」。
         * 前者说明这条记录可能真的对不上任何考点,后者说明<b>来源名里没有可用线索</b>,
         * 是我们这一侧的信息不够,不是用户记得不好。
         */
        NOT_RECALLED,

        /**
         * 有候选,但服务端手里<b>没有可送进模型的素材</b> —— 没调模型。界面:自己从树里挑一个。
         *
         * <p>见类注释。这一支今天是 {@code /tags/suggest} 的常态,它诚实地说明原因,
         * 而不是伪装成「模型没认出来」。
         */
        NO_MATERIAL,

        /** 模型看了,说不匹配(或低于阈值、或答了候选集之外的东西)。界面:自己从树里挑一个。 */
        NO_MATCH,

        /** 模型压根没跑成(没配密钥/超时/限流)。界面:稍后重试,或自己从树里挑一个。 */
        UNAVAILABLE
    }

    /**
     * 一次补标的结果。
     *
     * <p>{@code confidence} 在 {@link Outcome#NO_MATCH} 时仍然带着值,与
     * {@code RecognitionResult.noMatch(confidence)} 同一个理由:
     * 「0.42 分被阈值丢掉」和「什么都没认出来」对产品的含义完全相反 ——
     * 前者说明候选召回漏了东西,后者说明图糊了。压成同一个数,这条排查线索就没了。
     *
     * @param tag            落下的那条标签;只有 {@link Outcome#SUGGESTED} 与
     *                       {@link Outcome#ALREADY_TAGGED} 时非空
     * @param candidateCount 这次召回出了几个候选。<b>0 就是没调模型</b>
     */
    public record Suggestion(Outcome outcome, RecordTag tag, double confidence, int candidateCount) {}

    /** 手动挂载的结果。用 sealed 是为了让调用方<b>必须</b>把「这个考点不在树里」处理掉。 */
    public sealed interface MountResult {

        /**
         * @param created {@code false} 表示这个考点本来就挂着,<b>这一次什么都没新建</b> ——
         *                与 {@code CaptureResult.Recorded.replayed} 同一个用法:
         *                调用方据此把 201 降成 200,而不是当成一次新挂载去邀功
         */
        record Mounted(RecordTag tag, boolean created) implements MountResult {}

        /** 🔴 给的 code 不在(未归档的)骨架树里。<b>不模糊匹配、不取最接近的、不新建节点</b>(R-07)。 */
        record NotInSyllabus() implements MountResult {}
    }

    // ---------------------------------------------------------------- 读

    /**
     * 按 id 找这个用户的一条记录;没有返回 {@code null}。
     *
     * <h2>🔴 {@code userId} 从参数进来,而且它是这一层唯一的归属来源</h2>
     *
     * 拿到记录之后,下面那些写方法(挂载 / 确认 / 丢弃 / 补标)全部从
     * {@code touch.userId()} 取归属 —— <b>不再要第二个 userId 参数</b>。
     * 要第二个,就等于在每个方法上多一个「这两个值对不上时听谁的」要回答;
     * 而这条记录本身已经是答案:它是从这个用户名下查出来的。
     */
    public Touch findRecord(long userId, String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return null;
        }
        return touches.findAll(userId).stream()
                .filter(t -> t.id().equals(recordId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 一条记录当前的全部标签 —— 采集那一刻的主标签 + 库里存着的那些。
     *
     * <p>口径在 {@link RecordTag#effectiveTagsOf} 上,<b>这里只是把库查出来递过去</b>。
     * 派生规则只写一处:两处推同一条主标签,就一定会推出两条不一样的。
     */
    public List<RecordTag> tagsOf(Touch touch) {
        return RecordTag.effectiveTagsOf(touch, tags.findByRecord(touch.userId(), touch.id()));
    }

    // ---------------------------------------------------------------- 写

    /**
     * 触发一次闭集分类 —— docs/technical/后端系统设计与组件接入.md §1.3 的四段。
     *
     * <h2>🔴 这个方法签名里没有任何「调用方指定的标签文本」</h2>
     *
     * docs/technical/INDEX.md §6.3:「<b>请求体不接受调用方指定标签文本。</b>候选由服务端召回」。
     * 落到这里就是:候选<b>只能</b>从 {@link CandidateRecall} 出来,
     * 参数表里没有 {@code List<Candidate>}、没有 {@code hint}、没有 {@code label}。
     * 没有这个位置,「让前端传几个候选进来省一次召回」这条路就不存在。
     *
     * <h2>🔴 图片字节只在内存里过一次</h2>
     *
     * 与 {@code CaptureService#captureFromPhoto} 同一条:进来、内联送模型、方法返回即释放。
     * <b>不落盘、不进对象存储、不打进任何级别的日志</b>(docs/technical/INDEX.md §8.1 五条禁令)。
     * 这个方法里刻意<b>没有任何请求日志</b> —— 一次 {@code log.debug} 就等于把原图落了盘。
     *
     * @param material 要分类的字节;{@code null} 或空表示<b>服务端没有素材</b>(见类注释),
     *                 此时直接返回 {@link Outcome#NO_MATERIAL},不调模型
     */
    public Suggestion suggest(Touch touch, byte[] material, String mimeType) {
        // ① 候选召回。🔴 空就是空 —— 不回落到整棵树,「调了也只能瞎猜」(docs/technical/后端系统设计与组件接入.md §1.3)
        List<VisionTagger.Candidate> candidates = recall.recall(syllabus.current(), touch.sourceName());
        if (candidates.isEmpty()) {
            return new Suggestion(Outcome.NOT_RECALLED, null, 0.0, 0);
        }
        if (material == null || material.length == 0) {
            return new Suggestion(Outcome.NO_MATERIAL, null, 0.0, candidates.size());
        }

        RecognitionResult recognition;
        try {
            // ②③ 阈值裁决在实现类里的 RecognitionResult.of,出口自检在这一句。
            //     两者都是接口层的静态方法,这里原样调用 —— 不重写、不绕过。
            recognition = VisionTagger.enforceClosedSet(
                    visionTagger.classify(material, mimeType, candidates), candidates);
        } catch (RecognitionUnavailableException e) {
            // 🔴 识别不可用 ≠ 记录失败(docs/execution/INDEX.md §1.3.7.1)。记录早就落地了,
            //    这里什么都不写、什么都不删,用户照样能手动挂载。
            return new Suggestion(Outcome.UNAVAILABLE, null, 0.0, candidates.size());
        }

        if (!recognition.matched()) {
            return new Suggestion(Outcome.NO_MATCH, null, recognition.confidence(), candidates.size());
        }

        // 已经挂过这个考点就不再挂一条 —— 包括用户之前丢弃过的那条。
        // 🔴 丢弃过的绝不在这里复活:用户已经说过「不是这个」,一次自动识别没有资格推翻它,
        //    否则每补标一次就复活一次,而他不会知道自己丢过的东西又回到了覆盖度里。
        RecordTag existing = tagOn(touch, recognition.nodeCode(), true);
        if (existing != null) {
            return new Suggestion(Outcome.ALREADY_TAGGED, existing,
                    recognition.confidence(), candidates.size());
        }

        RecordTag tag = new RecordTag(
                newTagId(),
                touch.userId(),          // 归属跟着宿主记录走,调用方给不了第二个答案
                touch.id(),
                recognition.nodeCode(),
                recognition.confidence(),
                TagOrigin.AUTO,
                // 🔴 confirmedAt 留空:这条是模型挑的,还没有人认过。
                //    顺手填上「等于现在」会让 1.2.5.2 的准确率口径(标对的/标了的)分子恒等于分母。
                null,
                false);
        return new Suggestion(Outcome.SUGGESTED, tags.put(tag),
                recognition.confidence(), candidates.size());
    }

    /**
     * 手动挂一个考点 —— docs/technical/INDEX.md §6.3「body <b>只接受 {@code nodeId}</b>,不接受 {@code name}。
     * 从树里选,不能新建。」
     *
     * <p>校验只有一条,和 {@code CaptureService#mountAndAppend} 是同一条:
     * <b>这个 code 必须真的在(未归档的)树里</b>。{@code Syllabus#node} 查不到归档的考点,
     * 所以归档的考点也挂不上新标签 —— 归档的意思正是「这个考点不再使用了」。
     *
     * <p>{@code confirmedAt} 当场就写上:用户<b>亲手从树里挑了这个考点</b>,那一下就是确认本身,
     * 再要求他对自己刚挑的东西点一次「确认」是没有意义的仪式。
     */
    public MountResult mount(Touch touch, String nodeCode) {
        if (nodeCode == null || nodeCode.isBlank() || syllabus.current().node(nodeCode) == null) {
            return new MountResult.NotInSyllabus();
        }

        // 幂等:同一个考点重复挂 → 返回原来那条,不新建。
        // 这里只认【没被丢弃的】那条:丢弃过之后用户又亲手挂了一次,是一次明确的改主意,
        // 该给他一条新的、干净的标签,而不是把那条丢弃记录悄悄翻过来 ——
        // 「我曾经把它丢掉过」这件事得留着,否则同一个错标会被反复建议而他不知道自己丢过。
        RecordTag existing = tagOn(touch, nodeCode, false);
        if (existing != null) {
            return new MountResult.Mounted(existing, false);
        }

        RecordTag tag = new RecordTag(
                newTagId(),
                touch.userId(),
                touch.id(),
                nodeCode,
                RecordTag.MANUAL_CONFIDENCE,
                TagOrigin.MANUAL,
                clock.instant().truncatedTo(ChronoUnit.MILLIS),
                false);
        return new MountResult.Mounted(tags.put(tag), true);
    }

    /**
     * 确认一条标签 —— <b>只写 {@code confirmed_at}</b>(docs/technical/INDEX.md §6.3)。
     *
     * <p>🔴 不改 {@code origin}:变更由 {@link RecordTag#confirm} 完成,
     * 而那个方法的签名里没有能传进新 origin 的位置;{@link RecordTagStore#put} 在写入侧再核一遍。
     *
     * @return 确认之后的那条;{@code tagId} 不属于这条记录时返回 {@code null}
     */
    public RecordTag confirm(Touch touch, String tagId) {
        RecordTag target = tagWithId(touch, tagId);
        return target == null ? null : tags.put(target.confirm(clock.instant().truncatedTo(ChronoUnit.MILLIS)));
    }

    /**
     * 丢弃一条标签 —— 置 {@code discarded}。<b>可见,但不计覆盖度</b>({@code P1-7})。
     *
     * <p>丢弃主标签是允许的,而且是有意的:识别或采集时挂错了考点,用户得有办法说「不是这个」
     * 而<b>不必删掉整条记录</b>。删记录会把「我那天学过东西」这件事一起抹掉,
     * 而错的只是它挂在哪儿。
     *
     * @return 丢弃之后的那条;{@code tagId} 不属于这条记录时返回 {@code null}
     */
    public RecordTag discard(Touch touch, String tagId) {
        RecordTag target = tagWithId(touch, tagId);
        return target == null ? null : tags.put(target.discard());
    }

    /**
     * 级联删标签 —— docs/technical/INDEX.md §6.2 {@code DELETE /records/{id}} 的另一半。
     *
     * @return 删掉了几行
     */
    public int deleteTagsOf(long userId, String recordId) {
        return tags.deleteByRecord(userId, recordId);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 这条记录在某个考点上的标签。
     *
     * @param includeDiscarded {@code true} 时连丢弃过的也算 —— 用于「别重复挂」这类判断
     */
    private RecordTag tagOn(Touch touch, String nodeCode, boolean includeDiscarded) {
        for (RecordTag tag : tagsOf(touch)) {
            if (tag.nodeCode().equals(nodeCode) && (includeDiscarded || !tag.discarded())) {
                return tag;
            }
        }
        return null;
    }

    /**
     * 按 id 找这条记录名下的标签。
     *
     * <p>🔴 <b>先按记录取全集,再在里面找 id</b>,不是直接拿 id 查库。
     * 直接查库的写法会让「拿着别人记录的 tagId 来确认」成功一次 ——
     * 今天是单用户所以看不出区别,而多用户是已经排好期的事(docs/technical/INDEX.md §7)。
     * 顺带,主标签本来就不在库里,直接查库压根找不到它。
     */
    private RecordTag tagWithId(Touch touch, String tagId) {
        if (tagId == null || tagId.isBlank()) {
            return null;
        }
        for (RecordTag tag : tagsOf(touch)) {
            if (tag.id().equals(tagId)) {
                return tag;
            }
        }
        return null;
    }

    /** 🔴 前缀不是 {@code primary-} —— 那个前缀专属于推出来的主标签,签发的 id 不许撞上它。 */
    private static String newTagId() {
        return "tag-" + UUID.randomUUID();
    }
}
