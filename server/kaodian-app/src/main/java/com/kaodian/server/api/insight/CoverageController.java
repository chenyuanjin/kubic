package com.kaodian.server.api.insight;

import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.api.dto.insight.BlindSpotsResponse;
import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.coverage.CoverageReader.Snapshot;
import com.kaodian.server.api.dto.insight.BlindSpotsResponse;
import com.kaodian.server.api.dto.common.SummaryDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 差集本身的两个端点:一个是那个大字,一个是「先补这几个」。
 */
@RestController
@RequestMapping("/api/coverage")
public class CoverageController {

    private final CoverageReader reader;

    public CoverageController(CoverageReader reader) {
        this.reader = reader;
    }

    /**
     * 覆盖概览。
     *
     * <p>分母是考点总数,分子是有记录的考点数({@code NodeState.covered()}),
     * 判据在 {@code CoverageService.summarize} 里,<b>这里一个数都不算</b>。
     */
    @GetMapping("/summary")
    public SummaryDto summary(CurrentSession session) {
        Snapshot snapshot = reader.read(session.userId());
        return SummaryDto.from(reader.summarize(snapshot));
    }

    /**
     * 盲区 Top N —— <b>北极星指标的落点接口</b>。
     *
     * <h2>没有 {@code orderBy} 参数</h2>
     *
     * docs/technical/INDEX.md §6.4 列了 {@code orderBy=recent5y_count},但排序口径现在只有一个:
     * {@code 近五年频次 × 状态权重}({@code CoverageService.blindSpots})。
     * 开放排序参数等于把口径搬到调用方手里,而这个口径正是产品的判断本身 ——
     * <b>「先补这几个」如果每个客户端都能重排,它就不再是一个回答</b>。
     * 真需要第二种排序时,那是一次产品决定,不是一个查询参数。
     *
     * @param top 要几个。上限 100:这是一份「先补这几个」的清单,不是导出接口 ——
     *            全量导出走 {@code /export}(docs/technical/INDEX.md §6.5),那是另一件事
     */
    @GetMapping("/blindspots")
    public BlindSpotsResponse blindSpots(
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "至少要 1 个")
            @Max(value = 100, message = "一次最多 100 个,全量请走导出接口")
            int top,

            CurrentSession session) {
        Snapshot snapshot = reader.read(session.userId());
        return BlindSpotsResponse.of(top, reader.blindSpots(snapshot, top));
    }
}
