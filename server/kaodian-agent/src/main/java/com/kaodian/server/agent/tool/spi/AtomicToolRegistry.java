package com.kaodian.server.agent.tool.spi;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具池注册中心 —— 启动时把所有 {@link AgentTool} 上带 {@link AtomicTool} 的方法扫成一张表。
 *
 * <h2>🔴 这张表就是能力边界的白名单本身</h2>
 *
 * 模型能做的事情,<b>等于这张表里的条目</b>。它不是一份「建议清单」:
 * {@code AgentToolBridge} 执行工具前会回来查这张表,查不到就拒绝执行。
 * 于是「agent 会不会越界」这个问题被化简成了一个可以当场读完的列表 ——
 * 这正是 docs/后端详设 §4.1 那条绿线(「除 recognize 外无 ChatModel 注入点」)在 agent 这一侧的对应物:
 * 边界不是靠提示词里写「你不要判断对错」守住的(那是<b>请求</b>模型别做),
 * 是靠「判断对错所需要的数据,一个工具都拿不到」守住的(那是<b>让它做不到</b>)。
 *
 * <p>所以往这张表里加工具是一件需要停下来想一想的事,尤其是任何会返回<b>题目内容</b>
 * 或者<b>对错判定</b>的工具 —— 那两样一旦进来,产品定义就变了,而代码不会报任何错。
 *
 * <h2>为什么在构造器里扫,而不是等 ApplicationReadyEvent</h2>
 *
 * truman-ai 那边用 {@code @Lazy ApplicationContext} + {@code ApplicationReadyEvent},
 * 是因为它的工具 bean 之间有循环依赖。我们这里工具都是叶子节点(只依赖 domain 的只读服务),
 * 构造器注入 {@code List<AgentTool>} 就够,而且这样<b>扫描失败会让应用起不来</b> ——
 * 比起来,起得来但工具池是空的要糟得多:那种情况下 agent 会安静地退化成一个什么都查不到的聊天框。
 */
@Component
public class AtomicToolRegistry {

    private final Map<String, ToolMetadata> byName = new LinkedHashMap<>();
    private final List<AgentTool> tools;

    public AtomicToolRegistry(List<AgentTool> tools) {
        this.tools = List.copyOf(tools);
        for (AgentTool tool : tools) {
            scan(tool);
        }
    }

    private void scan(AgentTool tool) {
        for (Method m : tool.getClass().getMethods()) {
            AtomicTool atomic = m.getAnnotation(AtomicTool.class);
            Tool springAi = m.getAnnotation(Tool.class);
            if (atomic == null) {
                continue;
            }
            if (springAi == null) {
                // 只挂一半注解 = 这个方法要么模型看不见(没有 @Tool),要么编排层管不住(没有 @AtomicTool)。
                // 两种都是半成品,当场失败比上线后发现工具池少一个要好。
                throw new IllegalStateException(
                        "方法 " + m.getDeclaringClass().getSimpleName() + "#" + m.getName()
                                + " 挂了 @AtomicTool 却没有 @Tool —— 两个注解是一对,"
                                + "缺 @Tool 的话模型根本看不到这个工具,而编排层却以为它在池子里。");
            }
            String name = springAi.name().isBlank() ? m.getName() : springAi.name();
            ToolMetadata previous = byName.put(name, new ToolMetadata(
                    name, atomic.level(), atomic.displayName(),
                    atomic.noun(), atomic.verb(), springAi.description()));
            if (previous != null) {
                throw new IllegalStateException(
                        "工具名重复:「" + name + "」被注册了两次。"
                                + "工具名是贯穿日志、存储、前端显示的唯一标识,重名会让排查时的记录对不上号。");
            }
        }
    }

    /** 工具实例本身,交给 spring-ai 去反射生成 tools schema。 */
    public List<AgentTool> instances() {
        return tools;
    }

    public Collection<ToolMetadata> listAll() {
        return byName.values();
    }

    /** 查不到返回 null —— 调用方(AgentToolBridge)据此拒绝执行未注册的工具。 */
    public ToolMetadata find(String toolName) {
        return byName.get(toolName);
    }

    public int size() {
        return byName.size();
    }
}
