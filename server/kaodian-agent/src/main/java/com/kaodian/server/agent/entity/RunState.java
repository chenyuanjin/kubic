package com.kaodian.server.agent.entity;

/**
 * 一次 Run 的生命周期状态。
 *
 * <p>比 truman-ai 的同名枚举少了一半:那边有 AWAIT_USER / BLOCKED_SENSITIVE / BLOCKED_UPSTREAM /
 * DEGRADED_L1..L3 等十几个态,每一个都对应一条它那边真实发生过的线上事故。
 * <b>这里不预先搬进来</b> —— 一个还没有任何真实用户的 agent,搬进来的只是状态名,
 * 不是那些状态背后的判断力,而空状态最擅长的事情是被人随手用错。
 * 哪天真撞上了对应的情形,再加对应的那一个。
 */
public enum RunState {

    /** 已建档,还没开始跑。 */
    PENDING,

    RUNNING,

    SUCCEEDED,

    /** 上游模型报错、工具全崩、或者流中途断了。 */
    FAILED,

    /**
     * 工具轮次撞上上限({@code kaodian.agent.max-tool-rounds})后收尾。
     *
     * <p>它<b>不是</b> FAILED:该轮已经产出的文字仍然发给了用户,只是没跑完。
     * 单独一个状态是为了让「模型在原地打转」这件事在存储里可数 ——
     * 混进 FAILED 就查不出来了,而它恰恰是提示词或工具描述写坏了的第一信号。
     */
    TOOL_LOOP_LIMIT
}
