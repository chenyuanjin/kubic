package com.kaodian.server.api.support;

import com.kaodian.server.auth.AccessToken;
import com.kaodian.server.auth.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;

/**
 * 把 {@code Authorization: Bearer <token>} 解析成 {@link CurrentSession}。
 *
 * <h2>为什么是参数解析器,不是拦截器</h2>
 *
 * 拦截器要维护一张「哪些路径需要鉴权」的清单,而<b>清单是会漏的</b> ——
 * 新加一个端点忘了登记,它就默认不设防。
 * <p>
 * 参数解析器把这件事反过来:<b>方法签名里写了 {@link CurrentSession},它就一定被验过;
 * 没写,它就一定是公开端点</b>。需不需要鉴权写在方法自己身上,不在别处的一张表里。
 * 代价是「公开」仍然是默认值 —— 所以下面那句注释里的规矩必须被遵守。
 *
 * <h2>它<b>没有</b>接管 {@code /api/v1/records} 与 {@code /api/v1/syllabus}</h2>
 *
 * 那两组端点现在仍然是<b>单用户</b>的:{@code Touch} 上没有 {@code user_id},
 * 整个进程只有一份 {@code touches.json}。给它们加上鉴权而数据层不分租户,
 * 换来的不是安全,是一个「看起来分了用户其实没分」的假象 ——
 * 那比不加更危险,因为它会让人以为已经做完了。
 * <p>
 * 把行为层改成多租户({@code record_event.user_id})是一次独立的、更大的改动。
 * 在它完成之前,那两组端点靠的仍然是 {@code server.address=127.0.0.1}。
 */
public class CurrentSessionResolver implements HandlerMethodArgumentResolver {

    private static final String BEARER = "Bearer ";

    private final TokenService tokens;

    public CurrentSessionResolver(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentSession.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest webRequest, WebDataBinderFactory binder) {
        HttpServletRequest req = webRequest.getNativeRequest(HttpServletRequest.class);
        return resolve(req).map(CurrentSession::new)
                // 🔴 四种失败(没带头、格式不对、查不到、已过期/已吊销)对外是同一个 401。
                // 区分它们对用户没有区别(都要重新登录),对攻击者却是信息。
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                        "请先登录。"));
    }

    /** 不抛异常的版本 —— 给「登录了就带上、没登录也能用」的端点。 */
    public Optional<AccessToken> resolve(HttpServletRequest req) {
        if (req == null) {
            return Optional.empty();
        }
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        return tokens.verify(header.substring(BEARER.length()).trim());
    }
}
