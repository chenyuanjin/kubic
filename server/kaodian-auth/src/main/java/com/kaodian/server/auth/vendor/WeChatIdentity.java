package com.kaodian.server.auth.vendor;

/**
 * 微信换回来的那个人。
 *
 * <h2>🔴 这里没有昵称,没有头像 URL</h2>
 *
 * {@code /sns/userinfo} 能拿到 {@code nickname} / {@code headimgurl} / {@code sex} / {@code city},
 * <b>这个产品一个都不要</b>。
 * <p>
 * 理由和 {@code Touch} 里没有内容字段是同一条:<b>不是不填,是不建这个位置</b>。
 * 昵称头像唯一的用途是让个人中心好看一点,代价是从此持有一批用户的画像数据 ——
 * 而 01 §2.2 的能力边界里没有一条需要知道用户是谁。
 * <p>
 * 于是网页授权其实可以只要 {@code snsapi_base}……但那样拿不到 unionid。
 * 所以 scope 仍然是 {@code snsapi_userinfo},<b>只是拿到的信息我们只留 unionid 与 openid</b>。
 *
 * @param openid  这个人在<b>这个应用</b>里的 id。换个入口就变
 * @param unionid 这个人在<b>整个开放平台账号</b>下的 id。可能为空 —— 见 {@link #hasUnionId}
 */
public record WeChatIdentity(String openid, String unionid) {

    public WeChatIdentity {
        if (openid == null || openid.isBlank()) {
            throw new IllegalArgumentException("微信身份必须有 openid");
        }
    }

    /**
     * 有没有拿到 unionid。
     *
     * <p>拿不到的唯一原因是<b>这个小程序/网站应用没有绑定到同一个微信开放平台账号</b> ——
     * 一次控制台配置,不是代码问题。
     * <p>
     * 但它的后果很具体:没有 unionid 就只能用 openid 建账号,于是<b>同一个人从
     * 小程序进和从网站进会得到两个账号</b>,行为层被拆两半({@code R-33})。
     * 所以这个方法存在,是为了让「没绑开放平台」表现为一个可被检查、可被告警的事实,
     * 而不是一个静默降级。
     */
    public boolean hasUnionId() {
        return unionid != null && !unionid.isBlank();
    }
}
