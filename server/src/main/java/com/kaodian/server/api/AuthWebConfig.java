package com.kaodian.server.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 把 {@link CurrentSessionResolver} 挂进 MVC。
 *
 * <p>单独一个类,而不是让 {@link AuthBeans} 自己实现 {@code WebMvcConfigurer} ——
 * 后者会让那个 {@code @Configuration} 依赖自己声明的一个 bean,
 * 是一个能启动但脆弱的自引用。分开之后依赖方向是干净的单向。
 *
 * <h2>🔴 拿不到解析器时装的是「一律 401」,不是「不装」</h2>
 *
 * {@code @WebMvcTest} 的切片会把这个类扫进去(它是 {@code WebMvcConfigurer}),
 * 但不会把 {@link AuthBeans} 里的 {@link CurrentSessionResolver} 一起带进去。
 * 那种上下文里有两条路可走:
 *
 * <table border="1">
 *   <caption>缺解析器时怎么办</caption>
 *   <tr><th>做法</th><th>后果</th></tr>
 *   <tr><td>什么都不装</td>
 *       <td>{@code CurrentSession} 参数无人解析,Spring 会退回去<b>当成表单对象来绑</b> ——
 *           于是控制器拿到一个 userId 为 null 的会话,<b>而且不报错</b>。
 *           一个需要登录的端点在没有鉴权的上下文里静默变成了公开端点</td></tr>
 *   <tr><td><b>装一个只会拒绝的</b></td>
 *       <td>同样的端点一律 401。<b>失败朝安全的那边倒</b></td></tr>
 * </table>
 *
 * 这与 docs/13 §1.10「确认中不能就近归到成功」是同一条推理:
 * 两个方向的错误代价不对称时,朝代价小的那边倒。
 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<CurrentSessionResolver> resolver;

    public AuthWebConfig(ObjectProvider<CurrentSessionResolver> resolver) {
        this.resolver = resolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        HandlerMethodArgumentResolver found = resolver.getIfAvailable();
        resolvers.add(found != null ? found : new DenyAll());
    }

    /** 没有装配鉴权的上下文里,任何需要登录的端点一律 401。 */
    static final class DenyAll implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return CurrentSession.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                      NativeWebRequest request, WebDataBinderFactory binder) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录。");
        }
    }
}
