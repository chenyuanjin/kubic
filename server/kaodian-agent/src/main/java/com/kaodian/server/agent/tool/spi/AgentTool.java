package com.kaodian.server.agent.tool.spi;

/**
 * 标记接口,只为让 Spring 能按 {@code List<AgentTool>} 把工具收集起来。
 *
 * <p>没有任何方法 —— 工具的形状由 {@link AtomicTool} 注解和方法签名决定,不由继承决定。
 */
public interface AgentTool {
}
