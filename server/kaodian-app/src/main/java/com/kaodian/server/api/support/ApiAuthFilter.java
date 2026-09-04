package com.kaodian.server.api.support;

import com.kaodian.server.api.dto.common.ApiError;
import com.kaodian.server.api.dto.common.ErrorCode;
import com.kaodian.server.auth.AccountStore;
import com.kaodian.server.auth.AppUser;
import com.kaodian.server.auth.TokenCheck;
import com.kaodian.server.auth.TokenScope;
import com.kaodian.server.auth.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;

/**
 * 🔴 <b>默认拒绝</b> —— {@code B0-4}(`B0-平台底座与横切契约` §五 / `接口契约` §三)。
 *
 * <h2>它修的是那个默认值,不是某一个端点</h2>
 *
 * 上一版的鉴权靠参数解析器({@link CurrentSessionResolver}):方法签名里写了
 * {@link CurrentSession} 才验令牌。那个形态的代价写在它自己的类注释里 ——
 * 「<b>代价是『公开』仍然是默认值</b>」,而 {@code /api/records/**} 四个方法一个都没写,
 * 于是它们裸奔了整整一版({@code 接口契约} 待办 1)。
 * <p>
 * 判据只有一条:<b>新增一个 controller 忘了鉴权,它必须默认打不通</b>
 * ({@code ApiAuthDefaultDenyTest})。
 *
 * <h2>它与 {@link CurrentSessionResolver} 是两件事,不合并(B0 §5.2 末行)</h2>
 *
 * 过滤器负责「<b>能不能进</b>」,解析器负责「<b>进来的是谁</b>」。
 * 合并的话,「这个端点要不要登录」又会退回成「作者记不记得声明一个参数」。
 *
 * <h2>🔴 前缀今天是 {@code /api},而这是本文件唯一一处「按临时口径生效」的东西</h2>
 *
 * {@code B0} §16.1 第 2 条:白名单常量在设计稿里按 {@code /api/v1} 写,而代码里十个
 * controller 全部挂在 {@code /api/**} 下;<b>{@code /api/** } → {@code /api/v1/** } 的迁移时点
 * 由项目经理排,没排下来就走临时那条</b>(迁前缀会同时打断 {@code web/}、{@code shell/} 的
 * {@code /api} 代理与任何在跑的端,是一次跨四个端的一次性改动)。
 * <p>
 * 所以这里<b>只写一个前缀</b>。🔴 <b>不许两个前缀都写一遍</b> —— 那会让
 * 「匿名入口的全集在这一处数得清」当场失效,而那正是白名单存在的全部理由。
 * 迁 {@code /api/v1} 时改 {@link #PREFIX} 一行,{@code ApiAuthWhitelistContractTest}
 * 会盯着它与契约 §三 那张表继续对齐。
 */
@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    /**
     * 🔴 生效前缀。见类注释 —— <b>B0 唯一一处「写了但今天按临时前缀生效」的东西</b>,
     * 迁 {@code /api/v1} 时这里跟着改一次(B0 §16.1 第 2 条)。
     *
     * <p>健康检查不在 {@code /api} 下,所以它不需要进白名单(`接口契约` §三 已写)。
     */
    static final String PREFIX = "/api/v1";

    /**
     * 🔴 <b>匿名端点全集,七行。</b>加一行要在 {@code 接口契约} §三 同时加一行 ——
     * 两处不一致由 {@code ApiAuthWhitelistContractTest} 判红(B0 §5.5 判据 ②)。
     *
     * <p>🔴 <b>第七行 {@code /billing/notify/wxpay} 不是匿名,是另一条鉴权链</b>:
     * 微信支付回调独立验签,不走应用令牌。过滤器放行之后由该 controller 自己验签
     * (那个端点今天还不存在,这一行先留着)。它在这张表上是为了让
     * 「<b>七行里真正匿名的只有六行</b>」不被误读成「回调漏了」。
     */
    static final List<Anonymous> WHITELIST = List.of(
            new Anonymous(HttpMethod.POST, PREFIX + "/auth/sms/send"),
            new Anonymous(HttpMethod.POST, PREFIX + "/auth/sms/verify"),
            new Anonymous(HttpMethod.POST, PREFIX + "/auth/wechat/login"),
            new Anonymous(HttpMethod.GET, PREFIX + "/auth/agreements/current"),
            new Anonymous(HttpMethod.POST, PREFIX + "/auth/wechat/phone-login"),
            new Anonymous(HttpMethod.GET, PREFIX + "/auth/wechat/authorize-url"),
            new Anonymous(HttpMethod.POST, PREFIX + "/billing/notify/wxpay"));

    /**
     * 🔴 只读令牌一律 {@code 403},<b>不论方法</b> —— <b>五条前缀,不是四条</b>
     * (`接口契约` §3.1:「只读令牌的路径前缀黑名单是五条,不是四条」)。
     *
     * <p>第五条 {@code /export/jobs/**} 是最容易漏的那条,它连自己那两个 {@code GET} 一起挡掉:
     * 只读令牌发不起作业,给它两个只会返回 404 的 GET,是多开两个面换零个能力。
     */
    static final List<String> READONLY_FORBIDDEN_PREFIXES = List.of(
            "/billing/", "/quota/", "/agent/", "/tokens/", "/export/jobs/");

    /**
     * 🔴 <b>唯一接受「仅仅过期」令牌的端点</b>(`接口契约` §3.1)。
     * 已吊销的令牌走的仍然是 {@code 401 UNAUTHORIZED} —— 见 {@link #doFilterInternal}。
     */
    static final Anonymous REFRESH = new Anonymous(HttpMethod.POST, PREFIX + "/auth/refresh");

    private static final String BEARER = "Bearer ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TokenService tokens;

    /**
     * 账号状态的来源 —— {@code 401} 第三档 {@code ACCOUNT_DEACTIVATED} 的唯一依据。
     *
     * <p>🔴 它问的是<b>账号状态</b>不是令牌状态,所以第三档不破「已吊销不单独成档」那一条
     * (B0 §5.3):被踢下线而账号还活着的一方走 {@code UNAUTHORIZED},原样什么都不知道。
     */
    private final LongFunction<Optional<AppUser>> accountLookup;

    /**
     * 拿不到 {@link AccountStore} 时(比如只装了 web 切片的测试上下文),
     * 账号查询一律返回空 → 第三档说不出口,退回 {@code UNAUTHORIZED}。
     * <b>失败朝安全的那边倒</b>:少说一句话,不会多放一个人进来。
     */
    @Autowired
    public ApiAuthFilter(TokenService tokens, ObjectProvider<AccountStore> accounts) {
        this(tokens, userId -> {
            AccountStore store = accounts.getIfAvailable();
            return store == null ? Optional.empty() : store.findById(userId);
        });
    }

    ApiAuthFilter(TokenService tokens, LongFunction<Optional<AppUser>> accountLookup) {
        this.tokens = tokens;
        this.accountLookup = accountLookup;
    }

    /**
     * 一个匿名入口 —— <b>{@code (method, path)} 全等</b>。
     *
     * <h2>🔴 为什么是全等而不是前缀匹配(B0 §5.2)</h2>
     *
     * 前缀匹配会让 {@code /api/auth/sms/send/../../records} 这类路径落进白名单;
     * 而且 {@code /auth/**} 一整段匿名会把 {@code /auth/logout}、{@code /auth/merge/**}
     * 一起放出去 —— 那两个是<b>需要 {@code full} 令牌</b>的(`接口契约` §3.1)。
     * <p>
     * 路径在比对之前先规范化({@link #normalize}):{@code ..} 与重复斜杠都要先折掉,
     * 否则「全等」只是换了个地方被绕过去。
     */
    record Anonymous(HttpMethod method, String path) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = normalize(request.getRequestURI());

        // 生效范围只有 /api/v1/**。健康检查不在这个前缀下,所以它不需要进白名单。
        if (!path.equals(PREFIX) && !path.startsWith(PREFIX + "/")) {
            chain.doFilter(request, response);
            return;
        }

        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        // CORS 预检不带 Authorization —— 拦掉它等于关掉跨域,而跨域策略写在 ApiCorsConfig 里。
        if (HttpMethod.OPTIONS.equals(method)) {
            chain.doFilter(request, response);
            return;
        }

        if (WHITELIST.contains(new Anonymous(method, path))) {
            chain.doFilter(request, response);
            return;
        }

        String plaintext = bearerOf(request);
        if (plaintext == null) {
            reject(response, ErrorCode.UNAUTHORIZED, "请先登录。");
            return;
        }

        switch (tokens.check(plaintext)) {
            case TokenCheck.Valid valid -> {
                String denial = readonlyDenial(valid.token().scope(), method, path);
                if (denial != null) {
                    reject(response, ErrorCode.READONLY_TOKEN, denial);
                    return;
                }
                chain.doFilter(request, response);
            }
            // 「仅仅过了 expiresAt」自成一档:持有这个令牌的人本来就知道它曾经有效,
            // 说出来什么都没泄露,而登录门的副标题(U5.1)分不出这一档就写不出来。
            case TokenCheck.Expired ignored -> {
                if (REFRESH.equals(new Anonymous(method, path))) {
                    chain.doFilter(request, response);      // 唯一放行过期令牌的端点
                    return;
                }
                reject(response, ErrorCode.TOKEN_EXPIRED, "登录已过期,请重新登录。");
            }
            // 🔴 「已吊销」不单独成档 —— 那才是真的送信息:它等于告诉持有者
            //    「这个令牌曾经是真的」,而 POST /tokens/revoke-all 存在的意义
            //    正是让被吊销的一方什么都不知道。
            //    第三档的依据是【账号状态】不是令牌状态,所以它不破上面这一条。
            case TokenCheck.Revoked revoked -> {
                boolean deactivated = accountLookup.apply(revoked.userId())
                        .map(user -> !user.isActive())
                        .orElse(false);
                if (deactivated) {
                    reject(response, ErrorCode.ACCOUNT_DEACTIVATED, "这个账号已注销。");
                    return;
                }
                reject(response, ErrorCode.UNAUTHORIZED, "请先登录。");
            }
            // 没带头 / 格式不对 / 查不到。🔴 这一叶【永远没有 userId 可查】,
            // 于是 ACCOUNT_DEACTIVATED 在结构上就说不出口 —— 泄露面由结构限死。
            case TokenCheck.Invalid ignored -> reject(response, ErrorCode.UNAUTHORIZED, "请先登录。");
        }
    }

    /**
     * 只读令牌撞上了什么。{@code null} 表示放行。
     *
     * <p>🔴 <b>这是第二道锁,不是唯一那道。</b>主要防线是 MCP/CLI 那一侧根本换不出写能力
     * ({@link TokenScope}),{@link CurrentSession#requireWrite()} 是第三道。
     * 三道是冗余的,冗余是有意的 —— 一道失效不该导致整条线失守。
     */
    private static String readonlyDenial(TokenScope scope, HttpMethod method, String path) {
        if (scope.canWrite()) {
            return null;
        }
        String rest = path.substring(PREFIX.length());
        for (String forbidden : READONLY_FORBIDDEN_PREFIXES) {
            if (rest.startsWith(forbidden)) {
                return "只读令牌打不开这一段:" + forbidden + "** 一律拒绝,不论方法。";
            }
        }
        if (!HttpMethod.GET.equals(method)) {
            return "这是只读令牌,换不出写能力。MCP 与 CLI 一律只读。";
        }
        return null;
    }

    private static String bearerOf(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String plaintext = header.substring(BEARER.length()).trim();
        return plaintext.isEmpty() ? null : plaintext;
    }

    /**
     * 🔴 规范化之后再比对:先解码,再把重复斜杠压掉,再折掉 {@code .} 与 {@code ..}。
     *
     * <p>少了这一步,「{@code (method, path)} 全等」这句话在
     * {@code /api/auth/sms/send/../../records} 面前不成立 —— 而那正是前缀匹配被否掉的理由本身。
     * 解码失败(半截百分号编码)时原样返回:那样的路径匹配不上任何一行白名单,
     * <b>失败方向是「多要一次令牌」,不是「少验一次」</b>。
     */
    static String normalize(String rawUri) {
        String decoded;
        try {
            decoded = UriUtils.decode(rawUri, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return rawUri;
        }
        String collapsed = decoded.replaceAll("/{2,}", "/");
        String cleaned = org.springframework.util.StringUtils.cleanPath(collapsed);
        // cleanPath 折不掉开头那些爬到根以上的 ..,它们留着也匹配不上白名单,直接照原样返回。
        return cleaned.length() > 1 && cleaned.endsWith("/")
                ? cleaned.substring(0, cleaned.length() - 1)
                : cleaned;
    }

    /**
     * 🔴 过滤器在 {@code @RestControllerAdvice} 之外,拿不到 {@link ApiExceptionHandler} ——
     * 所以这里自己写出<b>同一个 {@link ApiError} 形状</b>({@code {code, message, traceId}},
     * {@code details} 为 null 时整个 key 不出现,靠 {@code ApiError} 上那个
     * {@code @JsonInclude(NON_NULL)})。
     *
     * <p>状态码与 code 都取自 {@link ErrorCode},<b>不在这里再写一份字符串常量</b> ——
     * 「端上不许出现契约 §十 之外的码」只有在码是一个封闭类型时才测得了。
     */
    private static void reject(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                MAPPER.writeValueAsString(new ApiError(code.name(), message, traceId)));
    }
}
