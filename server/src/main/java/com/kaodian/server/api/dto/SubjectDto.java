package com.kaodian.server.api.dto;

import com.kaodian.server.syllabus.Syllabus;

/**
 * 这棵树是哪个省、哪门考试、哪个模块。
 *
 * <p>{@code 一个模块、一个学科起步}(01 §Scope)—— 所以这里是单数,没有列表,
 * 也没有「切换科目」的位置。两棵树同时冷启动被明确称为 2–3 人团队的灾难,
 * 接口形状上不给它留口子。
 *
 * @param recent5yWindow 频次统计的年份窗口,如 {@code 2021-2025}。界面上那句「近五年」指的是它
 * @param display        拼好的展示名,前端不自己拼 —— 拼接规则改了不该要求两端同时发版
 */
public record SubjectDto(
        String code,
        String region,
        String exam,
        String module,
        String recent5yWindow,
        String display
) {
    public static SubjectDto from(Syllabus.Subject s) {
        return new SubjectDto(s.code(), s.region(), s.exam(), s.module(), s.recent5yWindow(), s.display());
    }
}
