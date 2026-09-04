package com.kaodian.server.api.record;

import com.kaodian.server.api.dto.common.ErrorCode;
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
import com.kaodian.server.api.dto.record.CandidateDto;
import com.kaodian.server.api.dto.record.RestoreTagResponse;
import com.kaodian.server.api.dto.record.TagSuggestionResponse;
import com.kaodian.server.api.support.IdempotencyGuard;
import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.tagging.ModelCallGate;
import com.kaodian.server.tagging.TaggingService;
import com.kaodian.server.tagging.TaggingService.MountResult;
import com.kaodian.server.tagging.TaggingService.RestoreResult;
import com.kaodian.server.tagging.TaggingService.Suggestion;
import com.kaodian.server.tagging.TagAttempt;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.syllabus.Syllabus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
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

    /**
     * 幂等键的保留期 —— 🔴 <b>本模块定 24 小时</b>({@code B0} §7.3:保留期各模块自定)。
     *
     * <p>理由是一个算得出来的数:最长退避 30min × 3 次 &lt; 2h,24h 覆盖「离线一整晚后补传」,
     * 再长只是为不会发生的重放付存储。
     */
    static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final TaggingService tagging;
    private final CoverageReader reader;
    private final IdempotencyGuard idempotency;

    /**
     * 🔴 {@link ModelCallGate} 在这一层注入,再<b>作为参数</b>递进领域层({@code M2} §2.5)——
     * 领域层不注入、不查找、不知道实现类名,{@code domain → app} 那条边因此建不出来。
     */
    private final ModelCallGate gate;

    public TagController(TaggingService tagging, CoverageReader reader,
                         IdempotencyGuard idempotency, ModelCallGate gate) {
        this.tagging = tagging;
        this.reader = reader;
        this.idempotency = idempotency;
        this.gate = gate;
    }

    /**
     * 触发一次闭集分类 —— {@code M2-打标管线与模型接入} §9.1。
     *
     * <h2>🔴 必带 {@code Idempotency-Key}</h2>
     *
     * 这个端点会触发外部模型调用 = 一笔按次外部账单,重放一次就是扣两次
     * ({@code 接口契约} §4.1 已逐字写死)。锚定 {@code (userId, path, key)},
     * 🔴 <b>不是参数哈希</b> —— 请求体是空对象,参数哈希会把「用户真的想再认一次」
     * 和「一次网络重试」压成同一个值。
     *
     * <h2>成功只有两种形态,失败各有各的码</h2>
     *
     * <table border="1">
     *   <caption>{@code M2} §9.1 那张表</caption>
     *   <tr><th>档</th><th>HTTP</th><th>{@code code}</th></tr>
     *   <tr><td>命中 / 无匹配</td><td>200</td><td>—({@code state})</td></tr>
     *   <tr><td>识别服务不可用</td><td>503</td><td>{@code RECOGNIZER_UNAVAILABLE}</td></tr>
     *   <tr><td>拿不到许可</td><td>403</td><td>{@code QUOTA_EXHAUSTED}</td></tr>
     *   <tr><td>该科目无骨架</td><td>422</td><td>{@code SYLLABUS_EMPTY}</td></tr>
     *   <tr><td>记录不存在</td><td>404</td><td>{@code RECORD_NOT_FOUND}</td></tr>
     *   <tr><td>没带幂等键</td><td>400</td><td>{@code IDEMPOTENCY_KEY_REQUIRED}</td></tr>
     *   <tr><td>幂等键进行中</td><td>409</td><td>{@code IN_PROGRESS}</td></tr>
     * </table>
     *
     * ⚠️ <b>「服务不可用」这一格与 {@code M2} §9.1 写的 {@code 502 SERVER_ERROR} 不一致,
     * 这是有意的、并且已经报回议题</b>:{@code 接口契约} §10.2 把 {@code SERVER_ERROR}
     * 钉在 {@code 500}(而 {@code B0} 的 {@code ErrorCode} 枚举照它落了地),§4.1 又写
     * {@code 502}/{@code 504} —— 契约自己两处对不上。本模块<b>不自己改横切件去迁就一侧</b>,
     * 走 {@code RECOGNIZER_UNAVAILABLE(503)}:今天识别不可用<b>确实发生在发出外部调用之前</b>
     * (还没有真实厂商实现),503 是这一档在契约里本来就有的那个码。
     *
     * <h2>⚪ {@code material} 传的是 {@code null},这不是没写完</h2>
     *
     * 服务端手里<b>一份可送进模型的素材都没有</b>:原图内联送一次即弃,转写文本用完即弃,
     * {@code Touch} 结构上没有能装下它们的字段。拿零字节去调一次视觉模型是「假装成功」的
     * 另一种写法,所以这里明确地不给素材,由领域层回一个 {@code NO_MATERIAL} 说清原因,
     * wire 上合进 {@code NO_MATCH}。<b>登记为 {@code M2-G1},入口归 {@code M1},本轮不代填。</b>
     *
     * @param body 可以整个不传;<b>传了就必须是个空对象</b> ——
     *             里面出现任何一个键都是 400(见 {@link SuggestTagRequest})
     */
    @PostMapping("/suggest")
    public TagSuggestionResponse suggest(CurrentSession session, @PathVariable String id,
                                         @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                         @Valid @RequestBody(required = false) SuggestTagRequest body) {
        session.requireWrite();
        Touch touch = requireRecord(session.userId(), id);

        String path = "/api/v1/records/" + id + "/tags/suggest";
        switch (idempotency.begin(session.userId(), path, key, IDEMPOTENCY_RETENTION)) {
            // 🔴 命中已成功:返回上一次的结果,不再调模型、不再动许可。
            case IdempotencyGuard.Replay replay -> {
                return (TagSuggestionResponse) replay.result();
            }
            case IdempotencyGuard.InFlight ignored -> throw new ApiException(
                    ErrorCode.IN_PROGRESS, "上一次识别还在进行中,请等它结束。");
            case IdempotencyGuard.Fresh ignored -> {
                // 往下真的走一遍管线
            }
        }

        Suggestion suggestion;
        try {
            suggestion = tagging.suggest(touch, null, null, gate);
        } catch (RuntimeException e) {
            // 🔴 失败要放掉槽位,否则这个键会被永久钉在 IN_PROGRESS 上直到保留期到点,
            //    而「上次失败 → 允许重试」是契约里写着的一档语义。
            idempotency.fail(session.userId(), path, key);
            throw e;
        }

        TagSuggestionResponse response;
        try {
            response = switch (suggestion.outcome()) {
                case UNAVAILABLE -> throw new ApiException(ErrorCode.RECOGNIZER_UNAVAILABLE,
                        "识别服务暂时不可用,可以稍后重试,也可以自己从树里挑一个考点。");
                case QUOTA_EXHAUSTED -> throw new ApiException(ErrorCode.QUOTA_EXHAUSTED,
                        "这个月的自动识别用完了。你仍然可以自己从树里挑一个考点,记录一条不少。");
                case SYLLABUS_EMPTY -> throw new ApiException(ErrorCode.SYLLABUS_EMPTY,
                        "这个科目的考点树还没有建好,现在挑不了考点。");
                default -> TagSuggestionResponse.from(
                        suggestion, reader.read(session.userId()).syllabus());
            };
        } catch (RuntimeException e) {
            idempotency.fail(session.userId(), path, key);
            throw e;
        }
        idempotency.complete(session.userId(), path, key, response);
        return response;
    }

    /**
     * 恢复一条丢弃过的标签 —— {@code M2} §9.2 / {@code 接口契约} §4.2。
     *
     * <h2>🔴 不需要 {@code Idempotency-Key}</h2>
     *
     * 它不触发外部账单、可以无限重放 —— <b>天然幂等</b>。已经是 {@code TS-02} 的标签
     * 原样返回 200,不报错。要求一个键只会让端多一次失败的机会。
     *
     * <h2>界面两档,契约三档</h2>
     *
     * {@code TAG_NOT_FOUND} 与 {@code NODE_ARCHIVED} 在界面上走同一个分支(「重新挑一个」),
     * 但<b>码不合并</b>:前者是我们这边的行不见了,后者是骨架变了 ——
     * 两件事各自会以完全不同的方式变多,合成一个码就再也分不出是哪一种在涨。
     */
    @PostMapping("/{tagId}/restore")
    public RestoreTagResponse restore(CurrentSession session, @PathVariable String id,
                                      @PathVariable String tagId) {
        session.requireWrite();
        Touch touch = requireRecord(session.userId(), id);
        return switch (tagging.restore(touch, tagId)) {
            case RestoreResult.Restored restored -> RestoreTagResponse.of(restored.tag().id());
            // 🔴 消息里不回显 tagId —— 它没有长度上限,而报错消息会同时进响应体和服务端日志。
            case RestoreResult.NotFound ignored -> throw new ApiException(
                    ErrorCode.TAG_NOT_FOUND, "找不到这条标签 —— 你可以重新挑一个考点。");
            case RestoreResult.NodeArchived ignored -> throw new ApiException(
                    ErrorCode.NODE_ARCHIVED, "这个考点已经归档了 —— 你可以重新挑一个。");
        };
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
