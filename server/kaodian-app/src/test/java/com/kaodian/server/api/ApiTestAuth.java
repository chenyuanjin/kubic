package com.kaodian.server.api;

import com.kaodian.server.api.support.CurrentSessionResolver;
import com.kaodian.server.auth.FileTokenStore;
import com.kaodian.server.auth.IssuedToken;
import com.kaodian.server.auth.TokenScope;
import com.kaodian.server.auth.TokenService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Clock;

/**
 * 接口测试共用的那把令牌 —— {@code B0-4} 之后每一个 {@code /api/**} 请求都需要它。
 *
 * <h2>🔴 它<b>不是</b>「把过滤器关掉」</h2>
 *
 * 这里签的是一条<b>真令牌</b>,走的是真的 {@code TokenService}:请求照样经过
 * {@code ApiAuthFilter} 的全部判断,只是它这次会通过。
 * <p>
 * 关掉过滤器的那种写法(排除这个 bean、或者给测试单独放一条白名单)会让
 * {@code B0-4} 的<b>全部意义</b>当场消失:默认拒绝这件事在生产里成立、在测试里不成立,
 * 而测试正是唯一会发现它不成立的地方。
 *
 * <h2>为什么是 {@link MockMvcBuilderCustomizer},而不是给几百个 {@code perform} 逐个加头</h2>
 *
 * 逐个加是几百行只有一种写法的改动,而且<b>漏一处就是一条静默变成 401 的用例</b>。
 * 默认请求把这件事收在一处:令牌从此是这些切片的<b>环境</b>,不是每条用例的参数。
 *
 * <p>🔴 代价必须点破:默认头一旦装上,这些类里就<b>写不出「不带令牌」那一条反向用例</b>了。
 * 所以那条反向用例不在这里,它在 {@code ApiAuthDefaultDenyTest} —— 那个类<b>不装</b>这份配置,
 * 于是它看到的是没有令牌的世界。<b>「全都带上令牌」如果没有一个不带令牌的地方,
 * 就等于把这条闸门测没了。</b>
 */
@TestConfiguration
public class ApiTestAuth {

    /**
     * 测试用户 —— 与 {@code auth} 侧的起号一致({@code B0} §3.3 从 10001 起)。
     *
     * <p>写成常量而不是散落的字面量:夹具里造的记录、令牌里的那个 id、断言里数的那些条数,
     * <b>必须是同一个人</b>。三处各写一个 {@code 1L} 的话,「按用户过滤」这件事在测试里
     * 会以「怎么一条都查不到」的形式出现,而排查方向完全指错。
     */
    public static final long USER_ID = 10001L;

    /** 另一个人。用来钉「别人的数据读不到」—— 只有两个用户在场时这条断言才有意义。 */
    public static final long OTHER_USER_ID = 10002L;

    private static final TokenService TOKENS = newTokenService();
    private static final IssuedToken TOKEN =
            TOKENS.issue(USER_ID, TokenScope.FULL, "接口测试");
    private static final IssuedToken READONLY_TOKEN =
            TOKENS.issue(USER_ID, TokenScope.READONLY, "接口测试(只读)");

    /** {@code Authorization} 头的值,给需要自己拼请求的用例。 */
    public static String bearer() {
        return "Bearer " + TOKEN.plaintext();
    }

    /** 只读令牌 —— 钉 {@code 403 READONLY_TOKEN} 那几条用它。 */
    public static String readonlyBearer() {
        return "Bearer " + READONLY_TOKEN.plaintext();
    }

    /**
     * 🔴 静态单例,而不是每个上下文一个新的。
     *
     * <p>{@link #TOKEN} 是在类初始化时签出来的,它只存在于<b>那一个</b> {@code TokenService}
     * 的库里。每个测试上下文各起一个新 store 的话,那条令牌在新库里查不到 ——
     * 于是全部用例变成 {@code 401},而失败消息只会说「没登录」。
     */
    @Bean
    public TokenService tokenService() {
        return TOKENS;
    }

    /**
     * 🔴 web 切片不扫 {@code AuthBeans},所以解析器得在这里补上。
     *
     * <p>不补的话 {@code AuthWebConfig} 会装上它那个 {@code DenyAll} 兜底
     * (「失败朝安全的那边倒」),于是每一个声明了 {@code CurrentSession} 的端点一律 401。
     */
    @Bean
    public CurrentSessionResolver currentSessionResolver(TokenService tokens) {
        return new CurrentSessionResolver(tokens);
    }

    /** 给这个切片里的每一个请求装上默认的 {@code Authorization} 头。 */
    @Bean
    public MockMvcBuilderCustomizer defaultBearerToken() {
        return builder -> builder.defaultRequest(
                MockMvcRequestBuilders.get("/").header(HttpHeaders.AUTHORIZATION, bearer()));
    }

    private static TokenService newTokenService() {
        try {
            return new TokenService(
                    new FileTokenStore(Files.createTempDirectory("kaodian-api-tokens")
                            .resolve("auth-tokens.json")),
                    Clock.systemUTC());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
