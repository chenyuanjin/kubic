package com.kaodian.server.api;

import org.springframework.http.MediaType;

/**
 * 全量导出的三种写法 —— <b>md / csv / json</b>(docs/10 §6.5)。
 *
 * <h2>三个值是并列的,没有默认值</h2>
 *
 * {@code format} 在 {@link ExportController} 上是必填参数。挑一个当默认,等于替用户
 * 决定了「哪一种才是正经的导出」,而 §6.5 的承诺是<b>三种写法装的是同一份数据</b> ——
 * 一旦有了默认值,另外两种就会慢慢变成没人跑的分支,然后在某次改动里悄悄少一列。
 * <p>
 * 所以这里也<b>没有</b>「格式排序」「主格式」这类概念,枚举顺序只是书写顺序。
 *
 * <h2>为什么 contentType 是方法而不是字段</h2>
 *
 * 两个理由,后一个更硬:
 * <ol>
 *   <li>这三个值只在响应出口用一次,存成实例字段并不省什么;</li>
 *   <li>🔴 一个叫 {@code contentType} 的 {@code String} 字段会被
 *       {@code NoStemFieldTest} 的字段名黑名单命中(命中「content」)。那条断言没有白名单,
 *       而且<b>它拦得对</b> —— 红线管的是「库里不许有能装内容的位置」,
 *       靠的正是不去分辨「这个 content 是好的、那个是坏的」。绕过它的写法只有一种:
 *       别把这类词做成字段。</li>
 * </ol>
 */
public enum ExportFormat {

    /** 人能读的那一份 —— 贴进 Obsidian / Notion 就是一篇笔记。 */
    MD,

    /** 表格工具能吃的那一份。 */
    CSV,

    /** 机器能吃的那一份,字段名与接口契约逐字一致。 */
    JSON;

    /**
     * 请求参数 → 枚举。
     *
     * <p>大小写不敏感:{@code ?format=JSON} 与 {@code ?format=json} 是同一件事,
     * 让一次大小写打错变成 400 没有任何好处。
     *
     * <p>🔴 报错走 {@link ApiException#unknownValue},<b>回声必须截断</b> ——
     * {@code format} 是查询参数,没有 {@code @Size} 管得着它,原样回显等于给
     * 「把一整段题干写进响应体和访问日志」开了一条最不起眼的路(01 §2.2 不碰内容)。
     */
    public static ExportFormat ofWireName(String s) {
        if (s != null) {
            for (ExportFormat f : values()) {
                if (f.name().equalsIgnoreCase(s.trim())) {
                    return f;
                }
            }
        }
        throw ApiException.unknownValue("UNKNOWN_EXPORT_FORMAT", "导出格式(只认 md / csv / json)", s);
    }

    /** 响应的媒体类型。三种都显式带 {@code charset=UTF-8} —— 导出的内容大部分是中文。 */
    public MediaType mediaType() {
        return switch (this) {
            case MD -> MediaType.parseMediaType("text/markdown;charset=UTF-8");
            case CSV -> MediaType.parseMediaType("text/csv;charset=UTF-8");
            case JSON -> MediaType.parseMediaType("application/json;charset=UTF-8");
        };
    }

    /** 下载文件名的后缀。 */
    public String fileSuffix() {
        return switch (this) {
            case MD -> ".md";
            case CSV -> ".csv";
            case JSON -> ".json";
        };
    }
}
