package com.kaodian.server.api;

import com.kaodian.server.api.dto.ApiError;
import com.kaodian.server.api.dto.BatchCreateRecordsRequest;
import com.kaodian.server.api.dto.BatchCreateRecordsResponse;
import com.kaodian.server.api.dto.BatchCreateRecordsResponse.ItemResult;
import com.kaodian.server.api.dto.CreateRecordRequest;
import com.kaodian.server.api.dto.CreateRecordResponse;
import com.kaodian.server.api.dto.NodeDetailDto;
import com.kaodian.server.api.dto.RecordDeletedResponse;
import com.kaodian.server.api.dto.RecordPageResponse;
import com.kaodian.server.api.dto.SummaryDto;
import com.kaodian.server.api.dto.TimelineItemDto;
import com.kaodian.server.collect.CaptureService;
import com.kaodian.server.collect.CaptureService.CaptureRequest;
import com.kaodian.server.collect.CaptureService.CaptureResult;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusSource;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 「记一笔」及其读侧 —— docs/10 §6.2 采集那张表。
 *
 * <h2>控制器不自己落库,一律走 {@link CaptureService}</h2>
 *
 * {@code CaptureService} 是五种记录方式的收口点,「先看用户挑没挑考点,再看模型认没认出来」
 * 那条分支顺序就在它里面。如果这个控制器为了图省事自己 {@code new Touch(...)} 再 append,
 * 就会出现<b>第二条写入路径</b> —— 而红线是逐条挂在写入路径上的(R-07 校验、时间戳来源、
 * 做题数照抄不判对错)。两条路径迟早只有一条被更新。
 * <p>
 * 批量补传因此也走同一个 {@code capture} —— <b>逐条调用,不另开一条「批量快速通道」</b>。
 * 快速通道是这里最诱人也最危险的优化:它会绕开考点校验(那是 R-07 的落点),
 * 而绕开的理由永远是「批量嘛,那些条目客户端已经校验过了」。
 *
 * <h2>🔴 R-07 在接口层的两道锁</h2>
 *
 * <ol>
 *   <li><b>请求体里没有自由文本标签的位置</b> —— {@link CreateRecordRequest} 的字段表是钉死的,
 *       且未定义字段一律 400(见该类的 javadoc)。批量的外层壳
 *       {@link BatchCreateRecordsRequest} 上有同一道锁</li>
 *   <li><b>{@code nodeCode} 必须在骨架树里查得到</b> —— 由 {@code CaptureService} 判,
 *       查不到就是拒绝,不模糊匹配、不取最接近的、不新建节点</li>
 * </ol>
 * 第二道锁是「宁缺毋滥」在写入路径上的形态:硬凑一个最接近的考点会让覆盖度失真,
 * 而失真的覆盖度比没有覆盖度更糟 —— <b>它就是这个产品本身</b>。
 *
 * <h2>这条路不碰任何模型</h2>
 *
 * body 里已经有用户挑好的 {@code nodeCode},所以走的是 {@code CaptureService.capture} ——
 * 永不消耗额度、永不受识别故障影响(docs/11 §二「额度用尽 ≠ 记不了」)。
 * 语音/拍照那条路是另一个端点,但它们最终落到同一个 {@code CaptureService},
 * 不是另开一条「识别成功才写入」的路径(docs/08 §1.3.7)。
 */
@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final CaptureService capture;
    private final SyllabusSource syllabus;
    private final CoverageReader reader;
    private final TouchStore store;

    /**
     * 逐条校验批量条目用的校验器。
     *
     * <h2>为什么不能给批量的元素挂 {@code @Valid}</h2>
     *
     * {@code List<@Valid CreateRecordRequest>} 会让 Spring 在进方法之前把整批校验一遍,
     * <b>一条不合法整批 400</b> —— 而那正是 {@link BatchCreateRecordsResponse} 开头
     * 那一整段说不能干的事:用户断网记了一天,第 17 条有问题,前 16 条不该跟着陪葬。
     * <p>
     * 所以校验器拿在手里,逐条自己调。注解本身一个字都不改 —— 规则仍然只写在
     * {@link CreateRecordRequest} 上一处,这里只是换了个<b>触发时机</b>。
     */
    private final Validator validator;

    public RecordController(CaptureService capture, SyllabusSource syllabus,
                            CoverageReader reader, TouchStore store, Validator validator) {
        this.capture = capture;
        this.syllabus = syllabus;
        this.reader = reader;
        this.store = store;
        this.validator = validator;
    }

    // ---------------------------------------------------------------- 写

    /**
     * 记一笔。
     *
     * <h2>201 与 200 的区别是「新建了没有」,不是「成功了没有」</h2>
     *
     * 带着同一个 {@code clientToken} 再提交一次,返回的是<b>原来那条</b>,
     * 服务端什么都没新建 —— 这时候还回 201 Created 是在说谎,而客户端凭这个状态码
     * 判断「我这次到底记上了没有」是最自然的写法。
     * <p>
     * 两种情况都<b>不是错误</b>:重复提交不报错,是 docs/10 §6.2「{@code client_token} 幂等」
     * 的字面要求,也是离线队列敢重发的前提。
     */
    @PostMapping
    public ResponseEntity<CreateRecordResponse> create(@Valid @RequestBody CreateRecordRequest req) {
        CaptureResult result = capture.capture(toCaptureRequest(req));

        return switch (result) {
            case CaptureResult.Recorded recorded -> ResponseEntity
                    .status(recorded.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(responseFor(recorded.touch()));
            case CaptureResult.Rejected rejected -> throw toApiException(rejected.reason(), req.nodeCode());
        };
    }

    /**
     * 离线队列补传 —— docs/08 {@code R-32} 的防线落到接口上的那一段。
     *
     * <h2>整批 400 与逐条失败的分界线</h2>
     *
     * <table border="1">
     *   <caption>两类失败</caption>
     *   <tr><th>什么时候整批 400</th><th>什么时候逐条失败</th></tr>
     *   <tr><td>超过 50 条、空批、JSON 解析不了、出现未定义字段(R-07)</td>
     *       <td>某一条缺去重键、字段不合法、考点不在树里</td></tr>
     *   <tr><td><b>调用方发错了东西</b>,重发同样的内容还是错</td>
     *       <td><b>数据本身的问题</b>,其余条目没有理由陪葬</td></tr>
     * </table>
     *
     * <p>🔴 解析层的失败必然是整批的,这一点无法调和也不该调和:
     * {@code @JsonAnySetter} 那道锁在<b>反序列化</b>时就抛了,那时还没有「第几条」这个概念。
     * 而它拦下的是 R-07 —— 有人试图往里塞自由文本标签。
     * <b>那种请求整批拒掉是对的:它不是「有一条数据不干净」,是调用方在试探红线。</b>
     *
     * <h2>顺序处理,不并发</h2>
     *
     * 50 条写一个 JSON 文件,并发只会让那把写锁排队更热闹。
     * 更要紧的是<b>顺序即结果顺序</b>:客户端靠下标把结果对回自己队列里的那一条。
     */
    @PostMapping("/batch")
    public BatchCreateRecordsResponse createBatch(@Valid @RequestBody BatchCreateRecordsRequest req) {
        List<ItemResult> results = new ArrayList<>(req.records().size());
        for (int i = 0; i < req.records().size(); i++) {
            results.add(storeOne(i, req.records().get(i)));
        }
        return BatchCreateRecordsResponse.of(results);
    }

    /**
     * 批里的一条。<b>这个方法不抛异常</b> —— 它的每一条出路都是一个 {@link ItemResult}。
     *
     * <p>抛出去就等于整批中断,而中断点之后那些本来能落的记录会一条都不落,
     * 客户端还拿不到「已经落了前几条」这个信息,于是重发时全部重来一遍。
     * 幂等能兜住重来,但兜不住用户看见的那个「补传失败」。
     */
    private ItemResult storeOne(int index, CreateRecordRequest item) {
        // 🔴 补传必须带去重键。理由见 BatchCreateRecordsRequest ——
        // 没有它的补传是一次注定重复的写入,而重复的触达会把覆盖度的分子算错。
        if (item.clientToken() == null || item.clientToken().isBlank()) {
            return ItemResult.failed(index, null, error("MISSING_CLIENT_TOKEN",
                    "补传的每一条都必须带 clientToken —— 没有它就没法判重,重发一次就多一条记录。"));
        }

        // 逐条校验,规则仍然是 CreateRecordRequest 上那几个注解(见 validator 字段的说明)。
        Set<ConstraintViolation<CreateRecordRequest>> violations = validator.validate(item);
        if (!violations.isEmpty()) {
            // 🔴 只拼「字段名 + 我们自己写的那句中文」,绝不把 getInvalidValue() 拼进去。
            // 一批 50 条,原样回声等于把 50 段用户输入一起写进响应体和访问日志 ——
            // 与 ApiExceptionHandler 开头那条纪律同源,只是这里的放大倍数是 50。
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + ":" + v.getMessage())
                    .sorted()
                    .distinct()
                    .collect(Collectors.joining("; "));
            return ItemResult.failed(index, item.clientToken(),
                    error("VALIDATION_FAILED", "这一条不合法 —— " + detail));
        }

        CaptureResult result = capture.capture(toCaptureRequest(item));
        return switch (result) {
            case CaptureResult.Recorded recorded -> {
                TimelineItemDto dto = TimelineItemDto.from(recorded.touch(), syllabus.current());
                yield recorded.replayed()
                        ? ItemResult.duplicate(index, item.clientToken(), dto)
                        : ItemResult.stored(index, item.clientToken(), dto);
            }
            // 拒绝理由的文案直接用枚举自带的 label():措辞只写在一处,与单条那条路同源。
            case CaptureResult.Rejected rejected -> ItemResult.failed(index, item.clientToken(),
                    error(rejected.reason().name(), rejected.reason().label()));
        };
    }

    /**
     * 删一条记录。
     *
     * <h2>⚪ 契约的「级联删标签」这一半今天是空的,没有被假装做掉</h2>
     *
     * 说明写在 {@link RecordDeletedResponse} 的 javadoc 里,连同它落地时要动哪儿。
     * 这里只重复一句结论:<b>今天没有标签表</b>,{@code Touch} 直接挂着一个 {@code nodeCode},
     * 删掉记录就删掉了它的全部挂载关系。
     *
     * <h2>「触发覆盖层重算」不需要一次显式调用</h2>
     *
     * 覆盖度是每次请求从 {@link TouchStore#findAll()} 现算的差集(见 {@link CoverageReader#read}),
     * 没有一份需要跟着失效的缓存,也就没有「忘了触发」这种失败模式。
     * 契约那句话在当前实现形态下自动成立 —— <b>这不是没做,是不需要做</b>。
     * 哪天覆盖度改成增量维护的,这条注释就是那时候第一个要回头看的地方。
     *
     * <h2>🔴 404 的消息里不回显那个 id</h2>
     *
     * 路径变量没有任何长度上限,能塞满一整个请求行;而报错消息会同时进响应体和服务端日志。
     * 那个 id 是<b>客户端自己刚发过来的</b>,回显给它一个字的信息都不增加,
     * 却凭空开了一条「往日志里写一整段题干」的路。所以这里干脆不回显 ——
     * 定位靠 traceId(见 {@code ApiExceptionHandler} 开头那条纪律)。
     */
    @DeleteMapping("/{id}")
    public RecordDeletedResponse delete(@PathVariable String id) {
        Touch deleted = store.delete(id);
        if (deleted == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RECORD_NOT_FOUND",
                    "找不到这条记录 —— 它可能已经被删掉了。");
        }

        CoverageReader.Snapshot snapshot = reader.read();
        NodeCoverage node = snapshot.node(deleted.nodeCode());
        return new RecordDeletedResponse(
                deleted.id(),
                node == null ? null : NodeDetailDto.from(node),
                SummaryDto.from(reader.summarize(snapshot)));
    }

    // ---------------------------------------------------------------- 读

    /**
     * 时间线,cursor 分页(docs/10 §6.2)。
     *
     * <p>与 {@code GET /api/timeline} 的分工写在 {@link RecordPageResponse} 的 javadoc 里 ——
     * <b>那一条是 §6.4 的聚合视图,这一条是采集线的读侧</b>,两个都留着。
     *
     * @param cursor 上一页返回的 {@code nextCursor};第一页不传
     * @param limit  每页几条。默认 50,上限 200。
     *               <b>这两个数不再与 {@code /api/timeline} 共享</b> —— 那边改成聚合视图之后已经没有
     *               {@code limit} 了,它按 {@code buckets} 数格子。两处从此各定各的,别再当成一组数改。
     */
    @GetMapping
    public RecordPageResponse list(
            // 🔴 这里刻意【没有】@Size:长度由 RecordCursor.decode 一处判。
            // 挂上去的话,超长游标回 VALIDATION_FAILED、解不开的游标回 INVALID_CURSOR ——
            // 同一件事(这个游标不能用)两个错误码,前端就得写两条分支。
            @RequestParam(required = false)
            String cursor,

            @RequestParam(defaultValue = "" + RecordPageResponse.DEFAULT_LIMIT)
            @Min(value = 1, message = "至少要 1 条")
            @Max(value = RecordPageResponse.MAX_LIMIT, message = "一次最多 200 条")
            int limit) {

        RecordCursor.Position from = RecordCursor.decode(cursor);

        List<Touch> all = store.findAll();
        // 🔴 排序是 (occurredAt, id) 两级倒序,不是只按时间。
        // 同一毫秒里真的会有多条 —— 补传一次落 50 条,它们共用同一个服务端时刻。
        // 只按时间排,那 50 条在翻页时要么一起被跳过要么一起被重复吐出来(见 RecordCursor)。
        Comparator<Touch> oldestFirst = Comparator.comparing(Touch::occurredAt).thenComparing(Touch::id);
        List<Touch> ordered = all.stream()
                .sorted(oldestFirst.reversed())
                .filter(t -> from == null || from.isStrictlyAfter(t))
                .toList();

        // 多取一条只为回答「还有没有更旧的」。用 total 减一减是算不出来的:
        // total 是全量条数,而游标之后还剩几条要么再扫一遍要么就是猜。
        List<Touch> page = ordered.stream().limit(limit + 1L).toList();
        boolean hasMore = page.size() > limit;
        List<Touch> visible = hasMore ? page.subList(0, limit) : page;

        // 树只问一次:同一页上的记录必须用同一棵树翻译考点名,否则中途一次改名会让上下两条对不上
        Syllabus syllabusNow = syllabus.current();
        List<TimelineItemDto> items = visible.stream()
                .map(t -> TimelineItemDto.from(t, syllabusNow))
                .toList();

        return new RecordPageResponse(
                all.size(),
                items.size(),
                hasMore,
                hasMore ? RecordCursor.encode(visible.get(visible.size() - 1)) : null,
                items);
    }

    // ---------------------------------------------------------------- 内部

    /** 请求体 → 采集入参。<b>逐字段列举</b>,不做任何转换 —— 转换的地方就是字段悄悄改语义的地方。 */
    private static CaptureRequest toCaptureRequest(CreateRecordRequest req) {
        return new CaptureRequest(req.kind(), req.sourceName(), req.nodeCode(),
                req.practiced(), req.correct(), req.clientToken());
    }

    /** 落地之后再读一次差集,把那个考点的新状态一起带回去 —— 前端不必再请求一次。 */
    private CreateRecordResponse responseFor(Touch stored) {
        NodeCoverage node = reader.read().node(stored.nodeCode());
        return new CreateRecordResponse(
                TimelineItemDto.from(stored, syllabus.current()),
                node == null ? null : NodeDetailDto.from(node));
    }

    /**
     * 批量里那一条的错误体。
     *
     * <p>形状与整条请求失败时完全一样({@link ApiError}),包括 {@code traceId} ——
     * 用户报「我有几条没传上去」时,那一串是唯一能捞到日志的东西。
     * <b>逐条一个 traceId,不是整批共用一个</b>:共用的那个捞出来是 50 条日志,等于没捞。
     */
    private static ApiError error(String code, String message) {
        return new ApiError(code, message, UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    /**
     * 拒绝原因 → HTTP。
     *
     * <p>{@code CaptureService.Rejection} 把四种拒绝分开列,是因为<b>界面上该说的下一步完全不同</b>;
     * 接口层要做的就是别把它们又合并回一个笼统的 400。所以 {@code code} 逐个映射,
     * 文案直接用枚举自带的 {@code label()} —— 措辞只写在一处。
     *
     * <p>后两种在这条路上不会发生({@code capture} 不调用模型),但 switch 必须穷尽:
     * 哪天有人把识别接到这个端点上,编译器会先提醒他这两支需要一个答复。
     */
    private static ApiException toApiException(CaptureService.Rejection reason, String nodeCode) {
        return switch (reason) {
            case NODE_NOT_IN_SYLLABUS -> ApiException.nodeNotInSyllabus(nodeCode);
            case MISSING_NODE_CODE -> new ApiException(
                    HttpStatus.BAD_REQUEST, "MISSING_NODE_CODE", reason.label());
            case NO_MATCH_AND_NO_USER_NODE -> new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "NO_MATCH_AND_NO_USER_NODE", reason.label());
            case RECOGNIZER_UNAVAILABLE_AND_NO_USER_NODE -> new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "RECOGNIZER_UNAVAILABLE", reason.label());
        };
    }
}
