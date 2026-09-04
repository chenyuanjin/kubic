package com.kaodian.server.api;

import com.kaodian.server.api.dto.common.ApiError;
import com.kaodian.server.api.dto.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>{@code B0-5} 的核心交付:{@link ErrorCode} 与 {@code 接口契约-签名与错误码全集} §十 双向比对。</b>
 *
 * <h2>方向必须是两条,不是一条</h2>
 *
 * <ul>
 *   <li><b>契约有、代码无</b> → 红。少一个码,就是有一档失败没有名字,端只能落到「未知错误」</li>
 *   <li><b>代码有、契约无</b> → 红。多一个码,就是有一个端上不认识的字符串在往外发 ——
 *       §十 明写「端上不许出现本表之外的码」,而只有封闭类型 + 反向比对才管得住它</li>
 * </ul>
 *
 * 形态照抄仓库里已有的 {@code scripts/boundary-copy-check.py}(文案同源检查):
 * <b>文档是真源,代码跟着,不一致就红。</b>
 *
 * <h2>🔴 一个数字都不写进断言</h2>
 *
 * 契约 §十 自己写着「不要把『80』写进任何断言」—— 写死总数它就会和表分叉,
 * §12.10 写「8 个」而 §10.8 只列了 7 个就是这么来的。<b>这里每次自己数。</b>
 * (实证:B0 成文时是 80 个,§10.6「本轮补两行」与 §10.7「本轮补三行」之后已是另一个数。)
 *
 * <h2>顺手比对 HTTP 状态</h2>
 *
 * 表格第二列就是状态。只比码名的话,手抄状态码写错(把 {@code WECHAT_UNIONID_MISSING}
 * 写成 502)这类错误一条都拦不住 —— 而契约在那一行专门用 🔴 强调过它必须是 503。
 *
 * @see ErrorCode
 */
class ErrorCodeContractTest {

    // ——————————————————— 定位契约文档 ———————————————————

    /**
     * 契约文档路径。
     *
     * <p>不写死绝对路径 —— 写法与 {@code redline/ImageRetentionTest#serverDir} 同源:
     * Maven 从模块目录跑时 {@code user.dir} 是 {@code server/kaodian-app/},从 {@code server/}
     * 跑时是 {@code server/},从仓库根手动跑时是仓库根。三种都要认,<b>认不出就失败而不是扫 0 行</b> ——
     * 一条扫不到东西的断言会永远绿,那比没有更糟。
     */
    private static final String CONTRACT_DOC = "docs/technical/接口契约-签名与错误码全集.md";

    private static Path contractDoc() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = cursor; p != null; p = p.getParent()) {
            Path candidate = p.resolve(CONTRACT_DOC);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 " + CONTRACT_DOC + "(user.dir=" + cursor + ")");
    }

    // ——————————————————— 抽取规则(B0 §6.3 逐条) ———————————————————

    /**
     * 一个码长什么样。
     *
     * <p>🔴 <b>这条形状过滤是 B0 §6.3 那五条规则之外的第六条,原文漏写了</b>(2026-09-04 实跑发现,
     * 待回写 §契约增量)。没有它,§10.6 末尾那张「两组不是 {@code code} 的取值域」表会被当成码表:
     * 它的首列是 <code>`POST /ai/ask` 的 `done` 帧</code> 与 <code>`GET /export/jobs/{id}`</code>,
     * <b>反引号里是路径不是码</b>,规则 1~5 一条都拦不住它。
     */
    private static final Pattern CODE_SHAPE = Pattern.compile("[A-Z][A-Z0-9_]*");

    /**
     * 一个 HTTP 状态格长什么样:三位数字 / 破折号 / 「透传」。
     *
     * <p>🔴 <b>第七条漏写的规则,同样 2026-09-04 实跑发现。</b>§10.5 里有一张
     * 「⚠️ 2026-09-03 实测落差」表,首列是三个<b>真正的码</b>({@code TOKEN_EXPIRED} 等,
     * 形状过滤拦不住),而第二列是散文(「{@code agreedVersion} 字段还没进三个登录请求体」)——
     * 它不是码表,是一张「为什么今天还没实现」的说明表。
     * 判它的唯一稳定特征就是<b>第二列不是状态</b>。
     * <p>
     * 不用它的后果不是多几个码(那三个码在 §10.2/§10.5 已登记,去重后总数不变),
     * 而是<b>状态比对拿到一段散文当状态</b> —— 那条断言会从第一天起就是红的,
     * 然后被人加白名单加到失效。
     */
    private static final Pattern STATUS_SHAPE = Pattern.compile("\\d{3}|—+|透传");

    /** 「无固定状态码」:契约里的 {@code —}(条目级错误)与 {@code 透传}(容器层)。 */
    private static final int NO_FIXED_STATUS = 0;

    /**
     * 从 §10.2 ~ §10.7 六节的表格首列抽出全部码及其 HTTP 状态。
     *
     * <p><b>规则(B0 §6.3 五条 + 上面两条实跑补的):</b>
     * <ol>
     *   <li>只取 §10.2 ~ §10.7 —— 起点 {@code ### 10.2},终点 {@code ### 10.8}</li>
     *   <li><b>不取 §10.1</b> —— 那是废名登记表,左列是已废弃的错名({@code SMS_CODE_WRONG} 等),
     *       取进来会把废名当现役。落在起点之前,天然排除</li>
     *   <li>首列一格多码时按反引号逐个抽({@code MISSING_AUDIO / AUDIO_TOO_LARGE / …} 一格四个)</li>
     *   <li>带删除线 {@code ~~X~~} 的行跳过({@code EXAM_TYPE_UNSUPPORTED} 今天没有端点能抛出它)</li>
     *   <li>表头行与分隔行跳过</li>
     *   <li>🔴 首列的反引号内容必须形如 {@link #CODE_SHAPE} —— 见该常量注释</li>
     *   <li>🔴 第二列必须整格都是 {@link #STATUS_SHAPE} —— 见该常量注释</li>
     * </ol>
     *
     * <p><b>状态与码的配对:</b>首列按 {@code /} 拆出 N 个码,第二列按 {@code /} 拆出 M 个状态。
     * {@code M == N} 按位对应({@code MISSING_AUDIO / AUDIO_TOO_LARGE / AUDIO_TOO_LONG /
     * UNSUPPORTED_AUDIO_FORMAT} ↔ {@code 400 / 413 / 413 / 415});{@code M == 1} 应用到该行全部码;
     * 其余情况<b>直接失败</b> —— 那是契约表本身写歪了,不该被静默吞掉。
     *
     * @return 码 → HTTP 状态({@code 0} = 无固定状态码),保留文档顺序
     */
    private static Map<String, Integer> extractContractCodes(List<String> lines) {
        Map<String, Integer> codes = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        boolean inScope = false;

        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("### 10.2")) {
                inScope = true;
                continue;
            }
            if (line.startsWith("### 10.8")) {
                break;                                  // 规则 1:六节到此为止
            }
            if (!inScope || !line.startsWith("|")) {
                continue;
            }
            if (line.contains("~~")) {
                continue;                               // 规则 4:删除线行
            }
            String[] cells = line.replaceAll("^\\||\\|$", "").split("\\|", -1);
            if (cells.length < 2) {
                continue;
            }
            String first = cells[0].strip();
            String second = cells[1].strip();
            if (first.matches("[\\s:\\-]+")) {
                continue;                               // 规则 5:分隔行
            }

            List<String> rowCodes = new ArrayList<>();
            Matcher m = Pattern.compile("`([^`]+)`").matcher(first);
            while (m.find()) {
                if (CODE_SHAPE.matcher(m.group(1)).matches()) {
                    rowCodes.add(m.group(1));           // 规则 3 + 6
                }
            }
            if (rowCodes.isEmpty()) {
                continue;                               // 规则 5(表头 `code`)+ 规则 6
            }

            List<String> rowStatuses = new ArrayList<>();
            for (String s : second.split("/")) {
                rowStatuses.add(s.strip());
            }
            if (!rowStatuses.stream().allMatch(s -> STATUS_SHAPE.matcher(s).matches())) {
                continue;                               // 规则 7:第二列不是状态 → 整行不是码表行
            }
            assertTrue(rowStatuses.size() == 1 || rowStatuses.size() == rowCodes.size(),
                    "契约 §十 这一行的码数与状态数对不上,无法按位配对(码 " + rowCodes
                            + ",状态 " + rowStatuses + "):" + line);

            for (int i = 0; i < rowCodes.size(); i++) {
                String status = rowStatuses.size() == 1 ? rowStatuses.get(0) : rowStatuses.get(i);
                int value = status.matches("\\d{3}") ? Integer.parseInt(status) : NO_FIXED_STATUS;
                Integer prior = codes.put(rowCodes.get(i), value);
                if (prior != null) {
                    duplicates.add(rowCodes.get(i));
                }
            }
        }

        assertTrue(duplicates.isEmpty(),
                "契约 §十 里同一个码登记了不止一次,两处的状态可能分叉:" + duplicates);
        // 🔴 认不出就失败,而不是比对 0 个码 —— 一条扫不到东西的断言会永远绿。
        assertTrue(codes.size() > 50, "从契约 §十 只抽出 " + codes.size() + " 个码,抽取规则失效了");
        return codes;
    }

    private static List<String> contractLines() {
        try {
            return Files.readAllLines(contractDoc(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ——————————————————— 断言 ———————————————————

    @Test
    @DisplayName("🔴 ErrorCode 与契约 §十 双向一致 —— 少一个、多一个都红")
    void enumMatchesContractBothWays() {
        Set<String> contract = extractContractCodes(contractLines()).keySet();
        Set<String> enumerated = new LinkedHashSet<>();
        for (ErrorCode c : ErrorCode.values()) {
            enumerated.add(c.name());
        }

        Set<String> missingInCode = new TreeSet<>(contract);
        missingInCode.removeAll(enumerated);
        Set<String> missingInContract = new TreeSet<>(enumerated);
        missingInContract.removeAll(contract);

        assertTrue(missingInCode.isEmpty() && missingInContract.isEmpty(),
                "ErrorCode 与契约 §十 不一致(共抽出 " + contract.size() + " 个码)\n"
                        + "  契约有、代码无(" + missingInCode.size() + "):" + missingInCode + "\n"
                        + "  代码有、契约无(" + missingInContract.size() + "):" + missingInContract);
    }

    @Test
    @DisplayName("HTTP 状态与契约 §十 第二列逐个相同 —— 手抄写错的那种拦得住")
    void httpStatusMatchesContract() {
        Map<String, Integer> contract = extractContractCodes(contractLines());
        List<String> mismatches = new ArrayList<>();
        for (ErrorCode c : ErrorCode.values()) {
            Integer expected = contract.get(c.name());
            if (expected != null && expected != c.status()) {
                mismatches.add(c.name() + ":契约 " + describe(expected) + ",代码 " + describe(c.status()));
            }
        }
        assertTrue(mismatches.isEmpty(), "HTTP 状态与契约 §十 对不上:" + mismatches);
    }

    private static String describe(int status) {
        return status == NO_FIXED_STATUS ? "无固定状态码" : String.valueOf(status);
    }

    /**
     * 🔴 {@code details} 为 {@code null} 时整个 key 不出现 —— {@code 接口契约} §1.1 空值规则。
     *
     * <h2>为什么这条要真的序列化一次,而不是「看一眼注解在不在」</h2>
     *
     * {@code @JsonInclude} 放错包<b>不报编译错也不报运行时错,只是安静失效</b>:
     * Jackson 3 的 databind 在 {@code tools.jackson},但注解仍在
     * {@code com.fasterxml.jackson.annotation}(它自己的 pom 写着
     * "Annotations remain at Jackson 2.x group id")。写成 {@code tools.jackson.annotation}
     * 这个包今天不存在所以编译期就炸,但下一个版本一旦补上同名包,失效就会变成静默的 ——
     * 而失效的表现是 {@code "details": null} 照常发出去,端写的
     * {@code if ('details' in err)} 永远为真,没有任何一条别的断言会红。
     */
    @Test
    @DisplayName("🔴 details 为 null 时整个 key 不出现(§1.1 空值规则的执行装置)")
    void nullDetailsKeyDisappears() {
        ObjectMapper mapper = JsonMapper.builder().build();
        String withoutDetails = mapper.writeValueAsString(
                new ApiError(ErrorCode.SERVER_ERROR.name(), "服务端自身错误", "01a0631a7f2c"));
        assertTrue(!withoutDetails.contains("details"),
                "details 为 null 时不许出现这个 key,实际:" + withoutDetails);

        String withDetails = mapper.writeValueAsString(new ApiError(
                ErrorCode.CODE_WRONG.name(), "验证码不对", "01a0631a7f2c", Map.of("remaining", 2)));
        assertTrue(withDetails.contains("\"details\""),
                "写明了形状的端点要带得出 details,实际:" + withDetails);
    }

    /**
     * 🔴 两个「无固定状态码」的成员问它要状态时必须炸,不能悄悄给一个 500。
     *
     * <p>{@code REQUEST_REJECTED} 透传的是容器给的状态 —— 编一个 500 会把一个 404 变成 500;
     * {@code MISSING_CLIENT_TOKEN} 只在 {@code POST /records/batch} 的条目级 error 里,
     * 整批恒 200 —— 给它一个状态就等于承认「它可以当响应状态用」。
     */
    @Test
    @DisplayName("REQUEST_REJECTED / MISSING_CLIENT_TOKEN 没有固定状态码,问它要就抛")
    void noFixedStatusMembersRefuseToInventOne() {
        for (ErrorCode c : List.of(ErrorCode.REQUEST_REJECTED, ErrorCode.MISSING_CLIENT_TOKEN)) {
            assertEquals(NO_FIXED_STATUS, c.status(), c + " 应当是「无固定状态码」");
            IllegalStateException ex = assertThrows(IllegalStateException.class, c::httpStatus,
                    c + " 不该编出一个状态码来");
            assertTrue(ex.getMessage().contains(c.name()), "异常消息要说清是哪个码:" + ex.getMessage());
        }
        // 其余成员都要给得出状态 —— 否则上面那条会退化成「反正都抛」。
        for (ErrorCode c : ErrorCode.values()) {
            if (c.status() != NO_FIXED_STATUS) {
                assertEquals(c.status(), c.httpStatus().value(), c + " 的 httpStatus() 与 status() 分叉");
            }
        }
    }
}
