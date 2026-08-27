package com.kaodian.server.api;

import com.kaodian.server.api.dto.MountTagRequest;
import com.kaodian.server.api.dto.NodeDetailDto;
import com.kaodian.server.api.dto.RecordTagsResponse;
import com.kaodian.server.api.dto.SuggestTagRequest;
import com.kaodian.server.api.dto.SuggestTagResponse;
import com.kaodian.server.api.dto.SummaryDto;
import com.kaodian.server.api.dto.TagDto;
import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.TaggingService;
import com.kaodian.server.collect.TaggingService.MountResult;
import com.kaodian.server.collect.TaggingService.Suggestion;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.syllabus.Syllabus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 打标 —— docs/10 §6.3 那张表的四个端点。
 *
 * <h2>🔴 四个端点里没有一个能收下一段标签文字</h2>
 *
 * 这是 R-07 在接口层的全部实现,逐个端点看:
 * <table border="1">
 *   <caption>四个端点的入口形状</caption>
 *   <tr><th>端点</th><th>能送进来的东西</th></tr>
 *   <tr><td>{@code POST /tags/suggest}</td>
 *       <td><b>什么都送不进来</b> —— {@link SuggestTagRequest} 一个分量都没有。
 *           候选由服务端召回({@code CandidateRecall}),调用方连指定候选的位置都没有</td></tr>
 *   <tr><td>{@code POST /tags}</td>
 *       <td>只有一个 {@code nodeCode},而且必须在树里查得到。<b>从树里选,不能新建</b></td></tr>
 *   <tr><td>{@code POST /tags/{tagId}/confirm}</td>
 *       <td>只有路径上那个 id。🔴 <b>不改 {@code origin}</b> —— 它是来源不是状态</td></tr>
 *   <tr><td>{@code POST /tags/{tagId}/discard}</td>
 *       <td>同上。置 {@code discarded},<b>可见但不计覆盖度</b></td></tr>
 * </table>
 * 「只要 API 上没有传入自由文本标签的通道,自由生成的考点就进不了库 ——
 * 无论模型输出什么」(docs/10 §6.3)。
 *
 * <h2>控制器不自己写标签,一律走 {@link TaggingService}</h2>
 *
 * 与 {@code RecordController} 委托给 {@code CaptureService} 同一条:
 * 红线是逐条挂在写入路径上的(考点校验、origin 不可变、丢弃过的不复活),
 * 出现第二条写入路径,迟早只有一条被更新。
 *
 * <h2>🔴 报错消息里不回显任何路径变量</h2>
 *
 * {@code id} 与 {@code tagId} 都没有长度上限,能塞满一整个请求行,而报错消息会同时进
 * 响应体和服务端日志。它们是<b>客户端自己刚发过来的</b>,回显一个字的信息都不增加,
 * 却凭空开了一条「往日志里写一整段题干」的路。定位靠 traceId ——
 * 与 {@code RecordController#delete} 那段是同一条纪律。
 */
@RestController
@RequestMapping("/api/records/{id}/tags")
public class TagController {

    private final TaggingService tagging;
    private final CoverageReader reader;

    public TagController(TaggingService tagging, CoverageReader reader) {
        this.tagging = tagging;
        this.reader = reader;
    }

    /**
     * 触发一次闭集分类(docs/13 §1.3 的四段)。
     *
     * <h2>🔴 全部结局都是 200,理由写在 {@link SuggestTagResponse} 上</h2>
     *
     * 一句话:<b>记录早就落地了,补标失败什么都没损坏</b>。回 503 会让前端把它当成一次失败去重试,
     * 而它没有失败 —— 它只是这次没认出来,用户随时可以手动挂一个。
     *
     * <h2>⚪ {@code material} 传的是 {@code null},这不是没写完</h2>
     *
     * 服务端手里<b>一份可送进模型的素材都没有</b>:原图内联送一次即弃(01 §2.3 / docs/09 坑二),
     * 转写文本用完即弃,{@code Touch} 结构上没有能装下它们的字段(01 §2.2 不碰内容)。
     * 拿零字节去调一次视觉模型是「假装成功」的另一种写法,所以这里明确地不给素材,
     * 由 {@code TaggingService} 回一个 {@code NO_MATERIAL} 说清原因。
     * <p>
     * 带着字节走完四段的那条路在 {@code TaggingService.suggest} 里<b>是实现好的</b>,
     * 只是还没有 HTTP 入口 —— docs/10 §6.2 的 {@code POST /records/{id}/image} 落地那天接上即可。
     * 契约层面的缺口(§6.3 的 suggest 依赖 §5.2 的 {@code extracted_text},
     * 而那个字段与本仓库的红线冲突)已在交付说明里报出,本轮不自行改契约。
     *
     * @param body 可以整个不传;<b>传了就必须是个空对象</b> ——
     *             里面出现任何一个键都是 400(见 {@link SuggestTagRequest})
     */
    @PostMapping("/suggest")
    public SuggestTagResponse suggest(@PathVariable String id,
                                      @Valid @RequestBody(required = false) SuggestTagRequest body) {
        Touch touch = requireRecord(id);
        Suggestion suggestion = tagging.suggest(touch, null, null);

        List<RecordTag> tags = tagging.tagsOf(touch);
        CoverageReader.Snapshot snapshot = reader.read();
        Syllabus tree = snapshot.syllabus();
        RecordTag tag = suggestion.tag();

        return new SuggestTagResponse(
                suggestion.outcome().name(),
                messageFor(suggestion),
                suggestion.confidence(),
                suggestion.candidateCount(),
                tag == null ? null : TagDto.from(tag, tree),
                toDtos(tags, tree),
                tag == null ? null : nodeDetail(snapshot, tag.nodeCode()),
                SummaryDto.from(reader.summarize(snapshot)));
    }

    /**
     * 手动挂载。<b>body 只接受 {@code nodeCode},不接受名字</b>(docs/10 §6.3)。
     *
     * <h2>201 与 200 的区别是「新挂了没有」,不是「成功了没有」</h2>
     *
     * 同一个考点挂第二次返回的是原来那条,服务端什么都没新建 ——
     * 这时候还回 201 Created 是在说谎。与 {@code POST /api/records} 的幂等语义一致。
     * <p>
     * 例外是<b>之前丢弃过</b>的那个考点:那时会新挂一条干净的标签(201),
     * 而不是把丢弃那条翻过来 —— 「我曾经把它丢掉过」这件事得留着,
     * 否则同一个错标会被反复建议,而用户不知道自己已经丢过一次。
     */
    @PostMapping
    public ResponseEntity<RecordTagsResponse> mount(@PathVariable String id,
                                                    @Valid @RequestBody MountTagRequest req) {
        Touch touch = requireRecord(id);

        return switch (tagging.mount(touch, req.nodeCode())) {
            case MountResult.Mounted mounted -> ResponseEntity
                    .status(mounted.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(responseFor(touch, mounted.tag().nodeCode()));
            // 🔴 挂不上就是挂不上:不模糊匹配、不取最接近的、不新建节点(R-07)。
            //    这个工厂方法已经把用户输入过了一遍截断,这里不再拼一次。
            case MountResult.NotInSyllabus ignored -> throw ApiException.nodeNotInSyllabus(req.nodeCode());
        };
    }

    /**
     * 确认 —— 写 {@code confirmed_at},计入覆盖度。🔴 <b>不改 {@code origin}</b>(docs/10 §6.3)。
     *
     * <p>「计入覆盖度」不是这次确认新增的效果:一条没被丢弃的标签本来就在分子里
     * (docs/10 §6.4「分子 = {@code discarded=0} 的触达节点数」)。这句契约陈述的是
     * <b>确认不会让它掉出覆盖度</b> —— 与丢弃相对。所以这个端点前后覆盖率通常不变,
     * 那是对的,不是没生效。
     */
    @PostMapping("/{tagId}/confirm")
    public RecordTagsResponse confirm(@PathVariable String id, @PathVariable String tagId) {
        Touch touch = requireRecord(id);
        RecordTag confirmed = tagging.confirm(touch, tagId);
        return responseFor(touch, requireTag(confirmed).nodeCode());
    }

    /**
     * 丢弃 —— 置 {@code discarded=1}。<b>可见,但不计覆盖度</b>({@code P1-7})。
     *
     * <p>这是宁缺毋滥在接口上的出口:标错了的考点得能拿掉,而<b>不必删掉整条记录</b>。
     * 删记录会把「我那天学过东西」这件事一起抹掉,而错的只是它挂在哪儿。
     */
    @PostMapping("/{tagId}/discard")
    public RecordTagsResponse discard(@PathVariable String id, @PathVariable String tagId) {
        Touch touch = requireRecord(id);
        RecordTag discarded = tagging.discard(touch, tagId);
        return responseFor(touch, requireTag(discarded).nodeCode());
    }

    // ---------------------------------------------------------------- 内部

    /** 🔴 404 的消息里不带那个 id —— 理由见类注释。 */
    private Touch requireRecord(String id) {
        Touch touch = tagging.findRecord(id);
        if (touch == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RECORD_NOT_FOUND",
                    "找不到这条记录 —— 它可能已经被删掉了。");
        }
        return touch;
    }

    /**
     * 标签不属于这条记录 → 404,<b>与「记录不存在」用不同的 code</b>。
     *
     * <p>合并成一个 404 的话,前端分不清该刷新时间线还是该刷新这条记录的标签列表。
     * 而「标签属于别人的记录」也走这一支:那种情况回 403 等于确认了这个 id 存在,
     * 404 什么都不确认。
     */
    private static RecordTag requireTag(RecordTag tag) {
        if (tag == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TAG_NOT_FOUND",
                    "这条记录上没有这个标签 —— 它可能已经被删掉了,或者本来就不属于这条记录。");
        }
        return tag;
    }

    /** 写完之后再读一次差集,把标签列表、受影响的考点、整体概览一起带回去。 */
    private RecordTagsResponse responseFor(Touch touch, String nodeCode) {
        CoverageReader.Snapshot snapshot = reader.read();
        return new RecordTagsResponse(
                touch.id(),
                toDtos(tagging.tagsOf(touch), snapshot.syllabus()),
                nodeDetail(snapshot, nodeCode),
                SummaryDto.from(reader.summarize(snapshot)));
    }

    private static List<TagDto> toDtos(List<RecordTag> tags, Syllabus tree) {
        return tags.stream().map(t -> TagDto.from(t, tree)).toList();
    }

    /** 考点已不在树里(被删了)时返回 {@code null} —— 那不该让这次请求 500。 */
    private static NodeDetailDto nodeDetail(CoverageReader.Snapshot snapshot, String nodeCode) {
        NodeCoverage node = snapshot.node(nodeCode);
        return node == null ? null : NodeDetailDto.from(node);
    }

    /**
     * 六种结局各说各的话。
     *
     * <p>措辞写在这一处,不写在枚举上:{@code TagOrigin} 那段说过为什么标签侧的枚举不带中文 label ——
     * 一旦枚举带上给用户看的字,就会有人为了让提示好看去改枚举本身,而其中一个是不可变的。
     * 这里是接口层,措辞本来就该在这儿。
     */
    private static String messageFor(Suggestion suggestion) {
        return switch (suggestion.outcome()) {
            case SUGGESTED -> "识别挑了一个考点,请确认或丢弃。";
            case ALREADY_TAGGED -> "这个考点已经挂在这条记录上了,没有重复挂。";
            case NOT_RECALLED -> "来源名里没有可用线索,没有候选可送 —— 请自己从树里挑一个考点。";
            case NO_MATERIAL -> "这条记录没有可再次识别的素材(原图与转写都不留存),请自己从树里挑一个考点。";
            case NO_MATCH -> "没认出来 —— 请自己从树里挑一个考点。";
            case UNAVAILABLE -> "识别服务暂时不可用,可以稍后重试,也可以自己从树里挑一个考点。";
        };
    }
}
