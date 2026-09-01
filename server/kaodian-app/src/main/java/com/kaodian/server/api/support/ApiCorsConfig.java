package com.kaodian.server.api.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 跨域策略 —— 一处声明,不散在注解上。
 *
 * <h2>为什么不用 {@code @CrossOrigin}</h2>
 *
 * 打在控制器上的 {@code @CrossOrigin} 会随着控制器变多而分叉:漏打一个就出现一个
 * 前端连不上的端点,多打一个就出现一个谁都没审过的放行口子。<b>跨域是部署形态的事,
 * 不是某个端点的事</b> —— 允许谁访问由 {@code application.properties} 说了算,
 * 从开发期的 Vite dev server 换到线上 Caddy 同源直出时,改的是配置不是代码。
 *
 * <h2>全局仍然只放行 GET 与 POST</h2>
 *
 * 把方法白名单写死在这里,等于给「只读」多加一道锁 ——
 * docs/technical/INDEX.md §6.5 的四道锁是有意冗余的,同一条思路:<b>一道锁失效不该导致整条线失守</b>。
 * 新增一种方法必须显式加,而「必须显式加」正是要的效果。
 *
 * <h2>🔴 {@code DELETE} 逐条路径开,永远不开给 {@code /api/**}</h2>
 *
 * 契约里需要 {@code DELETE} 的只有三条:{@code DELETE /api/account}(注销账号,§6.1)、
 * {@code DELETE /api/records/{id}}(删记录,§6.2)和 {@code DELETE /api/assertions}
 * (取消「我已掌握」,§6.4)。
 * 图省事的做法是往全局白名单里加一个 {@code DELETE} —— 那会<b>连带给
 * {@code /api/syllabus/**} 开了删除口子</b>,而骨架层的删除守则是
 * 「有记录就不许删,只能归档」。<b>那条守则保护的正是行为层的记录</b>,
 * 不能被一行图省事的跨域配置从旁边绕开。
 * <p>
 * 所以每需要一条就单开一条映射,<b>更窄的那些必须排在前面</b>:
 * Spring 按注册顺序取第一条匹配的规则,顺序反了就等于没写。
 * 名单变长是这个写法的代价,也正是它的作用 —— <b>加一条要动一次这个文件</b>。
 *
 * <h2>{@code Authorization} 头必须显式放行</h2>
 *
 * 浏览器的预检请求会把它列进 {@code Access-Control-Request-Headers};
 * 不在白名单里,预检就失败 —— 表现是<b>登录之后每一个请求都被浏览器拦下,
 * 而服务端日志里一条都看不到</b>。这是接入 Bearer 令牌时最常撞的一个坑。
 */
@Configuration
public class ApiCorsConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public ApiCorsConfig(@Value("${kaodian.api.cors.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 🔴 更窄的先注册 —— Spring 取第一条匹配的规则,顺序反了这些就永远轮不到。
        registry.addMapping("/api/account")                 // 注销账号(docs/technical/INDEX.md §6.1)
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("Content-Type", "Authorization")
                .allowCredentials(false)
                .maxAge(3600);

        // 删一条记录(docs/technical/INDEX.md §6.2)。范围刻意写成 /api/records/* 而不是 /api/records/** ——
        // 单层通配只覆盖 {id} 这一层,将来 /api/records/{id}/audio、/image、/tags/** 那些
        // 子路径要开什么方法,得各自过一遍这里,而不是被这一行提前放行。
        registry.addMapping("/api/records/*")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("Content-Type", "Authorization")
                .allowCredentials(false)
                .maxAge(3600);

        // 取消「我已掌握」(docs/technical/INDEX.md §6.4 的 DELETE /assertions)。
        // 🔴 写成 /api/assertions 这一条路径,不是 /api/assertions/** ——
        //    契约里这两个端点都没有路径变量(body 只接受 nodeCode),
        //    多一层通配就是提前给一批还不存在的子路径放行。
        registry.addMapping("/api/assertions")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("POST", "DELETE")
                .allowedHeaders("Content-Type", "Authorization")
                .allowCredentials(false)
                .maxAge(3600);

        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type", "Authorization")
                // 现在没有 Cookie 会话(令牌方案见 docs/technical/INDEX.md §7.4),不需要带凭据的跨域。
                // 关掉它才能让 allowedOrigins 保持成一份可枚举的清单。
                .allowCredentials(false)
                .maxAge(3600);
    }
}
