package com.kaodian.server.api.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 🔴 <b>白名单与契约同源</b>({@code B0} §5.5 判据 ②)。
 *
 * <h2>为什么这条判据必须存在</h2>
 *
 * 理由写在 {@code 接口契约} §三 里:「这张表的行数<b>只许因为『真的多了一个匿名入口』而变</b>……
 * <b>白名单存在的意义正是『匿名入口的全集在这一处数得清』</b>」。
 * 两处各写各的就会各漏各的 —— {@code 后端系统设计与组件接入} §4.2 那份自动装配排除清单
 * 漏掉 {@code moderation},正是这个成因。
 * <p>
 * 所以这里<b>双向</b>比对:契约有而代码没有 → 红(有个匿名入口没被挡也没被数);
 * 代码有而契约没有 → 红(有个匿名入口没人知道)。
 *
 * <h2>⚠️ 前缀是临时的,比对时归一化</h2>
 *
 * 契约表里写的是 {@code /auth/sms/send} 这种<b>不带前缀</b>的形式,
 * 代码里带着 {@link ApiAuthFilter#PREFIX}(今天是 {@code /api})。
 * 那个前缀按 {@code B0} §16.1 第 2 条是<b>临时</b>的 —— 设计稿按 {@code /api/v1} 写,
 * 而迁移时点由项目经理排。所以比对在<b>剥掉前缀之后</b>做:
 * <b>迁前缀时这个测试不该跟着红</b>,它盯的是那七行本身。
 */
class ApiAuthWhitelistContractTest {

    /** 契约文件。简称见 {@code 文档规范与目录} §3.1。 */
    private static final String CONTRACT = "docs/technical/接口契约-签名与错误码全集.md";

    /** §三 那一节的标题。表格紧跟在它下面。 */
    private static final String SECTION = "## 三、鉴权总表";

    @Test
    @DisplayName("🔴 ApiAuthFilter.WHITELIST 与 接口契约 §三 那张表逐行一致(双向)")
    void whitelistMatchesTheContractTableBothWays() {
        Set<String> fromContract = contractRows();
        Set<String> fromCode = new LinkedHashSet<>();
        for (ApiAuthFilter.Anonymous anonymous : ApiAuthFilter.WHITELIST) {
            // 剥掉临时前缀之后再比 —— 见类注释
            fromCode.add(anonymous.method().name() + " "
                    + anonymous.path().substring(ApiAuthFilter.PREFIX.length()));
        }

        // 一条扫不到东西的断言会永远绿:表没解析出来时下面那句 assertEquals 会
        // 拿两个空集合比出「一致」,而它其实什么都没验。
        assertFalse(fromContract.isEmpty(),
                "没能从 " + CONTRACT + " §三 里解析出任何一行 —— 这个测试等于没跑");

        assertEquals(fromContract, fromCode, """
                白名单与 接口契约 §三 那张表对不上。
                  契约里有而代码里没有:%s   ← 有个匿名入口没被挡,也没被数进白名单
                  代码里有而契约里没有:%s   ← 有个匿名入口没人知道
                加一个匿名入口要【两处同时加】—— 白名单存在的意义正是「匿名入口的全集在这一处数得清」。"""
                .formatted(minus(fromContract, fromCode), minus(fromCode, fromContract)));
    }

    @Test
    @DisplayName("白名单是七行 —— 行数只许因为『真的多了一个匿名入口』而变")
    void thereAreExactlySevenAnonymousEntries() {
        assertEquals(7, ApiAuthFilter.WHITELIST.size(),
                "白名单不再是七行。改行数之前先回答:是真的多了一个匿名入口,"
                        + "还是只是把某一行挪出了统计?后者是把审计口径做小,不是把风险做小(接口契约 §三)。");
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 契约 §三 那张表的全部行,形如 {@code "POST /auth/sms/send"}。
     *
     * <p>只取 §三 标题之后<b>第一张</b>表:它下面还有一张「另有两个端点今天不需要令牌
     * 而它们不该匿名」的表({@code /auth/logout} 与 {@code /account/signup-count}),
     * 那张表的裁定恰恰是<b>把它们收进鉴权面</b> —— 混进来就等于把两个该挡的端点写进白名单。
     * 区分靠的是形状:匿名表的第一格是一个裸的 HTTP 方法名。
     */
    private static Set<String> contractRows() {
        List<String> lines = readContract();
        Set<String> rows = new LinkedHashSet<>();

        boolean inSection = false;
        boolean started = false;
        for (String line : lines) {
            if (line.startsWith(SECTION)) {
                inSection = true;
                continue;
            }
            if (!inSection) {
                continue;
            }
            if (line.startsWith("### ") || line.startsWith("## ")) {
                break;                                  // 出了 §三
            }
            List<String> cells = cellsOf(line);
            if (cells.size() < 2) {
                if (started) {
                    break;                              // 第一张表到此为止
                }
                continue;
            }
            String method = cells.get(0).trim();
            if (!isHttpMethod(method)) {
                if (started) {
                    break;
                }
                continue;                               // 表头 / 分隔行 / 另一张表
            }
            started = true;
            rows.add(method + " " + unquote(cells.get(1)));
        }
        return rows;
    }

    private static boolean isHttpMethod(String s) {
        return switch (s) {
            case "GET", "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    /** {@code | a | b | c |} → {@code [a, b, c]};不是表格行返回空。 */
    private static List<String> cellsOf(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("|")) {
            return List.of();
        }
        String[] parts = trimmed.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            // 首尾两个竖线各会切出一个空串,它们不是格子
            boolean edge = (i == 0 || i == parts.length - 1) && parts[i].isBlank();
            if (!edge) {
                cells.add(parts[i]);
            }
        }
        return cells;
    }

    private static String unquote(String cell) {
        return cell.strip().replace("`", "").strip();
    }

    private static List<String> readContract() {
        Path file = repoRoot().resolve(CONTRACT);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("找不到契约文件:" + file);
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 仓库根。
     *
     * <p>写法照抄 {@code ImageRetentionTest#serverDir}:Maven 从模块目录跑时
     * {@code user.dir} 是 {@code server/kaodian-app/},从 {@code server/} 跑时是 {@code server/},
     * 从仓库根手动跑时是仓库根 —— 三种都要认,<b>认不出就失败而不是扫 0 个文件</b>。
     */
    private static Path repoRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = cursor; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("server/kaodian-app/src/main/java/com/kaodian/server"))
                    && Files.isDirectory(p.resolve("docs"))) {
                return p;
            }
        }
        throw new IllegalStateException("找不到仓库根(user.dir=" + cursor + ")");
    }

    private static String minus(Set<String> a, Set<String> b) {
        Set<String> diff = new LinkedHashSet<>(a);
        diff.removeAll(b);
        return diff.isEmpty() ? "(无)" : diff.toString();
    }
}
