package com.kaodian.server.agent;

import com.kaodian.server.agent.tool.spi.AtomicTool;
import com.kaodian.server.agent.tool.spi.ToolLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>能力边界在 agent 这一侧的两道物理防线。</b>
 *
 * <h2>为什么 agent 需要自己的边界断言</h2>
 *
 * docs/technical/后端系统设计与组件接入.md §4.1 定过一条绿线:{@code ChatModel} / {@code ChatClient} 不得越过
 * {@code recognize} 的实现类,理由第三条是「任何人都能在别处 {@code @Autowired ChatModel}
 * 直接问模型,<b>能力边界就没有物理形态了</b>」。
 * <p>
 * 接一个通用对话 agent 与那条规定<b>直接冲突</b> —— agent 本来就是要问模型的。
 * 处理办法不是给它开一个例外(例外没有形状,下一个人会照着开第二个),
 * 而是<b>把同一条纪律在 agent 侧再执行一遍</b>,并且用断言钉住:
 *
 * <ol>
 *   <li>{@link #springAiStaysInsideLlmPackage} —— spring-ai 的类型只许出现在 {@code agent.llm} 一个包里。
 *       模型接入的注入点仍然可数,而且每一处都有名字</li>
 *   <li>{@link #toolPoolIsReadOnly} —— 工具池里不许有 EFFECT 级工具。
 *       模型能做的事情由工具池封顶</li>
 * </ol>
 *
 * <h2>两条都红过</h2>
 *
 * ① 往 {@code orchestrator/Orchestrator.java} 里加一行
 * {@code import org.springframework.ai.chat.model.ChatModel;},输出是
 * {@code com/kaodian/server/agent/orchestrator/Orchestrator.java:3} 加上那一行原文。
 * ② 把 {@code RecordTools#timeNow} 的 level 改成 {@code EFFECT},输出是
 * {@code RecordTools#timeNow(当前时间)}。两次都验完即改回。
 * <b>一条从来没红过的断言等于没有</b> —— 改动本类的判定逻辑后请照样再塞一次。
 */
class AgentBoundaryTest {

    /**
     * 唯一允许出现 spring-ai 类型的包(相对 {@code src/main/java} 的路径前缀)。
     *
     * <p>为什么是包而不是「几个类名」:类会改名、会被拆开,而包不会自己搬家。
     * 钉包意味着新增一个实现类({@code DashScopeAgentLlm} 之类)不用回来改这个测试,
     * 但把它写到 {@code orchestrator} 里去就会当场红。
     */
    private static final String LLM_PACKAGE = "com/kaodian/server/agent/llm/";

    /**
     * 被盯着的 import 前缀。
     *
     * <p>{@code org.springframework.ai.tool.annotation.Tool} 是<b>例外</b>,单独放行 ——
     * 见 {@link #ALLOWED_OUTSIDE}。
     */
    private static final String SPRING_AI = "org.springframework.ai.";

    /**
     * 允许出现在 {@code llm} 包之外的 spring-ai 类型。
     *
     * <p>只有工具注解这一组。理由:{@code @Tool} / {@code @ToolParam} 是<b>声明</b>,不是能力 ——
     * 它们描述「这个方法叫什么、参数是什么意思」,拿着它们<b>问不了模型</b>。
     * 而绿线防的是「有人在别处直接问模型」。
     * <p>
     * 把它们赶进 llm 包的唯一办法是自己造一套等价注解再做一层映射 ——
     * 那会为了一条断言的整洁,凭空多出一层每加一个工具都要维护的转换。
     * <b>例外要窄、要有名字、要写清楚为什么</b>,而不是不留例外然后被人整条注释掉。
     */
    private static final List<String> ALLOWED_OUTSIDE = List.of(
            "org.springframework.ai.tool.annotation.Tool",
            "org.springframework.ai.tool.annotation.ToolParam");

    @Test
    @DisplayName("🔴 绿线:spring-ai 的类型只许待在 agent.llm 包里")
    void springAiStaysInsideLlmPackage() {
        Path root = mainJava();
        List<String> hits = new ArrayList<>();
        int scanned = 0;

        for (Path file : sources(root)) {
            scanned++;
            String relative = root.relativize(file).toString();
            if (relative.replace('\\', '/').startsWith(LLM_PACKAGE)) {
                continue;   // 这里就是模型接入本身,允许
            }
            String[] lines = read(file).split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].strip();
                if (!line.startsWith("import ") || !line.contains(SPRING_AI)) {
                    continue;
                }
                if (ALLOWED_OUTSIDE.stream().anyMatch(line::contains)) {
                    continue;
                }
                hits.add("  " + relative + ":" + (i + 1) + "\n      >>> " + line);
            }
        }

        // 扫 0 个文件也会「通过」。把路径解析本身也变成断言的一部分。
        assertTrue(scanned >= 15,
                "只扫到 " + scanned + " 个源文件,源码树定位坏了(root=" + root + ")");

        assertTrue(hits.isEmpty(), () -> """
                🔴 spring-ai 的类型跑到了 agent.llm 之外:

                %s

                这条守的是 docs/technical/后端系统设计与组件接入.md §4.1 的绿线在 agent 侧的形态:模型接入必须封在 AgentLlm 接口后面,
                注入点保持可数。一旦 ChatModel / ChatClient 能在编排层、工具层被直接拿到,
                「能力边界」就退回成一句写在提示词里的请求 —— 而提示词是模型可以不听的。

                正确处置:把要用的能力加到 AgentLlm 接口上,在 llm 包里实现它。
                不是把这个文件加进白名单。
                """.formatted(String.join("\n", hits)));
    }

    @Test
    @DisplayName("🔴 能力边界:工具池里不许有 EFFECT 级工具")
    void toolPoolIsReadOnly() {
        List<String> effects = new ArrayList<>();

        for (Class<?> type : toolClasses()) {
            for (Method m : type.getMethods()) {
                AtomicTool atomic = m.getAnnotation(AtomicTool.class);
                if (atomic != null && atomic.level() == ToolLevel.EFFECT) {
                    effects.add("  " + type.getSimpleName() + "#" + m.getName()
                            + "(" + atomic.displayName() + ")");
                }
            }
        }

        assertTrue(effects.isEmpty(), () -> """
                🔴 工具池里出现了 EFFECT 级(有副作用)工具:

                %s

                agent 现在是【只读】的,这是刻意的:能力边界说的是不判断对错,
                而一个能写的 agent 还会带来另一个问题 —— 模型误判一次,用户的覆盖率数据就被改了,
                而覆盖率是这个产品唯一的那个数(决策记录 §2.2)。

                真要加写操作,那是一个产品决定,不是一次重构:
                先在 docs/execution/INDEX.md §四 记一条风险,再决定 AgentToolBridge 那道门怎么放行。
                """.formatted(String.join("\n", effects)));
    }

    @Test
    @DisplayName("扫描没落空 —— 工具池不该是空的")
    void toolPoolIsNotEmpty() {
        long tools = toolClasses().stream()
                .flatMap(t -> Stream.of(t.getMethods()))
                .filter(m -> m.getAnnotation(AtomicTool.class) != null)
                .count();

        // 上一条断言在工具池为空时会「通过」。这里给一个下限:
        // 空池子意味着 agent 什么都查不到,而那种退化【不会报错】——
        // 它表现为模型开始凭空回答,正是最该被拦住的那种失败。
        assertTrue(tools >= 4, "只扫到 " + tools + " 个工具,工具池定位坏了或者被清空了");
    }

    // ——————————————————— 辅助 ———————————————————

    /**
     * 工具实现类。这里<b>写死类名</b>而不是扫包:测试不起 Spring 上下文,
     * 而 {@code AtomicToolRegistry} 的收集靠的是容器注入。
     * 漏登记一个类的后果由 {@link #toolPoolIsNotEmpty} 的下限兜住。
     */
    private static List<Class<?>> toolClasses() {
        return List.of(
                com.kaodian.server.agent.tool.impl.CoverageTools.class,
                com.kaodian.server.agent.tool.impl.RecordTools.class);
    }

    /** {@code kaodian-agent/src/main/java}。Maven 从模块目录或从 server/ 跑,两种都要认。 */
    private static Path mainJava() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = cursor; p != null; p = p.getParent()) {
            for (Path candidate : List.of(p, p.resolve("kaodian-agent"), p.resolve("server/kaodian-agent"))) {
                Path hit = candidate.resolve("src/main/java/com/kaodian/server/agent");
                if (Files.isDirectory(hit)) {
                    return candidate.resolve("src/main/java");
                }
            }
        }
        throw new IllegalStateException("找不到 kaodian-agent/src/main/java(user.dir=" + cursor + ")");
    }

    private static List<Path> sources(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
