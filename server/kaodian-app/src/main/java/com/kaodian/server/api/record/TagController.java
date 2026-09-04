package com.kaodian.server.api.record;

import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.api.dto.record.MountTagRequest;
import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.record.RecordTagsResponse;
import com.kaodian.server.api.dto.record.SuggestTagRequest;
import com.kaodian.server.api.dto.record.SuggestTagResponse;
import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.api.dto.record.TagDto;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.api.dto.record.MountTagRequest;
import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.record.RecordTagsResponse;
import com.kaodian.server.api.dto.record.SuggestTagRequest;
import com.kaodian.server.api.dto.record.SuggestTagResponse;
import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.api.dto.record.TagDto;
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
 * 打标 —— docs/technical/INDEX.md §6.3 那张表的四个端点。
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
 * 无论模型输出什么」(docs/technical/INDEX.md §6.3)。
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
@RequestMapping("/api/v1/records/{id}/tags")
public class TagController {

    private final TaggingService tagging;
    private final CoverageReader reader;

    public TagController(TaggingService tagging, CoverageReader reader) {
        this.tagging = tagging;
        this.reader = reader;
    }

    /**
     * 触发一次闭集分类(docs/technical/后端系统设计与组件接入.md §1.3 的四段)。
     *
     * <h2>🔴 全部结局都是 200,理由写在 {@link SuggestTagResponse} 上</h2>
     *
     * 一句话:<b>记录早就落地了,补标失败什么都没损坏</b>。回 503 会让前端把它当成一次失败去重试,
     * 而它没有失败 —— 它只是这次没认出来,用户随时可以手动挂一个。
     *
     * <h2>⚪ {@code material} 传的是 {@code null},这不是没写完</h2>
     *
     * 服务端手里<b>一份可送进模型的素材都没有</b>:原图内联送一次即弃(决策记录 §2.3 / docs/data/识别链路选型.md 坑二),
     * 转写文本用完即弃,{@code Touch} 结构上没有能装下它们的字段(决策记录 §2.2 不碰内容)。
     * 拿零字节去调一次视觉模型是「假装成功」的另一种写法,所以这里明确地不给素材,
     * 由 {@code TaggingService} 回一个 {@code NO_MATERIAL} 说清原因。
     * <p>
     * 带着字节走完四段的那条路在 {@code TaggingService.suggest} 里是实现好的,而且
     * <b>现在有 HTTP 入口了</b>:docs/technical/INDEX.md §6.2 的 {@code POST /records/{id}/image}
     * ({@link RecognitionController#recognizePhotos})。两个端点共用
     * {@link SuggestTagResponse} 这一个答复形状,区别只在于<b>手里有没有素材</b>。
     * <p>
     * ⚪ <b>这个端点本身的缺口没有跟着补上,而且补不了</b>:它是「事后」补标,
     * 而事后服务端手里一份素材都没有 —— 那不是实现偷懒,是红线的直接后果。
     * 契约层面的缺口(§6.3 的 suggest 依赖 §5.2 的 {@code extracted_text},
     * 而那个字段与本仓库的红线冲突)已在交付说明里报出,本轮不自行改契约。
     *
     * @param body 可以整个不传;<b>传了就必须是个空对象</b> ——
     *             里面出现任何一个键都是 400(见 {@link SuggestTagRequest})
     */
    @PostMapping("/suggest")
    public SuggestTagResponse suggest(CurrentSession session, @PathVariable String id,
                                      @Valid @RequestBody(required = false) SuggestTagRequest body) {
        session.requireWrite();
        Touch touch = requireRecord(session.userId(), id);
        Suggestion suggestion = tagging.suggest(touch, null, null);

        List<RecordTag> tags = tagging.tagsOf(touch);
        CoverageReader.Snapshot snapshot = reader.read(session.userId());
        Syllabus tree = snapshot.syllabus();
        RecordTag tag = suggestion.tag();

        return new SuggestTagResponse(
                suggestion.outcome().name(),
                SuggestTagResponse.messageFor(suggestion.outcome()),
                suggestion.confidence(),
                suggestion.candidateCount(),
                tag == null ? null : TagDto.from(tag, tree),
                toDtos(tags, tree),
                tag == null ? null : nodeDetail(snapshot, tag.nodeCode()),
                SummaryDto.from(reader.summarize(snapshot)));
    }

    /**
     * 手动挂载。<b>body 只接受 {@code nodeCode},不接受名字</b>(docs/technical/INDEX.md §6.3)。
     *
     * <h2>201 与 200 的区别是「新挂了没有」,不是「成功了没有」</h2>
     *
     * 同一个考点挂第二次返回的是原来那条,服务端什么都没新建 ——
     * 这时候还回 201 Created 是在说谎。与 {@code POST /api/v1/records} 的幂等语义一致。
     * <p>
     * 例外是<b>之前丢弃过</b>的那个考点:那时会新挂一条干净的标签(201),
     * 而不是把丢弃那条翻过来 —— 「我曾经把它丢掉过」这件事得留着,
     * 否则同一个错标会被反复建议,而用户不知道自己已经丢过一次。
     */
    @PostMapping
    public ResponseEntity<RecordTagsResponse> mount(CurrentSession session, @PathVariable String id,
                                                    @Valid @RequestBody MountTagRequest req) {
        session.requireWrite();
        Touch touch = requireRecord(session.userId(), id);

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
     * 确认 —— 写 {@code confirmed_at},计入覆盖度。🔴 <b>不改 {@code origin}</b>(docs/technical/INDEX.md §6.3)。
     *
     * <p>「计入覆盖度」不是这次确认新增的效果:一条没被丢弃的标签本来就在分子里
     * (docs/technical/INDEX.md §6.4「分子 = {@code discarded=0} 的触达节点数」)。这句契约陈述的是
     * <b>确认不会让它掉出覆盖度</b> —— 与丢弃相对。所以这个端点前后覆盖率通常不变,
     * 那是对的,不是没生效。
     */
    @PostMapping("/{tagId}/confirm")
    public RecordTagsResponse confirm(CurrentSession session, @PathVariable String id,
                                      @PathVariable String tagId) {
        session.requireWrite();
        Touch touch = requireRecord(session.userId(), id);
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
    public RecordTagsResponse discard(CurrentSession session, @PathVariable String id,
                                      @PathVariable String tagId) {
        session.requireWrite();
        Touch touch = requireRecord(session.userId(), id);
        RecordTag discarded = tagging.discard(touch, tagId);
        return responseFor(touch, requireTag(discarded).nodeCode());
    }

    // ---------------------------------------------------------------- 内部

    /** 🔴 404 的消息里不带那个 id —— 理由见类注释。 */
    private Touch requireRecord(long userId, String id) {
        // 🔴 只在这个用户名下找 —— 别人的记录在这里等于不存在,回的是同一句 404。
        //    回 403 会确认「这个 id 存在」,而 404 什么都不确认(与 requireTag 同一条)。
        Touch touch = tagging.findRecord(userId, id);
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
        // 归属取自记录本身 —— 它是从当前用户名下查出来的(见 requireRecord)
        CoverageReader.Snapshot snapshot = reader.read(touch.userId());
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

    // 六种结局各说各的话 —— 那段 switch 搬到了 SuggestTagResponse#messageFor。
    // 搬家的理由写在那个方法上:走同一条管线的端点现在有两个(这里 + POST /records/{id}/image),
    // 措辞留在其中一个控制器里,另一个就只能抄一遍,而抄出来的两份迟早会说两句不一样的话。
}
