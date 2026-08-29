package com.kaodian.server.agent.tool.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 原子工具的元信息,叠加在 spring-ai 的 {@code @Tool} 旁边。
 *
 * <p>为什么不直接用 spring-ai 的 {@code @Tool} 就够了:{@code @Tool} 只描述
 * 「这个方法叫什么、干什么」——那是<b>给模型看的</b>。这个注解描述的是
 * 「它属于哪一层、能不能并行、中文叫什么」——那是<b>给我们的编排层看的</b>。
 * 两者混在一起的下场是:想给编排层加一个判据,就得改所有工具发给模型的描述文案。
 *
 * @see ToolLevel
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AtomicTool {

    ToolLevel level();

    /**
     * 中文显示名(「覆盖率查询」「盲区清单」)。
     *
     * <p>发到前端的 tool-call 帧里带上它,让「正在使用 coverage_query」变成「正在查覆盖率」。
     * 不改 {@code @Tool.name} —— 英文 slug 仍是贯穿日志、存储、幂等键的唯一标识。
     */
    String displayName() default "";

    /** 命名规范 {noun}_{verb} 的两半,便于按名词聚类看工具池。 */
    String noun() default "";

    String verb() default "";
}
