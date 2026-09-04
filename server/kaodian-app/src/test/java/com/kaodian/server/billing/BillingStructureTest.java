package com.kaodian.server.billing;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code M7-额度与订单} 里那些「用 {@code grep} 写的判据」,逐条落成断言。
 *
 * <h2>为什么把 {@code grep} 判据搬进测试</h2>
 *
 * 写在文档里的 {@code grep} 只有人想起来跑才会跑,而这些判据要挡的恰恰是<b>下一个人顺手加一行</b>。
 * 搬进来之后它们在 {@code ./server/build.sh -q test} 里每次都跑。
 *
 * <p>扫描根是<b>源码目录</b>而不是编译产物:判据管的是「仓库里有没有这一行」,不是「运行时有没有」。
 */
class BillingStructureTest {

    /** 商业化的两个包。§6.5 的原话:<b>撞上同名不同物时缩范围,不是加例外名单</b>。 */
    private static final List<String> BILLING_SOURCE_ROOTS = List.of(
            "src/main/java/com/kaodian/server/billing",
            "src/main/java/com/kaodian/server/api/billing");

    // ——————————————————— §5.4 / §一 第 4 行:通道三值 ———————————————————

    @Test
    @DisplayName("🔴 通道枚举恰好三个,而且不含支付宝")
    void 通道枚举恰好三个() {
        assertEquals(3, Channel.values().length,
                "🔴 照二值建枚举当场跑红 —— apple_iap 是 U7.5 缺口「内购通道取值」要的那一个");
        assertTrue(Stream.of(Channel.values()).anyMatch(c -> c == Channel.APPLE_IAP));
        assertFalse(Stream.of(Channel.values())
                        .anyMatch(c -> c.wireName().toLowerCase(Locale.ROOT).contains("alipay")),
                "🚫 支付宝不加 —— Q-8 / U7.4 缺口 4 是「做不做」还没裁定,"
                        + "而一个取值加进枚举就等于替它答了「做」");
    }

    @Test
    @DisplayName("🚫 商业化两个包里 alipay 零命中")
    void 支付宝在代码里零命中() {
        assertNoMatch("(?i)alipay|支付宝", "支付宝不加(§5.2)");
    }

    // ——————————————————— §一 第 3 行 / §十二 冲突 8:状态列名叫 state ———————————————————

    @Test
    @DisplayName("🔴 订单的状态字段叫 state,库列与 API 字段同名")
    void 订单状态字段叫state() {
        assertHasComponent(PaymentOrder.class, "state");
        assertNoComponent(PaymentOrder.class, "status",
                "不同名就要有一层映射,而那层映射今天没有任何一份文档写过(§十二 冲突 8)");
        assertHasComponent(com.kaodian.server.api.billing.dto.OrderDetailResponse.class, "state");
        assertHasComponent(com.kaodian.server.api.billing.dto.OrderSummaryDto.class, "state");
    }

    @Test
    @DisplayName("🔴 订阅上不建 status 列 —— 它是 expiresAt 的第二真源")
    void 订阅不建status列() {
        assertNoComponent(Subscription.class, "status",
                "一个需要定时任务写进去才准的状态列,是 expiresAt 的第二真源(§契约增量 5)");
    }

    // ——————————————————— §2.4:账本上找不到的三个方法 ———————————————————

    @Test
    @DisplayName("🔴 QuotaStore 上没有任何一个能让 used 变小的方法")
    void 账本上没有退还也没有直接写used() {
        List<String> methods = Stream.of(QuotaStore.class.getDeclaredMethods())
                .map(m -> m.getName().toLowerCase(Locale.ROOT)).toList();
        for (String forbidden : List.of("refund", "restore", "setused", "setremaining",
                "deductwithoutcall", "revert", "rollback")) {
            assertFalse(methods.contains(forbidden), """
                    QuotaStore 上出现了 %s —— §2.4 三条红线之一:
                      · 失败根本不扣,所以没有「退还」这个动作,有它界面上迟早长出「额度退还中」;
                      · 能直接写 used,`used <= granted` 就不再由结构保证;
                      · 不带流水的扣减方法,就是那个不该存在的扣减端点的内部版本。""".formatted(forbidden));
        }
    }

    // ——————————————————— §4.3:保留策略在结构上不存在 ———————————————————

    @Test
    @DisplayName("🔴 保留策略在结构上不存在 —— 界面翻到底说「没有更多」而这句话是真的")
    void 订单没有保留期() {
        assertNoMatch("purge|deleteBefore|retainedDays|retentionDays|maxOrders",
                "不设保留期(`接口契约` §8.6 缺口 22,G-9 已关闭)");
    }

    // ——————————————————— §4.6 判据 ② ③:没有代扣、不补开票 ———————————————————

    @Test
    @DisplayName("🔴 结构上不可能发生自动扣款")
    void 没有任何代扣字段() {
        assertNoMatch("contractId|nextChargeAt|autoRenewEnabled|subscription_contract",
                "没有代扣字段(§4.6 判据 ②)");
    }

    @Test
    @DisplayName("🔴 开票不补 —— 只占端点位,不给签名不给字段")
    void 开票零命中() {
        assertNoMatch("(?i)invoice|开票|发票", "开票不补(`接口契约` §8.7)");
    }

    // ——————————————————— §6.5:退款零命中 ———————————————————

    @Test
    @DisplayName("🔴 商业化两个包里 refund/退款 只以 REFUNDED 这个状态出现,没有任何退款动作")
    void 没有任何我方发起的退款路径() {
        // 两层缩范围,都不是「加例外名单」(§6.5 的原话:撞上同名不同物时缩范围):
        //   ① refund 后面不跟 ed —— REFUNDED / refundedAt 是「平台侧发起、订单必须能显示
        //      这个事实」的那一个取值,不是一条我方能走的路径;
        //   ② 字符串字面量不算 —— UpstreamState 里的 case "REFUND" 是【微信的 trade_state
        //      取值】,归一表必须认得它,而认得一个上游取值不等于我方有一条退款路径。
        List<String> hits = matchesInCode("(?i)\\brefund(?!ed)|退款");
        assertTrue(hits.isEmpty(), """
                商业化包里出现了退款动作:%s
                🔴 §六 已裁定一个端点都不补 —— 「有契约没规则,比没契约更糟」:
                   没契约时缺口是可见的,有契约时它变成了一个没人认的既成事实。
                   退款走 A/B/C 哪一条由律师稿(L-A5)定,技术侧不选(§6.2)。""".formatted(hits));
    }

    @Test
    @DisplayName("REFUNDED 仍然在取值域里 —— 平台侧可能自己发起,订单必须能显示这个事实")
    void 退款态本身还在() {
        assertTrue(Stream.of(OrderState.values()).anyMatch(s -> s == OrderState.REFUNDED));
        assertTrue(OrderState.REFUNDED.isTerminal());
    }

    // ——————————————————— §2.5:时区从配置来 ———————————————————

    @Test
    @DisplayName("🔴 额度这条路上没有第二个时区口径,也没有不带时区的「今天」")
    void 时区只从配置来() {
        assertNoMatch("LocalDate\\.now\\(\\)|LocalDateTime\\.now\\(\\)|ZoneId\\.systemDefault",
                "没有不带时区的「今天」(§2.5)");
        assertNoMatch("ZoneId\\.of\\(",
                "时区从配置来,不写字面量 —— BillingProperties.zone 直接绑成 ZoneId(§2.5)");
    }

    // ——————————————————— §5.4 / §5.1:autoRenew 与定价 ———————————————————

    @Test
    @DisplayName("🔴 autoRenew 只有一处赋值,右边是字面 false")
    void 自动续费恒为假() {
        List<String> assignments = matches("autoRenew\\s*=|AUTO_RENEW\\s*=");
        assertEquals(1, assignments.size(),
                "autoRenew 的赋值不止一处:" + assignments
                        + " —— 恒 false 要落成常量不是配置项,配置项意味着有人能改它,"
                        + "而改它需要的平台资质根本不存在(`商业化与额度设计` §6.3)");
        assertTrue(assignments.getFirst().contains("false"), "右边必须是字面 false:" + assignments);
    }

    @Test
    @DisplayName("🔴 没有任何「别端价格」「划线价」字段")
    void 没有价格锚点字段() {
        assertNoMatch("originalPrice|listPrice|priceFor\\(", "锚点字段不存在也不许加(`接口契约` §8.2)");
    }

    @Test
    @DisplayName("🔴 档位 code 不出现在代码里 —— 定价是配置不是编译期枚举")
    void 档位code不写死在代码里() {
        List<String> hits = matches("\"plus\"|\"free\"");
        assertTrue(hits.isEmpty(), """
                商业化包里写死了档位 code:%s
                🔴 `接口契约` §6.6:「校验依据是服务端 plans 列表,不是写死在代码里的枚举」。
                   免费兜底档走 kaodian.billing.default-plan 这个配置项。""".formatted(hits));
    }

    // ——————————————————— §10.1:分页不返回条数 ———————————————————

    @Test
    @DisplayName("🔴 分页不返回 total / hasMore / pageCount / offset")
    void 分页只有游标一种() {
        assertNoMatch("\\btotal\\b|hasMore|pageCount|pageNo|\\boffset\\b",
                "U7.6 逐字要求「不做『加载更多』按钮」(§10.1)");
    }

    // ——————————————————— §8.3:details 里没有购买入口 ———————————————————

    @Test
    @DisplayName("🔴 QUOTA_EXHAUSTED 的 details 里不出现任何指向购买的东西")
    void 受限态的details不带购买入口() {
        String source = codeOf(moduleFile(BILLING_SOURCE_ROOTS.get(1) + "/dto/QuotaExhaustedDetails.java"));
        for (String forbidden : List.of("upgrade", "purchase", "去看档位")) {
            assertFalse(source.contains(forbidden), """
                    QuotaExhaustedDetails 里出现了 %s。
                    🔴 受限态里付费入口的视觉权重必须低于免费兜底(U7.2 §2.7),
                       而一个由服务端下发的购买字段会让这条约束变成端的自觉。""".formatted(forbidden));
        }
        // planCode 只允许作为「不出现」的说明出现在注释里,不允许是一个字段
        assertNoComponent(com.kaodian.server.api.billing.dto.QuotaExhaustedDetails.class, "planCode",
                "details 里没有 planCode(§8.3)");
    }

    // ——————————————————— §7.3:回调的响应体 ———————————————————
    //
    // §7.2 那两条(白名单里没有 billing/plans、只读令牌前缀黑名单含 /billing/ 与 /quota/)
    // 不在这里:ApiAuthFilter 的那三个常量是包内可见的,而放宽它们就是改 B0 的横切件。
    // 它们落在 api.support 包里的 BillingAuthChainContractTest —— 同一个包,看得见。

    @Test
    @DisplayName("🔴 回调的响应体不是 ApiError —— 拿 ApiError 去回它,平台会一直重推")
    void 回调不回统一错误体() {
        String source = codeOf(moduleFile(BILLING_SOURCE_ROOTS.get(1) + "/WxPayNotifyController.java"));
        assertFalse(source.contains("ApiError"), """
                WxPayNotifyController 里出现了 ApiError。
                🔴 平台要的是 {"code":"SUCCESS"} / {"code":"FAIL","message":"…"},与 §1.3 是两套。
                   拿 ApiError 去回它,平台会因为读不到期望的字段而【一直重推同一条通知】,
                   而重推撞唯一键之后表现为「一切正常、日志一条没有」(§契约增量 6)。""");
    }

    // ——————————————————— B0-1:不写 DDL ———————————————————

    @Test
    @DisplayName("🔴 全仓一个 .sql 文件都没有")
    void 不写DDL() {
        List<Path> sql = walk(repoFile("."), ".sql");
        assertTrue(sql.isEmpty(), "B0-1 裁定本轮交付 store 接口 + 文件 JSON 实现,不写 CREATE TABLE:" + sql);
    }

    // ——————————————————— §11.1:依赖方向 ———————————————————

    @Test
    @DisplayName("🔴 商业化类型不出现在 domain / agent / auth 三个模块里")
    void 商业化不进另外三个模块() {
        for (String module : List.of("kaodian-domain", "kaodian-agent", "kaodian-auth")) {
            List<String> hits = new ArrayList<>();
            for (Path file : walk(repoFile("server/" + module + "/src/main/java"), ".java")) {
                String source = read(file);
                if (source.contains("com.kaodian.server.billing")
                        || source.contains("com.kaodian.server.api.billing")) {
                    hits.add(file.toString());
                }
            }
            assertTrue(hits.isEmpty(), """
                    %s 里引到了商业化类型:%s
                    🔴 `技术架构与接口契约` §5.6 那张 ER 图的全部信息量就是
                       「商业化侧与骨架层、行为层没有一条外键关系」(§11.1)。
                       额度扣减发生在 app 的 AI 端点内部:app 取出 userId,调 QuotaStore.consume,
                       再把 userId 显式传给 domain / agent 的方法参数。""".formatted(module, hits));
        }
    }

    // ——————————————————— 工具 ———————————————————

    private static void assertNoMatch(String regex, String why) {
        List<String> hits = matches(regex);
        assertTrue(hits.isEmpty(), why + " —— 命中:" + hits);
    }

    /**
     * 🔴 <b>扫描范围本身要先被验一次</b>。
     *
     * <p>一条扫不到东西的断言会<b>永远绿</b>,那比没有更糟(与 {@code ImageRetentionTest}
     * 同一句)。实测:第一版把路径写成从仓库根算起,而测试的工作目录是
     * {@code server/kaodian-app} —— 于是 {@code walk} 一个文件都没扫到,
     * <b>十条 grep 判据一起假绿</b>,只有那条「必须恰好一处赋值」的断言把它抓了出来。
     */
    @Test
    @DisplayName("🔴 扫描范围本身不是空的 —— 否则上面每一条 grep 判据都是假绿")
    void 扫描范围不为空() {
        for (String root : BILLING_SOURCE_ROOTS) {
            assertFalse(walk(moduleFile(root), ".java").isEmpty(),
                    "扫不到任何源码:" + root + " —— 这些判据等于没跑");
        }
        assertFalse(walk(repoFile("server/kaodian-domain/src/main/java"), ".java").isEmpty(),
                "扫不到 kaodian-domain 的源码 —— 依赖方向那条判据等于没跑");
    }

    /**
     * 在商业化两个包的源码里找匹配的行。
     *
     * <p>🔴 <b>注释行不算</b>:这个仓库的注释是用否定式写的(「没有 purge」「不加 alipay」),
     * 逐字带着被禁的那个词。把注释算进去,每一条判据都会被它自己的合规说明判红 ——
     * 那正是 CLAUDE.md 里「黑名单不许命中本仓库自己的合规注释」说的那件事。
     */
    private static List<String> matches(String regex) {
        return matches(regex, false);
    }

    /** 同上,但<b>字符串字面量里的命中不算</b> —— 见 {@link #没有任何我方发起的退款路径}。 */
    private static List<String> matchesInCode(String regex) {
        return matches(regex, true);
    }

    private static List<String> matches(String regex, boolean ignoreStringLiterals) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        List<String> hits = new ArrayList<>();
        for (String root : BILLING_SOURCE_ROOTS) {
            for (Path file : walk(moduleFile(root), ".java")) {
                int lineNo = 0;
                for (String line : read(file).lines().toList()) {
                    lineNo++;
                    if (isComment(line)) {
                        continue;
                    }
                    String haystack = ignoreStringLiterals ? stripStringLiterals(line) : line;
                    if (pattern.matcher(haystack).find()) {
                        hits.add(file.getFileName() + ":" + lineNo + " " + line.trim());
                    }
                }
            }
        }
        return hits;
    }

    /**
     * 🔴 注释行一律不算。
     *
     * <p>这个仓库的注释是<b>用否定式写的</b>(「没有 purge」「不加 alipay」「响应体不是 ApiError」),
     * 逐字带着被禁的那个词。把注释算进去,<b>每一条判据都会被它自己的合规说明判红</b> ——
     * 那正是 CLAUDE.md 里「黑名单不许命中本仓库自己的合规注释」说的那件事。
     * 实测:第一版把注释算进去,三条判据一起假红。
     */
    private static boolean isComment(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*")
                || trimmed.startsWith("/*") || trimmed.startsWith("/**");
    }

    /** 整份源码,去掉注释行。给那些要整文件 {@code contains} 的判据用。 */
    private static String codeOf(Path file) {
        return read(file).lines().filter(line -> !isComment(line))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static String stripStringLiterals(String line) {
        return line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    private static void assertHasComponent(Class<?> type, String name) {
        assertTrue(componentNames(type).contains(name),
                type.getSimpleName() + " 上没有 " + name + " 这个字段");
    }

    private static void assertNoComponent(Class<?> type, String name, String why) {
        assertFalse(componentNames(type).contains(name),
                type.getSimpleName() + " 上出现了 " + name + " —— " + why);
    }

    private static List<String> componentNames(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        return components == null ? List.of()
                : Stream.of(components).map(RecordComponent::getName).toList();
    }

    /**
     * 模块内的相对路径 → 真实路径。
     *
     * <p>Surefire 的工作目录是<b>模块目录</b>({@code server/kaodian-app});
     * 从仓库根手跑 {@code mvn -pl} 时又可能是仓库根。两边都要能找到,
     * 所以两个候选都试一遍,而不是猜一个。
     */
    private static Path moduleFile(String relativeToModule) {
        Path direct = Path.of(relativeToModule);
        return Files.exists(direct) ? direct : Path.of("server", "kaodian-app").resolve(relativeToModule);
    }

    /** 仓库根起算的相对路径 → 真实路径。同上,两个候选都试。 */
    private static Path repoFile(String relativeToRepo) {
        Path direct = Path.of(relativeToRepo);
        return Files.exists(direct) ? direct : Path.of("..", "..").resolve(relativeToRepo);
    }

    private static List<Path> walk(Path base, String suffix) {
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(base)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(suffix))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.toString().contains("/node_modules/"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
