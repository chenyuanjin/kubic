package com.kaodian.server.agent.tool.spi;

/**
 * 从 {@link AtomicTool} + spring-ai {@code @Tool} 上抽出来的一份工具元信息。
 */
public record ToolMetadata(
        String name,
        ToolLevel level,
        String displayName,
        String noun,
        String verb,
        String description
) {

    public boolean isEffect() {
        return level == ToolLevel.EFFECT;
    }

    /** 发到前端时优先中文,没配就退回英文 slug —— 缺注解不该让界面出现空白。 */
    public String label() {
        return displayName == null || displayName.isBlank() ? name : displayName;
    }
}
