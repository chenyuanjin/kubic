package com.kaodian.server.api.insight;

import com.kaodian.server.api.dto.insight.ExportResponse;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.coverage.CoverageReader.Snapshot;
import com.kaodian.server.api.dto.insight.ExportResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全量导出 —— <b>{@code GET /api/export?format=md|csv|json}</b>(docs/技术架构 §6.5)。
 *
 * <h2>为什么挂在 {@code /api/export},而不是 {@code /api/syllabus/export} 底下</h2>
 *
 * §6.5 的契约写的是 {@code /export},与 {@code /records} / {@code /coverage} /
 * {@code /timeline} 平级(契约里的统一前缀是 {@code /api/v1},本仓库目前用 {@code /api},
 * v1 那一位还没引入,所以落到 {@code /api/export})。
 * <p>
 * 它<b>不能</b>挂进 {@code /api/syllabus} 是因为它导的东西横跨三层 ——
 * 骨架(考点)、行为(记录)、覆盖(统计)。挂到其中任何一层下面,都是在说
 * 「这是那一层的一个功能」,而它是整份数据。
 * <p>
 * 🔴 <b>{@code GET /api/syllabus/export} 是另一件事,两者必须并存</b>:
 * 那个导的是<b>骨架树本身</b>(见 {@code SyllabusExportResponse}),
 * 它的格式与 {@code ~/.kaodian/syllabus.json} 一一对应,用途是<b>把文件放回去还能读</b>。
 * 这个导的是<b>用户自己的全量数据</b>,用途是拿走。合并成一个端点会同时毁掉两个用途:
 * 备份格式里混进行为层,放回去就读不了了。
 *
 * <h2>🔴 §6.5 的三条承诺,分别落在哪</h2>
 *
 * <table border="1">
 *   <caption>承诺 → 代码</caption>
 *   <tr><th>承诺</th><th>落点</th></tr>
 *   <tr><td><b>无删减</b></td>
 *       <td>{@link ExportResponse#of} 直接吃 {@code snapshot.touches()} ——
 *           没有 limit、没有 cursor、没有过滤。对比 {@code /api/records} 有 {@code limit}、
 *           {@code /api/coverage/blindspots} 有 {@code top ≤ 100}:
 *           <b>那两个是「先看这些」,这个是「全都给我」</b></td></tr>
 *   <tr><td><b>无水印</b></td>
 *       <td>{@code ExportRenderer} 里一个产品名都没有,没有页脚、没有「由 XX 导出」。
 *           唯一的元信息是快照时刻</td></tr>
 *   <tr><td><b>不限次数</b></td>
 *       <td>这个类<b>没有任何依赖</b>与频控或额度有关 —— 构造器里只有 {@link CoverageReader}。
 *           §6.7 的额度只管 {@code ai_capture} / {@code ai_ask} 两类(替用户花出去的模型钱),
 *           导出不花模型钱,也就没有可收的东西。<b>不是「先不加」,是这条路上没有它的位置</b></td></tr>
 * </table>
 *
 * <h2>🔴 R-06:导得出去的只有名字与数字</h2>
 *
 * 来源只是一个名字,考点是我们自行归纳的 code 与名称,统计是我们自己算的数。
 * <b>机构的课程内容一个字都没有</b> —— 不是导出时过滤掉的,是上游根本没有这样的字段
 * (决策记录 §2.2 不碰内容 / docs/技术架构 §5.1「不是不填,是不建这个列」)。
 * 题干同理(R-01):数据模型里没有,这里也就无处可拼。
 *
 * <h2>控制器里没有一行渲染逻辑</h2>
 *
 * 取数走 {@link CoverageReader}(接口层唯一的取数入口,覆盖率的口径只有那一处),
 * 排版走 {@code ExportRenderer}。这里只做三件事:认格式、组装、贴响应头。
 */
@RestController
public class ExportController {

    /** 下载文件名里的日期。用 UTC —— 与 {@code Clock.systemUTC()} 同一个时区,免得文件名和内容差一天。 */
    private static final DateTimeFormatter FILE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final CoverageReader reader;

    public ExportController(CoverageReader reader) {
        this.reader = reader;
    }

    /**
     * 全量导出。
     *
     * <h2>{@code format} 是必填的,没有默认值</h2>
     *
     * 挑一个当默认,等于替用户决定了哪一种才是「正经的」导出,而三种写法装的是同一份数据
     * (见 {@link ExportFormat})。少填一个参数换来一句明确的 400,比换来一份不是你想要的文件好。
     *
     * <h2>为什么带 {@code Content-Disposition}</h2>
     *
     * 「你的东西你随时能拿走」(决策记录 §2.6)在浏览器里要能<b>真的落到硬盘上</b>,
     * 而不是在标签页里渲染成一屏文本再让用户另存为。
     * <p>
     * ⚪ 一个已知的小缺口:{@code Content-Disposition} 不是简单响应头,跨域场景下前端 JS
     * 读不到它(要在 CORS 里 {@code exposedHeaders} 显式暴露)。用 {@code <a download>}
     * 直接跳转不受影响,所以这一版没有去动 {@code ApiCorsConfig} ——
     * <b>那份配置是「一处声明」的,改它属于跨域策略的决定,不是导出功能顺手能带的</b>。
     */
    @GetMapping("/api/export")
    public ResponseEntity<Object> export(@RequestParam String format) {
        ExportFormat fmt = ExportFormat.ofWireName(format);

        Snapshot snapshot = reader.read();
        ExportResponse data = ExportResponse.of(
                snapshot.at(), snapshot.syllabus(), reader.summarize(snapshot),
                snapshot.groups(), snapshot.touches());

        Object payload = switch (fmt) {
            case JSON -> data;
            case MD -> ExportRenderer.markdown(data);
            case CSV -> ExportRenderer.csv(data);
        };

        return ResponseEntity.ok()
                .contentType(fmt.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileNameOf(data, fmt) + "\"")
                .body(payload);
    }

    /**
     * 下载文件名,如 {@code export-sd-xingce-ziliao-20260827.csv}。
     *
     * <p>🔴 <b>模块 code 必须过一遍白名单过滤</b>:它来自 {@code ~/.kaodian/syllabus.json},
     * 是用户自己能编辑的文件。原样拼进 {@code Content-Disposition} 会带来两件事 ——
     * 一个中文 code 让这个头不再是 ASCII(得走 RFC 5987 那套编码),
     * 一个带引号或换行的 code 则是往响应头里注入。这里只留 {@code [A-Za-z0-9._-]},
     * 其余一律换成 {@code -}。
     *
     * <p>文件名里<b>没有产品名</b>,理由与「无水印」同一条:这是用户的文件。
     */
    private static String fileNameOf(ExportResponse data, ExportFormat fmt) {
        StringBuilder code = new StringBuilder();
        for (char c : data.subject().code().toCharArray()) {
            code.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' ? c : '-');
        }
        return "export-" + code + "-" + FILE_DATE.format(data.exportedAt()) + fmt.fileSuffix();
    }
}
