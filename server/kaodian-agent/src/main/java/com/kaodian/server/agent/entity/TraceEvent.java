package com.kaodian.server.agent.entity;

import java.time.Instant;

/**
 * 一条轨迹事件 —— 七阶段控制流在存储里的投影。
 *
 * <p>用 {@code phase} + {@code name} 两级定位:phase 是 {@code boot / plan / act / conclude / emit},
 * name 用 {@code {phase}.{动作}} 或 {@code act.tool.{工具名}.{动作}}。
 *
 * <p><b>没有 stepId。</b>truman-ai 那边有一个(run 内自增的 step_1 / step_2,用于关联与排序),
 * 照抄过来时它被填成了和 {@code phase} 一模一样的值 —— 第一次看落盘的 ndjson 才发现:
 * 一个恒等于隔壁字段的「序号」。排序已经有 {@code timestamp} 管,关联已经有 {@code runId} 管,
 * 于是直接删掉,而不是补一个自增计数器去喂它。<b>字段要么有人用,要么不存在</b>;
 * 留一个填了值却没有含义的字段,下一个人会认真地按它去排序。
 *
 * <p>刻意<b>没有</b> tags 这样的自由 Map(truman-ai 那边有,用来塞 prompt 全文和真实入参)。
 * 一个 {@code Map<String,Object>} 是这一层最好的藏内容的地方 ——
 * 谁往里放一份题干,既不会报错,也不会被任何一条红线断言看见(它们看的是<b>字段名</b>)。
 * 要加结构化调试信息时,加具名字段,让它出现在 NoStemFieldTest 的扫描范围里。
 *
 * @param status  SUCCESS / FAILED / INFO
 * @param detail  人类可读的一句话摘要
 */
public record TraceEvent(
        String runId,
        String phase,
        String name,
        String status,
        long durationMs,
        String detail,
        Instant timestamp
) {
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_INFO = "INFO";
}
