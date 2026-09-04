package com.kaodian.server.api.config;

import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.config.BlindspotCaliber;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 默认口径下发({@code M3-骨架与覆盖度差集} §3.1)。
 *
 * <h2>🔴 它是「同一个数只许有一个来源」的那个来源</h2>
 *
 * 端上不许揣一份默认口径。要么从这里拿,要么退让到本地默认<b>并留下痕迹</b>
 * (那条痕迹由 {@link CaliberDeviationFilter} 收)。所以这个端点的两个字段
 * <b>恒在</b> —— 见 {@link BlindspotCaliber} 类注释:它自己缺值就等于没有来源。
 *
 * <h2>🔴 鉴权是 {@code full},<b>不进那张七行匿名白名单</b></h2>
 *
 * 一个匿名可读的口径端点会立刻被端当成「登录前就能拿到默认值」,
 * 而那正好是把默认值搬回端上的路 —— 端一旦能在登录前拿到,它就会缓存,
 * 缓存下来的那一份从此就是第二个来源。<b>没有令牌就是 401</b>,由
 * {@code ApiAuthFilter} 默认拒绝兜住,{@code ApiAuthDefaultDenyTest} 逐个端点枚举着验。
 *
 * <h2>只读令牌照常放行</h2>
 *
 * 这是一次纯读,所以<b>不调 {@code CurrentSession#requireWrite}</b>。
 * 只读令牌打不开的是 {@code ApiAuthFilter#READONLY_FORBIDDEN_PREFIXES} 那五条前缀,
 * {@code /config/} 不在其中。
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    /**
     * @param session 🔴 <b>方法体用不到它,但它必须在签名里</b> —— 声明这个参数就是声明
     *                「这个端点要有人」({@code CurrentSessionResolver} 负责回答「进来的是谁」)。
     *                拿掉它,这个端点在结构上就与匿名端点没有区别了,
     *                而那正是本类注释里被否掉的那条路
     */
    @GetMapping("/effective")
    public EffectiveConfigResponse effective(CurrentSession session) {
        // 🔴 直接读那一份常量。这里不许再有 orElse / 默认值 —— 兜底端点自己兜底就是没有来源。
        return EffectiveConfigResponse.of(BlindspotCaliber.DEFAULT);
    }
}
