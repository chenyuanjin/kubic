package com.kaodian.server.api;

import com.kaodian.server.auth.TokenScope;
import com.kaodian.server.auth.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 🔴 只读令牌在 {@code M3} 这一域一律 {@code 403 READONLY_TOKEN} ——
 * {@code M3-骨架与覆盖度差集} §5.3(判据总表第 11 条)。
 *
 * <h2>两组路径,两种守法,都必须成立</h2>
 *
 * <table border="1">
 *   <caption>只读令牌被挡在哪一层</caption>
 *   <tr><th>路径</th><th>挡在哪</th><th>为什么是那一层</th></tr>
 *   <tr><td>{@code /agent/**}</td><td><b>{@code ApiAuthFilter} 的路径前缀黑名单</b></td>
 *       <td>它们<b>不论方法</b>都要挡 —— 包括 {@code GET}。会话内容是用户数据,
 *           而只读令牌是发给 MCP / CLI 的,那一面根本不该有对话历史</td></tr>
 *   <tr><td>{@code /assertions} · {@code /events/**} · {@code /profile/exam}</td>
 *       <td><b>控制器里的 {@code requireWrite()}</b></td>
 *       <td>它们不在前缀黑名单里,{@code GET /profile/exam} 更是一个读方法 ——
 *           但备考档案<b>不属于 MCP 五个 tool 的只读面</b>(§八),所以它也要挡</td></tr>
 * </table>
 *
 * <p>🔴 <b>两组一起测,是因为它们各自会以不同的方式失守。</b>
 * 前缀黑名单漏一条,新加的 {@code /agent/xxx} 静默变成只读可达;
 * 控制器漏一句 {@code requireWrite()},那一个端点静默变成只读可写。
 * 只测其中一组,另一组失守时这个类仍然全绿。
 *
 * <h2>同一组路径不带令牌再跑一遍</h2>
 *
 * {@code 403} 与 {@code 401} 是两句不同的话:「你这把钥匙开不了这扇门」
 * 与「你还没有钥匙」。合成一档,端上「登录过期了」与「这个令牌只能看」
 * 会走进同一个分支,而用户被要求重新登录去解决一个登录解决不了的问题。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 🔴 不要碰真实的 ~/.kaodian —— 与 ApiAuthDefaultDenyTest 同一条纪律。
        "kaodian.data.dir=${java.io.tmpdir}/kaodian-readonly-deny",
        "kaodian.agent.storage.root=${java.io.tmpdir}/kaodian-readonly-deny/agent"
})
class ReadonlyTokenDenyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TokenService tokens;

    /**
     * 一次调用 = 一个 {@code (方法, 路径, 请求体)}。
     *
     * <p>带上请求体是必要的:没有体的 {@code POST} 会先被 Jackson 判 {@code 400},
     * 而 {@code 400} 与 {@code 403} 在这条断言里长得一样地「不是 200」——
     * <b>一条会因为别的原因通过的断言,等于没有这条断言。</b>
     */
    private record Call(HttpMethod method, String path, String body) {

        static Call of(HttpMethod method, String path) {
            return new Call(method, path, null);
        }

        MockHttpServletRequestBuilder builder() {
            MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(method, path);
            if (body != null) {
                b = b.contentType(MediaType.APPLICATION_JSON).content(body);
            }
            return b;
        }
    }

    /**
     * {@code M3} 这一域全部需要 {@code full} 的调用。
     *
     * <p>🔴 {@code GET /agent/sessions} 与 {@code GET /profile/exam} 是<b>读方法</b>,
     * 它们在这张表上不是笔误 —— 见类注释那张表的第二列。
     */
    private static final List<Call> FULL_ONLY = List.of(
            // —— /agent/**:六条路径 × 各自的方法,不论读写 ——
            new Call(HttpMethod.POST, "/api/v1/agent/chat", "{\"message\":\"我的覆盖度怎么样\"}"),
            // ⚠️ 这一条今天还没有 controller(§十五 落差 11)。它留在表上不是笔误:
            //    路径前缀黑名单在【路由之前】生效,所以这一行钉的是「整个 /agent/ 子树都被挡住,
            //    包括还没写出来的那些端点」—— 而那正是「落法是路径黑名单,不是每个 controller
            //    的注解」这句话的全部内容(§5.3)。写出来那天它不需要改。
            Call.of(HttpMethod.GET, "/api/v1/agent/suggestions"),
            Call.of(HttpMethod.GET, "/api/v1/agent/sessions"),
            Call.of(HttpMethod.GET, "/api/v1/agent/sessions/s-not-there"),
            new Call(HttpMethod.PATCH, "/api/v1/agent/sessions/s-not-there", "{\"title\":\"改个名\"}"),
            Call.of(HttpMethod.DELETE, "/api/v1/agent/sessions/s-not-there"),

            // —— 备考档案:🔴 GET 也要 full,它不属于 MCP 五个 tool 的只读面 ——
            Call.of(HttpMethod.GET, "/api/v1/profile/exam"),
            new Call(HttpMethod.PUT, "/api/v1/profile/exam",
                    "{\"examType\":\"national\",\"examDate\":\"2027-11-28\"}"),

            // —— 断言:两个方法都是写 ——
            new Call(HttpMethod.POST, "/api/v1/assertions", "{\"nodeCode\":\"average-calc\"}"),
            new Call(HttpMethod.DELETE, "/api/v1/assertions", "{\"nodeCode\":\"average-calc\"}"),

            // —— 北极星埋点 ——
            new Call(HttpMethod.POST, "/api/v1/events/blindspot-opened",
                    "{\"localDate\":\"2026-09-04\",\"surface\":\"S-BLIND\","
                            + "\"entry\":\"home\",\"outcome\":\"data\"}"));

    @Test
    @DisplayName("🔴 只读令牌打这一域的每一条路径,一律 403 READONLY_TOKEN")
    void readonlyTokenIsForbiddenEverywhereInThisDomain() throws Exception {
        String readonly = "Bearer " + tokens
                .issue(ApiTestAuth.USER_ID, TokenScope.READONLY, "只读闸测试").plaintext();

        for (Call call : FULL_ONLY) {
            var response = mvc.perform(call.builder()
                            .header(HttpHeaders.AUTHORIZATION, readonly))
                    .andReturn().getResponse();

            assertEquals(403, response.getStatus(),
                    "%s %s 用只读令牌不是 403 —— 它要么漏了 requireWrite(),要么不在前缀黑名单里"
                            .formatted(call.method(), call.path()));
            // 🔴 光看状态码不够:403 也可能来自别的判断(比如额度耗尽),
            //    而端是按 code 分支的。这一行钉的是那个 code。
            assertEquals(true, response.getContentAsString().contains("READONLY_TOKEN"),
                    "%s %s 返了 403 但 code 不是 READONLY_TOKEN:%s"
                            .formatted(call.method(), call.path(), response.getContentAsString()));
        }
    }

    @Test
    @DisplayName("🔴 同一组路径不带令牌是 401 UNAUTHORIZED —— 与 403 必须两档")
    void noTokenIsUnauthorizedNotForbidden() throws Exception {
        for (Call call : FULL_ONLY) {
            var response = mvc.perform(call.builder()).andReturn().getResponse();

            assertEquals(401, response.getStatus(),
                    "%s %s 不带令牌却不是 401".formatted(call.method(), call.path()));
            assertEquals(true, response.getContentAsString().contains("UNAUTHORIZED"),
                    "%s %s 返了 401 但 code 不是 UNAUTHORIZED:%s"
                            .formatted(call.method(), call.path(), response.getContentAsString()));
        }
    }

    @Test
    @DisplayName("清单本身不许空掉,也不许悄悄少几行")
    void theInventoryItselfIsNotEmpty() {
        // 一条扫不到东西的断言会永远绿 —— 与 ApiAuthDefaultDenyTest 同一句。
        assertEquals(11, FULL_ONLY.size(),
                "M3 需要 full 的调用少了:六条 /agent/** + 两条 /profile/exam + "
                        + "两条 /assertions + 一条 /events/blindspot-opened");
    }
}
