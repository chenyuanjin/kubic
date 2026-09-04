package com.kaodian.server.auth.vendor;

/**
 * 微信授权登录的三条入口 —— <b>它们是三个不同的应用,三套不同的 appid/secret</b>。
 *
 * <h2>这是接入时最容易踩的那一脚</h2>
 *
 * 三条入口最后都调同一个 {@code /sns/oauth2/access_token},于是很容易被当成一件事。
 * 但公众号的 appid 换不出网站应用的 code,反过来也不行 —— 表现是
 * {@code errcode 40029 invalid code},而排查时几乎所有人都会先去怀疑 code 本身。
 *
 * <h2>资质与费用各不相同(2026-08 核)</h2>
 *
 * <table border="1">
 *   <caption>三条入口的门槛</caption>
 *   <tr><th>入口</th><th>平台</th><th>门槛</th></tr>
 *   <tr><td>小程序</td><td>微信公众平台</td>
 *       <td>非个人主体 + 已认证(¥300/年);<b>还依赖 ICP 备案</b></td></tr>
 *   <tr><td>公众号 H5</td><td>微信公众平台 · 服务号</td>
 *       <td>已认证服务号(¥300/年);<b>订阅号没有网页授权</b></td></tr>
 *   <tr><td>PC 扫码</td><td>微信开放平台</td>
 *       <td>开发者资质认证 ¥300(境内)+ 网站应用审核</td></tr>
 * </table>
 *
 * <b>三条全开 = 三笔认证费 + 三次审核。</b> 而 docs/technical/INDEX.md §7.2 已定:
 * 阶段 2 只做手机号,微信整体在<b>阶段 2 后</b>。
 * 所以这三个枚举值今天的作用是<b>把位置留出来</b>,不是让人现在就去开通。
 */
public enum WeChatEntry {

    /**
     * 小程序 {@code wx.login} → {@code auth.code2Session}。
     *
     * <p>形态上最省事的一条(没有回跳、没有 state),但它排在最后 ——
     * 小程序依赖 ICP 备案,而备案是 3-5 周的行政等待(docs/technical/INDEX.md §7.2)。
     */
    MINI_PROGRAM("mini"),

    /**
     * 公众号网页授权(微信内置浏览器里的 H5)。
     *
     * <p>🔴 <b>阶段 2 的头 10 个真实用户用的是 H5</b> —— 所以真要接微信,
     * 这一条比小程序更早有用。scope 用 {@code snsapi_userinfo}:
     * {@code snsapi_base} 是静默授权,只能拿到 openid,<b>拿不到 unionid</b>,
     * 而 unionid 才是跨端同一个人的锚点。
     */
    OFFICIAL_ACCOUNT_H5("official_h5"),

    /** 网站应用扫码登录,{@code scope=snsapi_login}。桌面端用。 */
    WEBSITE_QR("open_web");

    private final String wireName;

    WeChatEntry(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static WeChatEntry ofWireName(String s) {
        for (WeChatEntry v : values()) {
            if (v.wireName.equals(s)) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知的微信入口:" + s);
    }
}
