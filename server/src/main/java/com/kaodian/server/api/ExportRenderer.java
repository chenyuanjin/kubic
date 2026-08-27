package com.kaodian.server.api;

import com.kaodian.server.api.dto.ExportResponse;
import com.kaodian.server.api.dto.NodeDetailDto;
import com.kaodian.server.api.dto.StateCountDto;
import com.kaodian.server.api.dto.SyllabusNodeDto;
import com.kaodian.server.api.dto.TimelineItemDto;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link ExportResponse} 写成 Markdown 与 CSV。
 *
 * <h2>🔴 两种格式共用同一批「块」,这是「无删减」在代码上的形状</h2>
 *
 * md 与 csv 都只调 {@link #sectionsOf}:同一张列名表、同一批行。
 * 两个渲染器<b>各自决定怎么排版,但拿不到不一样的内容</b> ——
 * 想让 md 比 csv 少一列,得先去改 {@link Section} 的构造,而那是一次会被看见的改动。
 * <p>
 * 分开手写两遍的版本一开始也是对的,问题在半年后:有人给 csv 补了一列,md 没跟上,
 * 而没有任何测试会红。「无删减」不是一句要靠人记住的承诺,它得是<b>默认状态</b>。
 *
 * <h2>🔴 没有水印、没有署名、没有页脚</h2>
 *
 * 这个文件里<b>一个产品名都没有</b>,也没有「由 XX 导出」「Powered by」这类尾巴。
 * 01 §2.6:完整导出是对用户的承诺 —— 你的东西你随时能拿走。
 * 在拿走的那份东西上留记号,是把承诺打了折:一份带尾巴的导出,用户贴进 Obsidian
 * 之前得先手工删一行,而那一行的唯一作用是提醒他这份数据曾经属于我们。<b>它不曾属于我们。</b>
 * <p>
 * 唯一的元信息是「导出时间」,它是<b>快照时刻</b>,是数据的一部分(隔一周再导会得到不同的统计),
 * 不是落款。
 *
 * <h2>不用第三方库</h2>
 *
 * CSV 与 Markdown 都是手写的。理由不是「很简单」,是<b>依赖也是要审的东西</b>:
 * 为了给一个几十 KB 的文本文件加逗号,引入一个会随版本改变输出的库,不划算。
 * 转义规则一共两条(见 {@link #csvCell} 与 {@link #mdCell}),就写在下面。
 */
final class ExportRenderer {

    private ExportRenderer() {
    }

    /**
     * 导出里的一块。
     *
     * @param key     csv 第一列的值,机器读的。md 不用它
     * @param title   md 的小标题,人读的。csv 不用它
     * @param columns 列名。<b>md 的表头与 csv 的表头是同一张表</b>
     * @param rows    每行的单元格,已经全部字符串化,{@code null} 一律已换成空串
     */
    record Section(String key, String title, List<String> columns, List<List<String>> rows) {
    }

    // ———————————————————————— 列名 ————————————————————————
    //
    // 🔴 这五张表就是导出的内容边界(R-06)。每一列要么是我们自己的统计,要么是用户自己录的东西:
    //    来源【名字】、时间、方式、考点 code 与名称、用户自填的两个整数。
    //    没有一列装得下机构的课程内容,也没有一列装得下题干(R-01)——
    //    因为上游的 Touch 与 Syllabus 里根本没有那样的字段可供取用。
    //    往这里加一列之前,先回答它的值从哪个字段来。

    private static final List<String> META_COLUMNS =
            List.of("导出时间", "记录总数", "模块 code", "模块");

    private static final List<String> SUMMARY_COLUMNS =
            List.of("考点总数", "已触达", "空白", "覆盖率", "整块空白的题型");

    private static final List<String> STATE_COLUMNS =
            List.of("状态代码", "状态", "考点数");

    private static final List<String> NODE_COLUMNS = List.of(
            "考点 code", "考点", "题型 code", "题型", "近五年频次",
            "状态代码", "状态", "触达次数", "练了几道", "对了几道", "正确率", "最近触达", "来源");

    private static final List<String> ARCHIVED_COLUMNS = List.of(
            "考点 code", "考点", "题型 code", "题型", "近五年频次", "记录数");

    private static final List<String> RECORD_COLUMNS = List.of(
            "记录 id", "时间", "方式代码", "方式", "来源",
            "考点 code", "考点", "题型 code", "题型", "练了几道", "对了几道");

    /** 空单元格在 md 里显示成这个 —— 与界面上「没练过显示『—』而不是 0%」同一条口径。 */
    private static final String MD_EMPTY = "—";

    /**
     * 一份导出拆成五块,顺序固定。
     *
     * <p>顺序是「先说这是什么,再说整体,最后才是逐条」——
     * 一份存档要能从上往下读下来,而不是打开先撞见三千行流水。
     */
    static List<Section> sectionsOf(ExportResponse e) {
        List<Section> sections = new ArrayList<>();

        sections.add(new Section("meta", "本次导出", META_COLUMNS, List.of(row(
                e.exportedAt(), e.recordCount(), e.subject().code(), e.subject().display()))));

        sections.add(new Section("summary", "覆盖度", SUMMARY_COLUMNS, List.of(row(
                e.summary().total(), e.summary().covered(), e.summary().empty(),
                e.summary().percent() + "%", e.summary().whollyEmptyGroups()))));

        List<List<String>> states = new ArrayList<>();
        for (StateCountDto s : e.summary().distribution()) {
            states.add(row(s.state(), s.label(), s.count()));
        }
        sections.add(new Section("states", "五态分布", STATE_COLUMNS, states));

        List<List<String>> nodes = new ArrayList<>();
        for (NodeDetailDto n : e.nodes()) {
            nodes.add(row(n.code(), n.name(), n.groupCode(), n.groupName(), n.recent5yCount(),
                    n.state(), n.stateLabel(), n.touchCount(), n.practiced(), n.correct(),
                    percent(n.accuracy()), n.latestAt(), join(n.sources())));
        }
        sections.add(new Section("nodes", "考点", NODE_COLUMNS, nodes));

        List<List<String>> archived = new ArrayList<>();
        for (SyllabusNodeDto n : e.archivedNodes()) {
            archived.add(row(n.code(), n.name(), n.groupCode(), n.groupName(),
                    n.recent5yCount(), n.recordCount()));
        }
        sections.add(new Section("archived", "已归档的考点", ARCHIVED_COLUMNS, archived));

        List<List<String>> records = new ArrayList<>();
        for (TimelineItemDto t : e.records()) {
            records.add(row(t.id(), t.occurredAt(), t.kind(), t.kindLabel(), t.sourceName(),
                    t.nodeCode(), t.nodeName(), t.groupCode(), t.groupName(),
                    t.practiced(), t.correct()));
        }
        sections.add(new Section("records", "记录", RECORD_COLUMNS, records));

        return List.copyOf(sections);
    }

    // ———————————————————————— Markdown ————————————————————————

    /**
     * 人能读的那一份 —— 贴进 Obsidian / Notion 就是一篇笔记。
     *
     * <p>只用标题与表格两种语法。不用折叠块、不用脚注、不用 front matter:
     * 那些在各家 Markdown 里表现不一,而这份文件<b>要能被贴到任何地方</b>。
     */
    static String markdown(ExportResponse e) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(e.subject().display()).append("\n");

        for (Section s : sectionsOf(e)) {
            sb.append("\n## ").append(s.title()).append("\n\n");
            if (s.rows().isEmpty()) {
                // 空块也要留着,并且说明它是空的。整块消失会让人以为「导出漏了」,
                // 而「没有已归档的考点」本身就是一条信息。
                sb.append("(没有)\n");
                continue;
            }
            appendMdRow(sb, s.columns());
            appendMdRow(sb, s.columns().stream().map(c -> "---").toList());
            for (List<String> r : s.rows()) {
                appendMdRow(sb, r);
            }
        }
        return sb.toString();
    }

    private static void appendMdRow(StringBuilder sb, List<String> cells) {
        sb.append('|');
        for (String c : cells) {
            sb.append(' ').append(mdCell(c)).append(" |");
        }
        sb.append('\n');
    }

    /**
     * Markdown 表格里的一格。
     *
     * <p>要转义的只有两样:{@code |} 会把一格劈成两格,换行会把一行劈成两行。
     * 两者都来自用户能写的那一个字段 —— 来源名({@code @Size(max = 60)},但内容不限)。
     * <b>转义而不是丢弃</b>:用户填了什么,导出里就该是什么。
     */
    private static String mdCell(String v) {
        if (v == null || v.isEmpty()) {
            return MD_EMPTY;
        }
        return v.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    // ———————————————————————— CSV ————————————————————————

    /**
     * 表格工具能吃的那一份。
     *
     * <h2>为什么是「分块 + 第一列写块名」,而不是一张大表</h2>
     *
     * 五块的列数与含义都不一样,硬拼成一张表要么并列出一堆空格子,要么丢掉几块。
     * 分块之后每块自带表头,第一列是块名({@code meta / summary / states / nodes /
     * archived / records}),于是<b>一个文件里能一眼看出哪几行是记录</b>,
     * 数「无删减」那个数也不用靠猜。
     *
     * <h2>不加 BOM</h2>
     *
     * 加 UTF-8 BOM 能让 Windows 版 Excel 双击直开中文不乱码,但它也会让严格的 CSV 解析器
     * 在第一个表头前多读到三个字节。承诺里写的是「表格工具能吃」,不是「Excel 双击直开」——
     * 响应头已经带了 {@code charset=UTF-8},导入时选一次编码即可。
     */
    static String csv(ExportResponse e) {
        StringBuilder sb = new StringBuilder();
        List<Section> sections = sectionsOf(e);
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            appendCsvLine(sb, "section", s.columns());
            for (List<String> r : s.rows()) {
                appendCsvLine(sb, s.key(), r);
            }
            if (i < sections.size() - 1) {
                sb.append('\n');            // 块与块之间空一行
            }
        }
        return sb.toString();
    }

    private static void appendCsvLine(StringBuilder sb, String first, List<String> cells) {
        sb.append(csvCell(first));
        for (String c : cells) {
            sb.append(',').append(csvCell(c));
        }
        sb.append('\n');
    }

    /**
     * CSV 的一格,按 RFC 4180 转义。
     *
     * <p>含逗号、引号或换行的值整体加引号,内部的引号翻倍。规则就这一条。
     *
     * <p>🔴 <b>不做「公式注入」防护</b>(即给 {@code = + - @} 开头的值加前缀),这是有意的:
     * 那会<b>改掉用户自己写下的字符</b>,而这个端点的全部意义是「原样交还」。
     * 一份被悄悄改过一个字符的导出,比一份需要小心打开的导出更糟。
     */
    private static String csvCell(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    // ———————————————————————— 单元格 ————————————————————————

    /** 一行。{@code null} 在这里就统一成空串,后面两个渲染器都不必再各判一次。 */
    private static List<String> row(Object... cells) {
        List<String> out = new ArrayList<>(cells.length);
        for (Object c : cells) {
            out.add(c == null ? "" : String.valueOf(c));
        }
        return List.copyOf(out);
    }

    /**
     * 用户自填正确率 → 百分比。
     *
     * <p>没练过是空的(md 显示「—」),<b>不是 0%</b> —— 与 {@code NodeDetailDto#accuracy} 同一条口径。
     *
     * <p>取整不丢信息:同一行里「练了几道」「对了几道」两个原始整数都在,
     * 想要精确值随时能自己除。json 那一份给的是未取整的比值(那是 API 契约里的字段),
     * <b>md/csv 与 json 说的是同一个数,只是一个给人看、一个给机器看</b>。
     */
    private static String percent(Double accuracy) {
        return accuracy == null ? null : Math.round(accuracy * 100) + "%";
    }

    /**
     * 来源名集合 → 一格。
     *
     * <p>用分号而不是逗号,是为了让这一格在 csv 里通常不必加引号 —— 肉眼看文件时更清楚。
     * 真出现分号也不会歧义:同一批来源名在「记录」块里<b>每条一行</b>,那才是权威的那一份,
     * 这一格是给人看的汇总。
     */
    private static String join(List<String> sources) {
        return sources == null || sources.isEmpty() ? null : String.join("; ", sources);
    }
}
