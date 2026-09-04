package com.kaodian.server.api.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 🔴 <b>默认拒绝 —— 这条是 {@code B0-4} 的全部意义</b>({@code B0} §5.5 判据 ①)。
 *
 * <h2>它验的不是某几个端点,是那个默认值</h2>
 *
 * 判据原文:「<b>新增一个 controller 忘了加鉴权,它必须默认打不通。</b>」
 * 所以这里不写一张「该验的端点清单」——清单是会漏的,而漏掉的那一个正好就是
 * 「忘了」的那一个。它<b>枚举 {@link RequestMappingHandlerMapping} 里的全部端点</b>,
 * 白名单之外的每一个都必须在没有令牌时是 {@code 401}。
 * <p>
 * 断言的价值等于它扫过的范围,所以范围本身不能靠人记得去维护 ——
 * 与 {@code ImageRetentionTest} 那句「枚举而不是列举」是同一条。
 *
 * <h2>🔴 这个类<b>刻意不装</b> {@link ApiTestAuth}</h2>
 *
 * 其余接口测试都装了那份配置,于是它们的每个请求都自带一条真令牌 ——
 * <b>「全都带上令牌」如果没有一个不带令牌的地方,就等于把这条闸门测没了。</b>
 * 这里就是那个地方:它看到的是没有令牌的世界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 🔴 不要碰真实的 ~/.kaodian:这个上下文会起 FileTouchStore 与 OrphanGuard,
        //    跑一次测试就往用户目录里播一次种,是这一类测试最容易留下的副作用。
        "kaodian.data.dir=${java.io.tmpdir}/kaodian-default-deny",
        "kaodian.agent.storage.root=${java.io.tmpdir}/kaodian-default-deny/agent"
})
class ApiAuthDefaultDenyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("🔴 白名单之外的每一个端点,在没有令牌时都是 401")
    void everyEndpointOutsideTheWhitelistIsUnauthorizedWithoutAToken() throws Exception {
        List<Endpoint> endpoints = apiEndpoints();

        // 一条扫不到东西的断言会永远绿,那比没有更糟(与 ImageRetentionTest 同一句)。
        assertFalse(endpoints.isEmpty(), "一个 /api 端点都没枚举到 —— 这个测试等于没跑");

        for (Endpoint endpoint : endpoints) {
            if (ApiAuthFilter.WHITELIST.contains(
                    new ApiAuthFilter.Anonymous(endpoint.method(), endpoint.path()))) {
                continue;
            }
            int status = mvc.perform(
                            MockMvcRequestBuilders.request(endpoint.method(), endpoint.path()))
                    .andReturn().getResponse().getStatus();

            assertEquals(401, status,
                    "端点 %s %s 没有令牌却不是 401 —— 忘了它默认应当打不通"
                            .formatted(endpoint.method(), endpoint.path()));
        }
    }

    @Test
    @DisplayName("白名单那七行确实打得通(至少没有被这道闸挡住)")
    void theWhitelistItselfIsNotBlocked() throws Exception {
        for (ApiAuthFilter.Anonymous anonymous : ApiAuthFilter.WHITELIST) {
            int status = mvc.perform(
                            MockMvcRequestBuilders.request(anonymous.method(), anonymous.path()))
                    .andReturn().getResponse().getStatus();

            // 🔴 只断言「不是 401」。这七行里有的今天还没有 controller
            //    (/billing/notify/wxpay,那是另一条鉴权链),有的会因为缺请求体回 400 ——
            //    那些都是端点自己的事。这道闸只负责别把它们拦在门外。
            assertEquals(false, status == 401,
                    "白名单里的 %s %s 却被挡成了 401 —— 登录门会点不动"
                            .formatted(anonymous.method(), anonymous.path()));
        }
    }

    /** 一个可以真的发出去的 {@code (method, path)}。 */
    private record Endpoint(HttpMethod method, String path) {
    }

    /**
     * 枚举 {@code /api} 下的全部端点。
     *
     * <p>路径变量换成一个固定占位值 —— 这个测试问的是「有没有令牌」,
     * 那一步发生在任何一次 {@code @PathVariable} 绑定之前,所以占位值是什么都不影响结论。
     */
    private List<Endpoint> apiEndpoints() {
        List<Endpoint> result = new ArrayList<>();
        Set<String> seen = new TreeSet<>();

        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<HttpMethod> methods = info.getMethodsCondition().getMethods().stream()
                    .map(m -> HttpMethod.valueOf(m.name()))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (methods.isEmpty()) {
                methods = Set.of(HttpMethod.GET);       // 没写方法的映射:拿 GET 当代表
            }
            for (String pattern : patternsOf(info)) {
                if (!pattern.startsWith(ApiAuthFilter.PREFIX)) {
                    continue;                           // 生效范围只有 /api,健康检查不在这儿
                }
                String path = pattern.replaceAll("\\{[^/]*}", "placeholder");
                for (HttpMethod method : methods) {
                    if (HttpMethod.OPTIONS.equals(method)) {
                        continue;                       // 预检不带令牌,过滤器有意放行
                    }
                    if (seen.add(method + " " + path)) {
                        result.add(new Endpoint(method, path));
                    }
                }
            }
        }
        return result;
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return Set.of();
    }
}
