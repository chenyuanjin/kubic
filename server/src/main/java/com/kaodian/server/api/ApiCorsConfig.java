package com.kaodian.server.api;

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
 * docs/10 §6.5 的四道锁是有意冗余的,同一条思路:<b>一道锁失效不该导致整条线失守</b>。
 * 新增一种方法必须显式加,而「必须显式加」正是要的效果。
 *
 * <h2>🔴 {@code DELETE} 只开给注销那<b>一条路径</b>,不开给 {@code /api/**}</h2>
 *
 * docs/10 §6.1 要求 {@code DELETE /api/account}(注销账号)。
 * 图省事的做法是往全局白名单里加一个 {@code DELETE} —— 那会<b>同时给
 * {@code /api/records} 和 {@code /api/syllabus} 开了删除口子</b>,
 * 而骨架层的删除守则是「有记录就不许删,只能归档」。
 * <p>
 * 所以这里注册了两条映射,<b>更窄的那条必须排在前面</b>:
 * Spring 按注册顺序取第一条匹配的规则,顺序反了就等于没写。
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
        // 🔴 更窄的先注册 —— Spring 取第一条匹配的规则,顺序反了这条就永远轮不到。
        // 注销是整个接口表里唯一一个 DELETE(docs/10 §6.1)。
        registry.addMapping("/api/account")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("Content-Type", "Authorization")
                .allowCredentials(false)
                .maxAge(3600);

        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type", "Authorization")
                // 现在没有 Cookie 会话(令牌方案见 docs/10 §7.4),不需要带凭据的跨域。
                // 关掉它才能让 allowedOrigins 保持成一份可枚举的清单。
                .allowCredentials(false)
                .maxAge(3600);
    }
}
