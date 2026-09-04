package com.kaodian.server.api;

import com.jayway.jsonpath.JsonPath;
import com.kaodian.server.api.config.ConfigController;
import com.kaodian.server.config.BlindspotCaliber;
import com.kaodian.server.coverage.BlindspotOrder;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/config/effective} —— {@code M3-骨架与覆盖度差集} §3.1。
 *
 * <h2>🔴 这个文件验的第一件事是「两个字段恒在」</h2>
 *
 * 它是「同一个数只许有一个来源」的兜底端点,<b>没有「服务端也没配」这一档</b>:
 * 少一个字段,端就只能退回自己那份默认值,而那份默认值正是这个端点存在的理由本身。
 * 失败方式是无声的 —— 少一个字段不会报错,端会安静地用本地默认,
 * 榜单排序悄悄换成另一个口径,而两边都以为自己拿的是服务端口径。
 *
 * <h2>🔴 第二件事是「没有第三个字段」</h2>
 *
 * 多一个字段就是多一个数,而多出来的那个数一定会被端读、被缓存、被当默认值,
 * 于是下一次拿不到时要退让的东西从两个变成三个 —— 而偏离登记的闭集只认两个名字。
 */
@WebMvcTest(controllers = ConfigController.class)
// web 切片不扫 @Configuration;ApiTestAuth 给每个请求装上真令牌(B0-4 默认拒绝)
@Import(ApiTestAuth.class)
class ConfigEffectiveTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 🔴 两个值都按 §3.1 的原文钉死,而不是拿 {@link BlindspotCaliber#DEFAULT} 对照自己 ——
     * 拿常量对照自己的那一版,常量被改成 {@code 5} 时测试照样绿,
     * 而契约上写着的 {@code 20} 已经不成立了。
     */
    @Test
    @DisplayName("🔴 两个字段恒在:blindspotOrderBy=recent5y_count,blindspotTop=20(§3.1 逐字)")
    void bothFieldsAreAlwaysThere() throws Exception {
        mockMvc.perform(get("/api/v1/config/effective"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blindspotOrderBy").value("recent5y_count"))
                .andExpect(jsonPath("$.blindspotTop").value(20));
    }

    /**
     * 取值域来自 {@link BlindspotOrder} 那个闭集,<b>这里不重抄一份四个字符串的清单</b> ——
     * 抄一份就是给同一个闭集造第二个来源,而两份清单迟早会不一样。
     */
    @Test
    @DisplayName("blindspotOrderBy 落在四个取值域之内;blindspotTop 落在 1..100 之内")
    void theValuesAreInTheirDomains() throws Exception {
        String body = mockMvc.perform(get("/api/v1/config/effective"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String orderBy = JsonPath.read(body, "$.blindspotOrderBy").toString();
        assertNotNull(BlindspotOrder.of(orderBy),
                "🔴 下发的排序口径不在闭集四个之内:" + orderBy
                        + " —— 端把它原样送回 /coverage/blindspots 会拿到 422 UNKNOWN_ORDER_BY");

        int top = (int) JsonPath.read(body, "$.blindspotTop");
        assertTrue(top >= 1 && top <= 100,
                "blindspotTop 越界:" + top + " —— 这是一份「先补这几个」的清单,不是导出接口");
    }

    @Test
    @DisplayName("🔴 响应里只有这两个键 —— 多一个字段就是多一个要端去缓存的默认值")
    void thereIsNoThirdField() throws Exception {
        mockMvc.perform(get("/api/v1/config/effective"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", Matchers.hasSize(2)));
    }

    /**
     * 🔴 <b>不进那张七行匿名白名单</b>(§3.1 鉴权那一行)。
     *
     * <p>一个匿名可读的口径端点会立刻被端当成「登录前就能拿到默认值」,
     * 而那正好是把默认值搬回端上的路。
     *
     * <p>这里用「带了一个用不了的 Authorization 头」来验:{@link ApiTestAuth} 给这个切片里的
     * 每个请求都装了默认令牌头,而<b>请求自己设过的头不会被默认头覆盖</b>,所以这是本类里
     * 唯一能写出反向用例的写法。「一个头都不带」那一支由 {@code ApiAuthDefaultDenyTest} 兜着 ——
     * 它枚举 {@code RequestMappingHandlerMapping} 里的<b>全部</b>端点,这个端点自动在内。
     */
    @Test
    @DisplayName("🔴 鉴权 full:没有可用令牌就是 401,不是匿名端点")
    void itIsNotAnonymous() throws Exception {
        for (String useless : new String[]{"Basic dXNlcjpwYXNz", "Bearer 不是一条真令牌", "at_xxx"}) {
            mockMvc.perform(get("/api/v1/config/effective")
                            .header(HttpHeaders.AUTHORIZATION, useless))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    /**
     * 只读令牌照常读得到 —— 🔴 <b>这里不许出现 {@code requireWrite}</b>。
     *
     * <p>这是一次纯读。把口径端点挡在只读令牌之外,MCP/CLI 那一侧就拿不到口径,
     * 于是它只能揣一份自己的默认值 —— 正是这个端点要消灭的那件事。
     */
    @Test
    @DisplayName("只读令牌读得到口径 —— 它是一次纯读,不是写操作")
    void readonlyTokensCanReadIt() throws Exception {
        String body = mockMvc.perform(get("/api/v1/config/effective")
                        .header(HttpHeaders.AUTHORIZATION, ApiTestAuth.readonlyBearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(20, (int) JsonPath.read(body, "$.blindspotTop"),
                "只读令牌拿到的口径必须与 full 令牌一模一样 —— 两份口径就是两个来源");
    }
}
