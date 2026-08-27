package com.kaodian.server.api;

import com.kaodian.server.api.dto.CreateRecordRequest;
import com.kaodian.server.api.dto.CreateRecordResponse;
import com.kaodian.server.api.dto.NodeDetailDto;
import com.kaodian.server.api.dto.TimelineItemDto;
import com.kaodian.server.collect.CaptureService;
import com.kaodian.server.collect.CaptureService.CaptureRequest;
import com.kaodian.server.collect.CaptureService.CaptureResult;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.syllabus.SyllabusSource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「记一笔」—— 现阶段唯一的写入端点。
 *
 * <h2>控制器不自己落库,一律走 {@link CaptureService}</h2>
 *
 * {@code CaptureService} 是五种记录方式的收口点,「先看用户挑没挑考点,再看模型认没认出来」
 * 那条分支顺序就在它里面。如果这个控制器为了图省事自己 {@code new Touch(...)} 再 append,
 * 就会出现<b>第二条写入路径</b> —— 而红线是逐条挂在写入路径上的(R-07 校验、时间戳来源、
 * 做题数照抄不判对错)。两条路径迟早只有一条被更新。
 *
 * <h2>🔴 R-07 在接口层的两道锁</h2>
 *
 * <ol>
 *   <li><b>请求体里没有自由文本标签的位置</b> —— {@link CreateRecordRequest} 只有五个字段,
 *       且未定义字段一律 400(见该类的 javadoc)</li>
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

    public RecordController(CaptureService capture, SyllabusSource syllabus, CoverageReader reader) {
        this.capture = capture;
        this.syllabus = syllabus;
        this.reader = reader;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateRecordResponse create(@Valid @RequestBody CreateRecordRequest req) {
        CaptureResult result = capture.capture(new CaptureRequest(
                req.kind(), req.sourceName(), req.nodeCode(), req.practiced(), req.correct()));

        Touch stored = switch (result) {
            case CaptureResult.Recorded recorded -> recorded.touch();
            case CaptureResult.Rejected rejected -> throw toApiException(rejected.reason(), req.nodeCode());
        };

        // 落地之后再读一次差集,把那个考点的新状态一起带回去 —— 前端不必再请求一次。
        NodeCoverage node = reader.read().node(stored.nodeCode());
        return new CreateRecordResponse(
                TimelineItemDto.from(stored, syllabus.current()),
                node == null ? null : NodeDetailDto.from(node));
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
