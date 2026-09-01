package com.kaodian.server.redline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 🔴 <b>R-51:能力边界的后门在自动装配里,不在业务代码里。</b>
 *
 * <h2>为什么这条断言今天是空的 —— 别删它</h2>
 *
 * 本仓库唯一一条<b>今天必然通过</b>的断言。此刻 {@code server/pom.xml} 里根本没有 spring-ai
 * (只有 webmvc / validation / json / lombok),所以扫描结果当然是零命中。
 * 它的全部价值在<b>将来的某一天</b>:{@code docs/technical/后端系统设计与组件接入.md} §三 已经定了要接
 * {@code spring-ai 2.0.1 GA} + OpenAI 兼容端点,而
 * <b>{@code spring-ai-starter-model-openai} 会把 chat / embedding / image / audio-speech /
 * audio-transcription 整套模型一起自动装配</b>。加依赖的那一刻,这条断言从「空转」变成「唯一在看着的人」——
 * 而那一刻恰恰是最容易顺手把整套模型装进来的时刻:没人是故意开后门的,是 starter 替你开的。
 *
 * <p>后门具体长什么样:一个装配好的 {@code OpenAiImageModel} 摆在容器里,
 * 「让模型画个图」在技术上随时可用 —— 它不需要任何人写一行新代码去「越界」,
 * 只需要有人 {@code @Autowired} 它一下。而<b>能力边界(只答「有没有、几次、多久前」)是这个产品的产品定义本身</b>,
 * 不是一条可以事后补的规范。防线写在 {@code docs/technical/后端系统设计与组件接入.md} §4.2:{@code spring.autoconfigure.exclude} 显式排除。
 *
 * <p><b>它红过。</b>写完当场往上下文里塞了一个叫 {@code openAiImageModel} 的假 bean,
 * 输出是:{@code 命中 .*ImageModel(匹配到 openAiImageModel)—— 文生图},
 * 并把来源 {@code @Configuration} 反推出来直接给了一行可粘贴的 {@code spring.autoconfigure.exclude=...}。
 * 验完即删。<b>一条从来没红过的断言等于没有</b> —— 改动本类的判定逻辑后请照样再塞一次。
 *
 * <h2>⚠ 这条 tripwire 可能不是「变红」,而是「起不来」</h2>
 *
 * {@code docs/technical/后端系统设计与组件接入.md} §三的实测记录:探针里<b>不给 api-key 时,上下文直接在
 * {@code OpenAiAudioSpeechModel} 启动失败</b>({@code At least one credential source must be specified})——
 * 因为那几个模型是<b>急切实例化</b>的。所以撞上 R-51 的人看到的很可能不是本类的断言失败,
 * 而是<b>整个测试套件的上下文加载失败</b>,报错里一个字也没提能力边界。
 * 排查到那一步的人请回到这里:结论是一样的 —— 去 {@code application.properties} 加 exclude,
 * 不是去给 speech 模型补一把 key。
 *
 * <h2>为什么是 {@code @SpringBootTest} 而不是切片</h2>
 *
 * {@code @WebMvcTest} 之类的切片<b>只跑白名单里的那几个自动装配类</b>,spring-ai 的自动装配根本不在名单上 ——
 * 用切片来查「自动装配装出了什么」等于让被查的对象自己缺席。这里要的就是<b>真实的完整装配结果</b>,
 * 所以只能起整个上下文。代价可以接受:本类不带任何 {@code properties}/{@code MockBean} 定制,
 * 与 {@code KaodianServerApplicationTests} 的上下文缓存键<b>完全一致</b>,同一次构建里两者共用一份上下文,
 * 实际增量接近零。<b>后来的人若为了「跑得快点」把它降级成切片,这条断言就静默失效了</b>——
 * {@code scanSeesAWholeApplicationContext} 那条兜底断言防的就是这个。
 *
 * <p>本机能起得来:{@code server.address=127.0.0.1} 且默认 {@code webEnvironment=MOCK}(不真绑端口),
 * 鉴权供应商默认 logging/disabled,不需要任何外部依赖。
 */
@SpringBootTest
class NoGenerativeModelBeanTest {

    /**
     * 一条禁令 = 一个类型名模式 + 它对应的那个「随时可用的能力」。
     *
     * <p>匹配<b>类型名</b>而不是具体类:厂商类名会变(OpenAI / DashScope / 换 starter),
     * 但 spring-ai 的命名约定不会 —— 能力叫什么,类就叫什么。钉约定比钉类名活得久。
     *
     * <p>用 {@code .*Xxx} 后缀而不是 {@code contains}:留出一点精度,别把
     * {@code VisionTagger}、{@code StubAsrClient} 这些<b>我们自己的、跑在能力边界之内</b>的东西误伤。
     * 反过来,宁可误伤也不放过 —— 撞上误报的人花五秒改一行模式,撞上漏报的人则永远不知道自己越了界。
     */
    private static final List<Forbidden> FORBIDDEN = List.of(
            new Forbidden(".*ImageModel", "文生图"),
            new Forbidden(".*ImageClient", "文生图(旧版命名)"),
            new Forbidden(".*ImageGeneration.*", "文生图(选项/响应/属性)"),
            new Forbidden(".*SpeechModel", "文本转语音"),
            new Forbidden(".*AudioSpeech.*", "文本转语音(实测的启动失败点)"),
            new Forbidden(".*EmbeddingModel", "向量化 —— 会顺手长出「相似题推荐」,那是教研"),
            new Forbidden(".*TranscriptionModel", "语音转写(必须走我们自己的 AsrClient)"),
            new Forbidden(".*AudioTranscription.*", "语音转写(选项/响应/属性)"),
            new Forbidden(".*VideoModel", "文生视频"),
            new Forbidden(".*ModerationModel", "内容审核 —— 它要求把内容送出去,而我们不碰内容"));

    /**
     * 拿不到来源自动装配类时给的兜底修法,抄自 {@code docs/technical/后端系统设计与组件接入.md} §4.2。
     * 类名以实际引入的 spring-ai 版本为准,动手时用 {@code --debug} 看一遍自动装配报告。
     */
    private static final String FALLBACK_EXCLUDE = """
            spring.autoconfigure.exclude=\\
              org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration,\\
              org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration,\\
              org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration,\\
              org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration""";

    @Autowired
    private ConfigurableApplicationContext context;

    // ------------------------------------------------------------ 主断言

    @Test
    @DisplayName("🔴 R-51:上下文里不存在任何生成式模型 bean —— image / speech / embedding / transcription / video")
    void contextCarriesNoGenerativeModelBean() {
        List<String> hits = new ArrayList<>();

        for (String name : context.getBeanDefinitionNames()) {
            // allowFactoryBeanInit=false:只问类型,不因为查一下就把 FactoryBean 实例化了。
            // 这条断言不该有副作用 —— 尤其不该由它去触发那个「没 key 就炸」的模型。
            Class<?> type;
            try {
                type = context.getType(name, false);
            } catch (RuntimeException e) {
                continue;   // 类型解析不了的 bean 不在本条禁令的射程内
            }
            if (type == null) {
                continue;
            }

            // 连 bean 名一起看:代理类的类名可能是 Xxx$$SpringCGLIB$$0,而 bean 名仍是 openAiImageModel。
            Set<String> candidates = new LinkedHashSet<>();
            candidates.add(name);
            collectTypeNames(ClassUtils.getUserClass(type), candidates);

            for (Forbidden forbidden : FORBIDDEN) {
                String matched = firstMatch(candidates, forbidden.pattern());
                if (matched != null) {
                    hits.add(describe(name, type, forbidden, matched));
                    break;      // 一个 bean 报一次就够,不刷屏
                }
            }
        }

        if (!hits.isEmpty()) {
            fail("""
                    🔴 能力边界被自动装配开了后门(R-51)。上下文里出现了 %d 个生成式模型 bean:

                    %s
                    背景:spring-ai 的 OpenAI starter 会把整套模型一起装配,我们只要 chat 一个。
                    多出来的那几个不是浪费,是【技术上随时可用的越界能力】—— 见 docs/technical/后端系统设计与组件接入.md §4.2、docs/execution/INDEX.md R-51。
                    正确处置是排除掉它们,不是给它们补配置、更不是删掉本条断言。
                    """.formatted(hits.size(), String.join("\n", hits)));
        }
    }

    /**
     * 兜底:确认上面那次扫描是在一个<b>完整应用上下文</b>上跑的。
     *
     * <p>防的是一种很自然的「优化」——有人嫌 {@code @SpringBootTest} 慢,换成
     * {@code @WebMvcTest} 或 {@code @ContextConfiguration(classes = ...)},测试照样绿,
     * 但扫描的对象已经从「全部自动装配」缩成了「几个手点的 bean」,R-51 从此再也不会被看见。
     * <b>断言的价值等于它扫过的范围</b>,所以范围本身也要被断言一次。
     */
    @Test
    @DisplayName("扫描范围兜底 —— 上下文必须是完整应用,降级成切片时这里先红")
    void scanSeesAWholeApplicationContext() {
        int beans = context.getBeanDefinitionNames().length;
        assertTrue(beans > 50, () -> """
                上下文里只有 %d 个 bean —— 这不像一个完整的 Spring Boot 应用上下文。
                如果本类被从 @SpringBootTest 改成了切片测试,请改回去:
                切片只跑白名单里的自动装配类,而 R-51 要查的恰恰是【白名单之外自动装进来的东西】。
                """.formatted(beans));
    }

    // ------------------------------------------------ 软校验:配置上的配对

    /**
     * 配对断言:<b>接了 spring-ai 的 api-key,就必须同时有 {@code spring.autoconfigure.exclude}。</b>
     *
     * <p>与主断言不重复 —— 主断言查的是<b>运行结果</b>,这条查的是<b>意图有没有被写下来</b>。
     * 两者会在同一天失效:上面提到的实测事实是「没给 key 时上下文直接起不来」,
     * 也就是说接线过程中<b>一定会先补上 key</b>,而补 key 的那一刻主断言可能压根跑不到(上下文炸在前面)。
     * 这条则只读文本,不依赖上下文起不起得来。
     *
     * <p>软校验:spring-ai 不在类路径上时(即今天)整条不生效,直接返回。
     * 不用 {@code Assumptions} 跳过,是因为被跳过的测试在报告里是灰的、看不见 ——
     * 而这条在今天本来就<b>应该</b>是绿的:「没接 spring-ai」和「接了且排除干净」是同一个合格状态。
     */
    @Test
    @DisplayName("🔴 R-51 配对:配了 spring.ai 的 api-key 就必须同时配 spring.autoconfigure.exclude")
    void springAiApiKeyMustComeWithAutoconfigureExclude() {
        boolean springAiOnClasspath = ClassUtils.isPresent(
                "org.springframework.ai.chat.model.ChatModel", getClass().getClassLoader());

        List<String> effective = effectiveLinesOfApplicationProperties();
        boolean apiKeyInFile = effective.stream()
                .anyMatch(line -> line.matches("^spring\\.ai\\..*api[-_]?key\\s*[=:].*"));
        boolean apiKeyInEnvironment = context.getEnvironment().containsProperty("spring.ai.openai.api-key");

        if (!springAiOnClasspath && !apiKeyInFile) {
            // 今天走的就是这一支:pom 里没有 spring-ai,配置里也没有 spring.ai.*。
            // 这不是「没查」,是「查了,确实还没接线」。
            return;
        }
        if (!apiKeyInFile && !apiKeyInEnvironment) {
            // 依赖已经进来了但还没接线。此时 exclude 该不该有由主断言说了算,这条不表态。
            return;
        }

        boolean excludeInFile = effective.stream()
                .anyMatch(line -> line.startsWith("spring.autoconfigure.exclude"));
        boolean excludeInEnvironment = context.getEnvironment().containsProperty("spring.autoconfigure.exclude");

        assertTrue(excludeInFile || excludeInEnvironment, () -> """
                🔴 application.properties 里已经出现 spring.ai 的 api-key,却没有 spring.autoconfigure.exclude。

                这两行是一对:给了 key,OpenAI 那整套模型(image / speech / embedding / transcription)
                就全部装配得起来且急切实例化 —— 能力边界当场多出一批后门(docs/execution/INDEX.md R-51)。
                在 application.properties 里补上(类名以实际 spring-ai 版本的自动装配报告为准):

                %s

                然后跑一次 contextCarriesNoGenerativeModelBean 确认真的排干净了。
                """.formatted(FALLBACK_EXCLUDE));
    }

    // ------------------------------------------------------------ 辅助

    private record Forbidden(String pattern, String capability) {
    }

    private static String firstMatch(Set<String> candidates, String pattern) {
        Pattern compiled = Pattern.compile(pattern);
        for (String candidate : candidates) {
            if (compiled.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return null;
    }

    /** 类名 + 全部父类 + 全部接口(递归)。厂商实现类可能被代理掉,但它实现的那个接口名跑不了。 */
    private static void collectTypeNames(Class<?> type, Set<String> into) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            into.add(current.getSimpleName());
            into.add(current.getName());
            for (Class<?> iface : current.getInterfaces()) {
                collectTypeNames(iface, into);
            }
        }
    }

    /**
     * 一条命中报告。<b>点名 bean 名 + 类型 + 该往哪儿写 exclude</b> —— 撞上的人不该还要去翻文档。
     *
     * <p>exclude 的类名尽量从 bean 定义反推:模型 bean 是被某个 {@code @Configuration} 的
     * {@code @Bean} 方法造出来的,而那个 {@code @Configuration} 就是要 exclude 的自动装配类本身。
     * 反推不出来时退回 {@code docs/technical/后端系统设计与组件接入.md} §4.2 的那份清单。
     */
    private String describe(String beanName, Class<?> type, Forbidden forbidden, String matchedName) {
        String source = sourceAutoConfiguration(beanName);
        String fix = (source == null)
                ? "  修法(反推不出来源,用 docs/technical/后端系统设计与组件接入.md §4.2 的清单,并用 --debug 核对一遍自动装配报告):\n"
                  + FALLBACK_EXCLUDE.indent(4)
                : "  修法:在 application.properties 追加\n"
                  + ("spring.autoconfigure.exclude=" + source).indent(6);
        return """
                  · bean「%s」
                    类型   %s
                    命中   %s(匹配到 %s)—— %s
                %s""".formatted(beanName, type.getName(), forbidden.pattern(), matchedName,
                forbidden.capability(), fix);
    }

    private String sourceAutoConfiguration(String beanName) {
        try {
            BeanDefinition definition = context.getBeanFactory().getBeanDefinition(beanName);
            String factoryBeanName = definition.getFactoryBeanName();
            if (factoryBeanName == null) {
                return null;
            }
            Class<?> configClass = context.getType(factoryBeanName, false);
            return configClass == null ? null : ClassUtils.getUserClass(configClass).getName();
        } catch (NoSuchBeanDefinitionException e) {
            return null;    // 手工注册的单例没有 BeanDefinition,那就没有「来源自动装配类」可言
        }
    }

    /**
     * {@code application.properties} 里<b>生效的</b>那些行 —— 注释不算。
     *
     * <p>这个仓库的配置文件注释密度极高,而注释里为了讲清楚反例会原样写出被禁的配置
     * (比如上面那份 exclude 清单本身)。按原文匹配会把「文档」读成「配置」,
     * 与 {@code build.sh} 里剥 XML 注释再校验镜像地址是同一个理由。
     */
    private static List<String> effectiveLinesOfApplicationProperties() {
        String raw;
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不到 application.properties —— 本条断言的对象不存在了", e);
        }
        List<String> effective = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            effective.add(trimmed);
        }
        return effective;
    }
}
