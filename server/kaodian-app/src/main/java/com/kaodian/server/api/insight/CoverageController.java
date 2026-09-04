package com.kaodian.server.api.insight;

import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.api.dto.insight.BlindSpotsResponse;
import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.config.BlindspotCaliber;
import com.kaodian.server.coverage.BlindspotFilter;
import com.kaodian.server.coverage.BlindspotOrder;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.coverage.CoverageReader.Snapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 差集本身的两个端点:一个是那三个数,一个是「先补这几个」。
 *
 * <p>🔴 <b>这里一个数都不算。</b> 口径全部在 {@code CoverageService} 里 ——
 * 两处算同一个数就一定会算出两个数。这个类只做三件事:把 {@code userId} 递进去、
 * 把闭集参数翻成枚举(翻不动就报错,<b>不静默按默认走</b>)、把结果转成 DTO。
 */
@RestController
@RequestMapping("/api/v1/coverage")
public class CoverageController {

    private final CoverageReader reader;

    public CoverageController(CoverageReader reader) {
        this.reader = reader;
    }

    /**
     * 覆盖概览 —— <b>那三个数</b>。
     *
     * <p>🔴 骨架为空走 {@code 422 SYLLABUS_EMPTY},<b>不是返回一堆 0</b>:
     * {@code {"nodeTotal": 0}} 在语法上完全合法,而那个「0 个考点」是一句假话
     * ({@code U3.1} §2.4)。整屏进空态,一个数都不显示。
     */
    @GetMapping("/summary")
    public SummaryDto summary(CurrentSession session) {
        Snapshot snapshot = reader.read(session.userId());
        requireSyllabusNotEmpty(snapshot);
        // statsAsOfYear 传 null —— 骨架层今天没有这个事实的来源,「没数过」那一档
        // 的正确形态就是 key 不出现(§二),不是补一个 0。
        return SummaryDto.of(reader.summarize(snapshot), null);
    }

    /**
     * 「先补这几个」。
     *
     * <h2>🔴 没有 {@code top} 参数</h2>
     *
     * N 的唯一来源是 {@code GET /config/effective} 的 {@code blindspotTop},服务端在这里执行它,
     * 响应里回显实际用的 {@code top}。上一版是 {@code @RequestParam(defaultValue = "20") int top} ——
     * <b>只要 {@code top} 还是一个参数,「前端不硬编码」就只能靠自觉,而端上写下
     * {@code top=20} 的那一刻不会有任何东西报错</b>(§9.3 / §十四 增量 1)。
     *
     * <h2>🔴 未知 {@code orderBy} 与未知 {@code filter} 不是同一档</h2>
     *
     * <table border="1">
     *   <caption>两个闭集参数的错误档不同</caption>
     *   <tr><th>参数</th><th>未知值</th><th>为什么</th></tr>
     *   <tr><td>{@code orderBy}</td><td>{@code 422 UNKNOWN_ORDER_BY}</td>
     *       <td>它<b>有</b>服务端默认值,于是存在「静默按默认返回」这个诱惑 ——
     *           而静默之后端永远不知道自己传错了,屏上那句口径说明会一直在撒谎</td></tr>
     *   <tr><td>{@code filter}</td><td>{@code 400 INVALID_ARGUMENT}</td>
     *       <td>它<b>没有</b>服务端默认值,不存在那个诱惑;界面是四选一段控,
     *           用户选不出非法值 —— 走到这里是端上的 bug,而 <b>bug 不是一档</b>,
     *           所以不给它专码</td></tr>
     * </table>
     *
     * @param orderBy      不传 → 用服务端默认,响应回显
     * @param filter       不传 → {@code untouched}
     * @param hasStatsOnly 「只看有出现次数记录的」,不传 → {@code false}
     */
    @GetMapping("/blindspots")
    public BlindSpotsResponse blindSpots(
            @RequestParam(required = false) String orderBy,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false, defaultValue = "false") boolean hasStatsOnly,
            CurrentSession session) {

        // 🔴 直接读那个常量,不注入一个 bean。注入的那一版让「同一个数只许有一个来源」
        // 变成一句需要靠约定维持的话 —— 任何一处 @Bean BlindspotCaliber 都能悄悄换掉它,
        // 而 GET /config/effective 与这里就会下发两个不同的默认值,谁都不报错。
        BlindspotOrder order = BlindspotCaliber.DEFAULT.orderBy();
        if (orderBy != null) {
            order = BlindspotOrder.of(orderBy);
            if (order == null) {
                throw ApiException.unknownOrderBy(orderBy);
            }
        }
        BlindspotFilter chosen = BlindspotFilter.UNTOUCHED;
        if (filter != null) {
            chosen = BlindspotFilter.of(filter);
            if (chosen == null) {
                throw ApiException.invalidArgument("filter", filter);
            }
        }

        Snapshot snapshot = reader.read(session.userId());
        requireSyllabusNotEmpty(snapshot);
        int top = BlindspotCaliber.DEFAULT.top();
        return BlindSpotsResponse.of(order, top,
                reader.blindSpots(snapshot, order, chosen, hasStatsOnly, top));
    }

    /**
     * 🔴 骨架还没建好是<b>一档独立结果</b>,不是空数组、也不是一堆 0。
     *
     * <p>{@code U4.4}:缺骨架 ≠ 请求失败,所以它也不是 {@code 500}。
     * 三档必须能被端分开:{@code 200} 空清单(数过了,你都碰过)、
     * {@code 422 SYLLABUS_EMPTY}(这个科目还没建树)、{@code 5xx}(我们坏了)。
     */
    private void requireSyllabusNotEmpty(Snapshot snapshot) {
        if (snapshot.syllabus().allNodesIncludingArchived().isEmpty()) {
            throw ApiException.syllabusEmpty();
        }
    }
}
