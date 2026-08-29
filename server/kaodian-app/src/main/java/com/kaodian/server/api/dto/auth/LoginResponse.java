package com.kaodian.server.api.dto.auth;

import java.time.Instant;

/**
 * 登录成功。
 *
 * <p>{@code token} 是<b>唯一一次</b>能拿到明文令牌的地方(docs/10 §7.4:签发时返回一次,
 * 不可再查看)。客户端必须自己存好。
 *
 * @param isNewAccount 这次是不是新建了账号。产品侧据此决定要不要走首次引导(D24/H17);
 *                     <b>关卡 3 的累计注册数由服务端自己记,不依赖客户端回报这个值</b>
 * @param maskedPhone  打码手机号,如 {@code 138****6027}。可空(微信通道注册时还没绑号)
 * @param needsPhoneBinding 🔴 <b>这个账号还没有手机号,产品应当引导补绑</b>。
 *                     docs/10 §7.1:已登录状态下授权 / 补绑<b>是最顺的那条路径</b>,
 *                     比事后走合并便宜得多 —— 而不补绑的代价是这个人下次换个入口进来
 *                     可能又多一个账号({@code R-33})
 * @param splitMergeToken 🔴 <b>登录成功,但发现这个人在库里有两个账号。</b>
 *                     这是一次性合并令牌,<b>只表示「可以合并」,不代表已经合并</b>——
 *                     合并永远由用户显式发起、预览、二次确认(docs/10 §7.1)。
 *                     没有分裂则为 {@code null}
 */
public record LoginResponse(
        String token,
        Instant expiresAt,
        String userId,
        boolean isNewAccount,
        String maskedPhone,
        boolean needsPhoneBinding,
        String splitMergeToken
) {
}
