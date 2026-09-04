package com.kaodian.server.tagging;

import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.RecordTagStore;
import com.kaodian.server.collect.TagOrigin;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.recognize.RecognitionResult;
import com.kaodian.server.recognize.RecognitionUnavailableException;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusSource;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
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
 * 召回有候选,但没有东西可送。它返回 {@link TagAttempt.Outcome#NO_MATERIAL},<b>不调模型</b> ——
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
    private final TagAttemptStore attempts;
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
     *
     * <p>🔴 <b>{@link ModelCallGate} 不在这里注入</b>({@code M2} §2.5):它由调用方构造好之后
     * 作为 {@link #suggest} 的入参传进来。注入它等于让领域层去查找一个实现,
     * 而那个实现在 {@code app} 里 —— {@code domain → app} 那条边就是这么建出来的。
     */
    public TaggingService(TouchStore touches, RecordTagStore tags, TagAttemptStore attempts,
                          SyllabusSource syllabus, CandidateRecall recall,
                          VisionTagger visionTagger, Clock clock) {
        this.touches = touches;
        this.tags = tags;
        this.attempts = attempts;
        this.syllabus = syllabus;
        this.recall = recall;
        this.visionTagger = visionTagger;
        this.clock = clock;
    }

    /**
     * 一次补标的结果。
     *
     * <p>🔴 <b>成因枚举只有一份,在 {@link TagAttempt.Outcome} 上</b>({@code M2} §4.2)。
     * 它原来是这个类上的一个内嵌枚举,只是一次方法调用的返回值 —— <b>一落地就没了</b>;
     * 进程重启之后「这条为什么没对上」在库里查不出答案,而四张空态各要说一句不同的话。
     * 现在它跟着 {@link TagAttempt} 落库,<b>这里不再各写一版</b>。
     *
     * <p>{@code confidence} 在 {@code NO_MATCH} 时仍然带着值,与
     * {@code RecognitionResult.noMatch(confidence)} 同一个理由:
     * 「0.42 分被阈值丢掉」和「什么都没认出来」对产品的含义完全相反 ——
     * 前者说明候选召回漏了东西,后者说明图糊了。压成同一个数,这条排查线索就没了。
     *
     * <p>🔴 {@code confidence} <b>不上 wire</b>({@code M2} §9.1):阈值裁决在服务端做完了,
     * 端上没有任何理由拿到它 —— 拿到了就会有人在端上「稍微放宽一点」。
     *
     * @param tag            落下的那条标签;只有 {@code SUGGESTED} 与 {@code ALREADY_TAGGED} 时非空
     * @param candidates     🔴 <b>本次候选集全集</b>,两种形态下都要带回去 ——
     *                       端靠它自行判定 {@code selectedNodeId} 在不在集内({@code I-3} 的第二道校验)
     */
    public record Suggestion(TagAttempt.Outcome outcome, RecordTag tag, double confidence,
                             List<VisionTagger.Candidate> candidates) {

        public Suggestion {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /** 这次召回出了几个候选。<b>0 就是没调模型</b>。 */
        public int candidateCount() {
            return candidates.size();
        }
    }

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

    /**
     * 恢复的结果。三档,<b>后两档在界面上走同一个分支但码不合并</b>({@code 接口契约} §4.2):
     * 「这条标签没了」和「这个考点归档了」下一步都是「重新挑一个」,
     * 但前者是我们这边的行不见了,后者是骨架变了 —— 两件事各自会以不同的方式变多。
     */
    public sealed interface RestoreResult {

        /** 置回成功,状态恒为 {@code TS-02}。<b>恢复的终点只有一个</b>。 */
        record Restored(RecordTag tag) implements RestoreResult {}

        /** 标签行已不存在。 */
        record NotFound() implements RestoreResult {}

        /** 标签还在,但它指的考点已归档 / 失效。 */
        record NodeArchived() implements RestoreResult {}
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
     * 触发一次闭集分类 —— {@code M2-打标管线与模型接入} §二 的四段,从上到下走一遍。
     *
     * <h2>🔴 这个方法签名里没有任何「调用方指定的标签文本」</h2>
     *
     * 候选<b>只能</b>从 {@link CandidateRecall} 出来,参数表里没有 {@code List<Candidate>}、
     * 没有 {@code hint}、没有 {@code label}。没有这个位置,「让前端传几个候选进来省一次召回」
     * 这条路就不存在。
     *
     * <h2>🔴 四段里有三段的作用是「丢掉」,而许可只在第 ② 段之前拿一次</h2>
     *
     * <table border="1">
     *   <caption>许可在哪一段动、失败退不退({@code M2} §2.4)</caption>
     *   <tr><th>走到哪</th><th>许可</th><th>为什么</th></tr>
     *   <tr><td>骨架未建好 / 召回为空 / 没有素材</td><td><b>一次都不动</b></td>
     *       <td>压根没打算发起外部调用({@code I-4})</td></tr>
     *   <tr><td>拿不到许可</td><td>未拿到</td>
     *       <td>记录照样成立,这条不打标 —— 🔴 <b>不进自动重试队列</b></td></tr>
     *   <tr><td>抛 {@link RecognitionUnavailableException}</td><td><b>退</b></td>
     *       <td>压根没看成。不退的话这条重试三次,用户为一次没成功的识别付四次钱</td></tr>
     *   <tr><td>无匹配 / 低于阈值 / 出口自检降级</td><td>🔴 <b>不退</b></td>
     *       <td>模型<b>真的看了</b>,外部账单已经产生。退它等于让「宁可丢弃率高」变成免费的</td></tr>
     * </table>
     *
     * <p><b>恒等式(可测)</b>:{@code 净扣减次数 == 未退回的外部调用次数 ==
     * {SUGGESTED, ALREADY_TAGGED, NO_MATCH} 三种结局之和}。
     *
     * <h2>🔴 出口是「待确认」,不是「已确认」</h2>
     *
     * 命中时落下的那条标签 {@code confirmedAt} 恒为 {@code null}({@code TS-02})。
     * 顺手填上「等于现在」会让准确率口径(标对的/标了的)的分子恒等于分母,
     * 而且它会让一条<b>系统触发</b>的转移把覆盖度抬上去 —— {@code U2.2} §2.4 当场破。
     *
     * <h2>🔴 图片字节只在内存里过一次</h2>
     *
     * 进来、内联送模型、方法返回即释放。<b>不落盘、不进对象存储、不打进任何级别的日志</b>。
     * 这个方法里刻意<b>没有任何请求日志</b> —— 一次 {@code log.debug} 就等于把原图落了盘。
     *
     * @param material 要分类的字节;{@code null} 或空表示<b>服务端没有素材</b>(见类注释),
     *                 此时直接返回 {@link TagAttempt.Outcome#NO_MATERIAL},不调模型
     * @param gate     调外部模型的许可闸,<b>由调用方构造好传进来</b>({@code M2} §2.5)——
     *                 领域层不注入、不查找、不知道实现类名
     */
    public Suggestion suggest(Touch touch, byte[] material, String mimeType, ModelCallGate gate) {
        if (gate == null) {
            throw new IllegalArgumentException(
                    "要调外部模型就必须先问一句能不能 —— 没有闸的那条路不存在(M2 §2.5)");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Syllabus tree = syllabus.current();

        // 骨架一个考点都没有:连候选集都不存在,而且人工出口也没得挑 —— 这一档人工出口要禁用。
        if (tree.nodeCount() == 0) {
            return settle(touch, TagAttempt.Outcome.SYLLABUS_EMPTY, null, 0.0, List.of(), now);
        }

        // ① 候选召回。🔴 空就是空 —— 不回落到整棵树,「调了也只能瞎猜」。许可一次都不动。
        List<VisionTagger.Candidate> candidates = recall.recall(tree, touch.sourceName());
        if (candidates.isEmpty()) {
            return settle(touch, TagAttempt.Outcome.NOT_RECALLED, null, 0.0, candidates, now);
        }
        if (material == null || material.length == 0) {
            return settle(touch, TagAttempt.Outcome.NO_MATERIAL, null, 0.0, candidates, now);
        }

        // 🔴 许可拿在 ② 之前 —— 「决定要发起外部调用」的那一刻。拿在调用之后,
        //    并发下两个请求会同时看到「还可以再来一次」各调一次。
        if (!gate.acquire()) {
            return settle(touch, TagAttempt.Outcome.QUOTA_EXHAUSTED, null, 0.0, candidates, now);
        }

        RecognitionResult recognition;
        try {
            // ②③④ 阈值裁决在实现类里的 RecognitionResult.of,出口自检在这一句。
            //      两者都是接口层的静态方法,这里原样调用 —— 不重写、不绕过。
            recognition = VisionTagger.enforceClosedSet(
                    visionTagger.classify(material, mimeType, candidates), candidates);
        } catch (RecognitionUnavailableException e) {
            // 🔴 压根没看成 → 把那一次许可退回去,并排下一次自动重试。
            //    识别不可用 ≠ 记录失败:记录早就落地了,这里什么都不写、什么都不删。
            gate.release();
            return unavailable(touch, candidates, now);
        }

        if (!recognition.matched()) {
            // 🔴 扣了不退 —— 模型真的看了。「低于阈值」和「答了候选集外的东西」都走这一支。
            return settle(touch, TagAttempt.Outcome.NO_MATCH, null,
                    recognition.confidence(), candidates, now);
        }

        // 已经挂过这个考点就不再挂一条 —— 包括用户之前丢弃过的那条。
        // 🔴 丢弃过的绝不在这里复活:用户已经说过「不是这个」,一次自动识别没有资格推翻它,
        //    否则每补标一次就复活一次,而他不会知道自己丢过的东西又回到了覆盖度里。
        RecordTag existing = tagOn(touch, recognition.nodeCode(), true);
        if (existing != null) {
            return settle(touch, TagAttempt.Outcome.ALREADY_TAGGED, existing,
                    recognition.confidence(), candidates, now);
        }

        RecordTag tag = new RecordTag(
                newTagId(),
                touch.userId(),
                touch.id(),
                recognition.nodeCode(),
                recognition.confidence(),
                TagOrigin.AUTO,
                // 🔴 confirmedAt 留空:这条是模型挑的,还没有人认过。
                null,
                false);
        // 🔴 标签先落,尝试行紧随其后,中间不做任何会抛的事(M2 §10.2)。
        //    分两次写会出现「标签写进去了但 outcome 还停在 RUNNING」——
        //    那条记录会被队列反复捞起来重认,而它其实已经认成了。
        //    换 JDBC 那天这两句变成一个事务,形状不变。
        RecordTag stored = tags.put(tag);
        return settle(touch, TagAttempt.Outcome.SUGGESTED, stored,
                recognition.confidence(), candidates, now);
    }

    /**
     * 恢复一条丢弃过的标签 —— {@code M2} §8.1 / {@code 接口契约} §4.2。
     *
     * <p>🔴 <b>置 {@code discarded=false} 的同时把 {@code confirmedAt} 清成 {@code null}</b>。
     * 不清空的话,一个「曾确认 → 丢弃 → 恢复」的标签会直接落回 {@code TS-03} ——
     * 那是一条<b>系统触发、且终点计覆盖度</b>的转移,而
     * 「没有任何一条系统触发的转移会让覆盖度上升」({@code U2.2} §2.4)当场破。
     * 恢复表达的是「我想再看看」,不是「我确认」;用户还得再点一次。
     *
     * <p>其余语义一个都不动:<b>不触发重新分类、不改 {@code origin}、一次外部模型都不调</b>。
     * 天然幂等 —— 已经是 {@code TS-02} 的标签原样返回,不报错。
     */
    public RestoreResult restore(Touch touch, String tagId) {
        RecordTag target = tagWithId(touch, tagId);
        if (target == null) {
            return new RestoreResult.NotFound();
        }
        // 考点已归档 / id 失效:标签还在,但它指着的那一格已经退出分母了(R-49)。
        // 恢复它会让界面显示「已对上一个不存在的考点」,所以这里拒绝,而不是恢复成一条坏标签。
        if (syllabus.current().node(target.nodeCode()) == null) {
            return new RestoreResult.NodeArchived();
        }
        RecordTag restored = target.restore();
        // 主标签可能还没有实体行(它是推出来的),put 会把它建出来 —— 与 discard 同一条路。
        return new RestoreResult.Restored(tags.put(restored));
    }

    // ---------------------------------------------------------------- 待补队列

    /** 这条记录最近一次打标尝试;{@code null} = 还没触发过({@code TS-00})。 */
    public TagAttempt attemptOf(Touch touch) {
        return attempts.find(touch.userId(), touch.id());
    }

    /**
     * 这条记录此刻的状态 —— 口径全库唯一,在 {@link TagState#of} 上。
     *
     * <p>🔴 {@code M3} 的未分类计数调 {@link TagState#isUnclassified},<b>不自己写谓词</b>。
     */
    public TagState stateOf(Touch touch) {
        return TagState.of(touch, tagsOf(touch), attemptOf(touch), syllabus.current());
    }

    /** 待补队列长度 —— 顶栏那个计数读的就是它。<b>队列内容不提供端点</b>。 */
    public int pendingCount(long userId) {
        return attempts.pendingCount(userId);
    }

    /**
     * 到点该自动重试的那些行 —— <b>驱动方在外面</b>。
     *
     * <p>⚪ <b>今天没有任何东西在驱动它</b>:事后补标手里一份素材都没有({@code M2-G1}),
     * 重试一次的结局只会是同一个 {@code NO_MATERIAL}。所以这一层只提供队列的状态机,
     * <b>不自带调度器</b> —— 造一个每分钟把队列捞一遍、每次都得到同一个结论的定时任务,
     * 是把「这条路今天走不通」这件事藏起来,而不是解决它。
     * 手动重试那个出口一直都在({@code POST …/tags/suggest},后端看不出这次是首次还是重试)。
     */
    public List<TagAttempt> dueForRetry(int limit) {
        return attempts.dueForRetry(clock.instant(), limit);
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
        // 🔴 尝试行跟着一起删。留着它,一条已经不存在的记录会继续排在待补队列里被捞起来重认,
        //    而顶栏那个计数会显示一个用户永远点不到的数字。
        attempts.deleteByRecord(userId, recordId);
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

    /**
     * 落一行「最近一次尝试」,并把结果打包回去。
     *
     * <p>🔴 <b>结局只有这一个出口</b>:六种成因各自 return 一次的写法,加第七种时
     * 一定会有一条路忘了写尝试行 —— 而那条记录会永远停在上一次的结论上。
     */
    private Suggestion settle(Touch touch, TagAttempt.Outcome outcome, RecordTag tag,
                              double confidence, List<VisionTagger.Candidate> candidates, Instant now) {
        attempts.put(TagAttempt.settled(touch.id(), touch.userId(), outcome, now));
        return new Suggestion(outcome, tag, confidence, candidates);
    }

    /** 链路不通 —— 排下一次自动重试(到上限就停在 {@code TS-06},两个人工出口都还在)。 */
    private Suggestion unavailable(Touch touch, List<VisionTagger.Candidate> candidates, Instant now) {
        TagAttempt previous = attempts.find(touch.userId(), touch.id());
        attempts.put(TagAttempt.unavailable(touch.id(), touch.userId(),
                previous != null && previous.outcome() == TagAttempt.Outcome.UNAVAILABLE ? previous : null,
                now));
        return new Suggestion(TagAttempt.Outcome.UNAVAILABLE, null, 0.0, candidates);
    }

    /** 🔴 前缀不是 {@code primary-} —— 那个前缀专属于推出来的主标签,签发的 id 不许撞上它。 */
    private static String newTagId() {
        return "tag-" + UUID.randomUUID();
    }
}
