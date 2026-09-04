package com.kaodian.server.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 口径偏离登记({@code M3-骨架与覆盖度差集} §3.2 / §十四 增量 7)。
 *
 * <h2>🔴 不建端点、不建表,只有这一个过滤器</h2>
 *
 * 端拿不到 {@code GET /config/effective} 时退到本地默认,<b>本地计数器 +1</b>;
 * 下一次<b>任何</b>成功的 API 请求把计数捎在 {@link #HEADER} 上,端收到 2xx 之后清零。
 * <p>
 * 为什么不是「发生时上报一次」:<b>偏离恰好发生在服务端够不着的那一刻,那一刻上报也发不出去。</b>
 * 为什么不建确认端点:那正是「不建端点」要挡的东西 —— 代价是<b>至少一次语义</b>
 * (响应回程断掉时会重复计数),这个偏差已经登记在案,<b>不补</b>。
 *
 * <h2>🔴 它在 {@code app},不进 {@code domain}</h2>
 *
 * 它是诊断,不是领域。差集的算法里没有「用户曾经拿不到口径」这件事。
 *
 * <h2>🔴 头解析失败,永不返回 4xx</h2>
 *
 * 整条头丢弃、落一行 {@link #CODE_MALFORMED}、<b>继续处理请求</b>。
 * 一个诊断头不许让它搭车的那个请求失败 —— 那会把「口径拿不到」升级成「功能用不了」,
 * 而且失败的是一个与口径毫无关系的接口,排查方向会整个指错。
 *
 * <h2>🔴 日志里不出现头的原文</h2>
 *
 * 合法的头只装口径名与次数(名字来自 {@link #NAMES} 这个闭集,次数是解析出来的 {@code long})——
 * 那是<b>服务端自己的值</b>,落盘安全。畸形的头则<b>一个字都不打</b>,只记字节数:
 * 它本来就装了不该装的东西({@code x=1;DROP TABLE} 就是判据里那三条之一),
 * 而「不往磁盘上落用户送来的原文」是 {@code ApiExceptionHandler} 开头那条纪律,
 * 截断在这里也不必要 —— 畸形头的内容对定位没有任何帮助,语法是写死的常量,错只可能错在端上。
 */
@Component
public class CaliberDeviationFilter extends OncePerRequestFilter {

    /** 偏离登记的请求头。🔴 全 {@code main} 里只有这一处字面量,改名只改这一行。 */
    public static final String HEADER = "X-Config-Fallback";

    /** 合法的一条登记。 */
    public static final String CODE = "CONFIG_FALLBACK";

    /** 🔴 整条头被丢弃的那一条。请求本身照常处理,状态码不受影响。 */
    public static final String CODE_MALFORMED = "CONFIG_FALLBACK_MALFORMED";

    /**
     * 登记落在哪个端点名下。
     *
     * <p>🔴 <b>不是搭车的那个请求的路径</b> —— 偏离的是「口径拿不到」这件事,
     * 而它发生在这里。按搭车路径记的那一版,同一件事会散在十几个端点名下,
     * 「这个端点最近拿不到几次」当场问不出来。
     */
    public static final String PATH = "/config/effective";

    /** 🔴 总长上限,超了整条丢弃。头是闭集两个名字 + 两个数,正常撑不到 40 字节。 */
    private static final int MAX_BYTES = 256;

    /** 🔴 语法闭集,写死({@code §十四} 增量 7 原文)。 */
    private static final Pattern SYNTAX = Pattern.compile("[a-zA-Z]+=[0-9]+(,[a-zA-Z]+=[0-9]+)*");

    /**
     * 🔴 <b>名字取值域是闭集两个</b>,随 {@code /config/effective} 的字段逐条加。
     *
     * <p>不在闭集里就整条丢弃,而不是「认识的留下、不认识的跳过」——
     * 跳过的那一版会让端悄悄往这个头里塞第三个名字(科目、来源、任何自由文本),
     * 服务端全程 2xx,而<b>这个头过不了红线就不该存在</b>。
     */
    private static final Set<String> NAMES = Set.of("blindspotOrderBy", "blindspotTop");

    private static final Logger log = LoggerFactory.getLogger(CaliberDeviationFilter.class);

    /**
     * 🔴 登记发生在 {@code chain.doFilter} <b>之前</b>。
     *
     * <p>放在之后的那一版,搭车的那个请求抛异常时登记就丢了 —— 而「拿不到口径」
     * 与「那个请求成功没成功」本来就是两件事,后者不该吃掉前者。
     * 没带头时一次 {@code getHeader} 就返回,不做任何事。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        if (raw != null) {
            recordDeviation(raw);
        }
        chain.doFilter(request, response);
    }

    /** 一条头一行日志,合法与畸形各一种形状。🔴 两种都不打头的原文。 */
    private static void recordDeviation(String raw) {
        Map<String, Long> counts = parse(raw);
        if (counts == null) {
            // 只记字节数:头的原文一个字都不进日志,理由见类注释。
            log.info("口径偏离登记被丢弃 code={} bytes={}",
                    CODE_MALFORMED, raw.getBytes(StandardCharsets.UTF_8).length);
            return;
        }
        log.info("口径偏离登记 code={} path={} counts={}", CODE, PATH, counts);
    }

    /**
     * 解析闭集头。
     *
     * <p>四类拒绝合并成一个 {@code null}:超长、语法不合、名字不在闭集、次数不是正整数。
     * 🔴 <b>合并是有意的</b> —— 分开回给调用方就等于给端一条「试出服务端认哪个名字」的通道,
     * 而这个头的价值全在于它是闭集。四类的处置也完全一样:<b>整条丢弃</b>,不做部分接受。
     *
     * @return 口径名 → 累计次数;整条头不合法时返回 {@code null}
     */
    private static Map<String, Long> parse(String raw) {
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES || !SYNTAX.matcher(raw).matches()) {
            return null;
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq);
            if (!NAMES.contains(name)) {
                return null;
            }
            long count;
            try {
                count = Long.parseLong(pair.substring(eq + 1));
            } catch (NumberFormatException tooManyDigits) {
                // 语法只保证「全是数字」,保证不了「装得下」——20 个 9 在这里溢出。
                return null;
            }
            if (count <= 0) {
                return null;      // 次数是正整数;0 次的偏离不是一次偏离
            }
            counts.put(name, count);
        }
        return counts;
    }
}
