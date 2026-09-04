package com.kaodian.server.redline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code tagging} 包的三条源码扫描 —— {@code M2-打标管线与模型接入} §6.2 / §6.3 / §2.5。
 *
 * <p>与 {@code AgentBoundaryTest} 同形:<b>判的是源码里有没有那几个字</b>,不是运行时行为。
 * 三条都属于「写错了不会报错」那一类 ——
 * {@code domain} 里注入一个 {@code CurrentUser} 编译得过、跑得通、测试全绿,
 * 只是从那一刻起领域层再也不能脱离账号体系被测,而没有人会注意到是哪一次提交做的。
 *
 * <p>⚠️ 这三条<b>不是</b> {@code kaodian-domain/pom.xml} 上那道 enforcer 的替代:
 * enforcer 看的是解析后的依赖树(含传递依赖),是主防线;这里看的是<b>语义</b> ——
 * 一个类型名可以在不新增任何 Maven 依赖的情况下出现(比如 {@code Principal} 来自 JDK)。
 */
class TaggingBoundaryTest {

    private static final Path DOMAIN_MAIN =
            Path.of("..", "kaodian-domain", "src", "main", "java", "com", "kaodian", "server");

    private static final Path TAGGING = DOMAIN_MAIN.resolve("tagging");

    @Test
    @DisplayName("🔴 tagging 包真的建出来了,而且那两个类真的搬过去了(§6.1:不留半个中间态)")
    void theTaggingPackageExists() {
        assertTrue(Files.isDirectory(TAGGING), "tagging 包不存在");
        for (String moved : List.of("TaggingService.java", "CandidateRecall.java")) {
            assertTrue(Files.exists(TAGGING.resolve(moved)), moved + " 还没搬进 tagging");
            assertTrue(Files.notExists(DOMAIN_MAIN.resolve("collect").resolve(moved)),
                    "🔴 " + moved + " 在 collect 里还留着一份 —— 「一半在 collect 一半在 tagging」"
                            + "这个中间态没有人会记得收尾");
        }
    }

    @Test
    @DisplayName("🔴 tagging 不是模型注入点 —— 包里不出现 org.springframework.ai.*")
    void springAiStaysOutOfTagging() throws IOException {
        List<String> hits = grep(TAGGING, "org.springframework.ai");
        assertEquals(List.of(), hits,
                "🔴 tagging 只认 VisionTagger / AsrClient 两个接口,它不知道 spring-ai 存在,"
                        + "也不知道 base-url 是什么 —— 模型注入点只有 recognize 与 agent.llm 两处");
    }

    @Test
    @DisplayName("🔴 domain 里的 HTTP 客户端只许出现在 recognize 包")
    void noHttpClientOutsideRecognize() throws IOException {
        for (String client : List.of("RestClient", "WebClient", "HttpClient", "RestTemplate")) {
            for (String hit : grep(DOMAIN_MAIN, client)) {
                assertTrue(hit.contains("/recognize/"),
                        "🔴 " + client + " 出现在 recognize 之外:" + hit
                                + " —— 换厂商的切换点必须只有一处,散出去之后「改一行 base-url」就不成立了");
            }
        }
    }

    @Test
    @DisplayName("🔴 domain 里不出现当前用户那四个类型名 —— 连注释里也不出现")
    void domainNeverReachesForTheCurrentUser() throws IOException {
        for (String forbidden : List.of("CurrentUser", "SecurityContext",
                "@AuthenticationPrincipal", "Principal")) {
            assertEquals(List.of(), grep(DOMAIN_MAIN, forbidden),
                    "🔴 " + forbidden + " 出现在 domain 里 —— userId 一律作为 long 参数由 app 显式传入。"
                            + "判据是一行 grep,所以一句「我们没有用它」会让那行 grep 自己命中自己");
        }
    }

    @Test
    @DisplayName("🔴 ModelCallGate 那份源码里,一个字都不说明「拿不到是为什么」")
    void theGateNeverNamesWhatItGuards() throws IOException {
        String source = Files.readString(TAGGING.resolve("ModelCallGate.java"), StandardCharsets.UTF_8);
        for (String forbidden : List.of("quota", "Quota", "billing", "Billing", "order", "额度", "账单")) {
            assertTrue(!source.contains(forbidden),
                    "🔴 ModelCallGate 里出现了「" + forbidden + "」—— 一旦出现,下一个人就会在 domain 里"
                            + "加一个「查还剩多少」的方法,而那是那条边的第一节");
        }
    }

    /**
     * {@code M2} §2.5 的那条 grep 判据:{@code domain} 里不许提到商业化。
     *
     * <p>⚠️ <b>它今天在 {@code tagging} 之外是红的,而且不是本模块弄红的</b> ——
     * {@code TouchKind.consumesAiQuota()} 是 {@code v1} 上早就有的一个布尔标志
     * (「手动三种永不消耗」这条产品结论的落点),{@code CaptureService} / {@code AsrClient}
     * 里那几处是 javadoc。它们都<b>不是一条依赖边</b>:没有一处去问「还剩多少」。
     *
     * <p>所以这一条钉两件事:①{@code tagging} 包<b>一处都不许有</b>;
     * ②{@code domain} 其余部分的命中<b>就是已知的这几个文件,多一个就红</b> ——
     * 「已经红了所以随便加」是这条判据失效的唯一方式。
     * 判据本身与代码的落差已回写 §契约增量,由 stage 3 收口。
     */
    @Test
    @DisplayName("🔴 tagging 里一处商业化都没有;domain 其余部分就那三个已知文件,多一个就红")
    void commerceNeverLeaksIntoTagging() throws IOException {
        List<String> known = List.of("collect/CaptureService.java", "collect/TouchKind.java",
                "recognize/AsrClient.java", "recognize/StubAsrClient.java");
        for (String forbidden : List.of("quota", "Quota", "billing", "Billing", "额度")) {
            for (String hit : grep(DOMAIN_MAIN, forbidden)) {
                assertTrue(!hit.contains("/tagging/"),
                        "🔴 tagging 里出现了「" + forbidden + "」:" + hit
                                + " —— ModelCallGate 存在的全部理由就是让这条边建不出来");
                assertTrue(known.stream().anyMatch(hit::contains),
                        "domain 里多了一处提到商业化:" + hit
                                + " —— 已知那几处是 v1 上早就有的 javadoc 与一个布尔标志,"
                                + "它们不是一条边;新增的这一处得先说清楚它是什么");
            }
        }
    }

    /** 扫源码。返回命中的「相对路径:行号」。 */
    private static List<String> grep(Path root, String needle) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(needle)) {
                        hits.add(file.toString().replace('\\', '/') + ":" + (i + 1));
                    }
                }
            }
        } catch (java.io.UncheckedIOException e) {
            fail("扫描失败:" + e.getMessage());
        }
        return hits;
    }
}
