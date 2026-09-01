package com.kaodian.server.auth.vendor;

/**
 * 微信侧的全部 HTTP 调用 —— <b>就这一个接口,别处不许再出现 {@code api.weixin.qq.com}</b>。
 *
 * <p>与 {@code recognize} 包「模型调用只能出现在那一层」是同一条纪律(docs/technical/后端系统设计与组件接入.md §二),
 * 理由也一样:散开之后就没有切换点了,而微信的接口是会变的
 * (光是「换取手机号」这一件事,官方就已经废弃过一版旧组件)。
 *
 * <h2>三条入口的流程差异,全部收在这个接口的两个方法里</h2>
 *
 * <pre>
 *   小程序    wx.login → code ──────────────────→ {@link #exchangeMiniProgramCode}
 *   公众号 H5  跳 authorize → 回跳带 code ────────→ {@link #exchangeOAuthCode}
 *   PC 扫码    跳 qrconnect → 回跳带 code ────────→ {@link #exchangeOAuthCode}
 * </pre>
 *
 * 后两条的<b>授权 URL 不同、appid 也不同</b>,但换取那一步是同一个端点,
 * 所以第一步由 {@link #buildAuthorizeUrl} 按入口分派,第二步合并。
 */
public interface WeChatClient {

    /**
     * 第一步:生成让用户跳过去的授权 URL。
     *
     * @param redirectUri 回跳地址。<b>必须与公众号/开放平台后台配置的域名一致</b>,
     *                    否则用户看到的是「redirect_uri 参数错误」而不是授权页
     * @param state       防 CSRF 的一次性串。<b>回跳时原样带回,服务端必须校验</b> ——
     *                    不校验的话,攻击者可以把自己的 code 塞给受害者的浏览器,
     *                    把受害者的账号绑到自己的微信上
     */
    String buildAuthorizeUrl(WeChatEntry entry, String redirectUri, String state);

    /** 小程序:{@code auth.code2Session}。 */
    WeChatIdentity exchangeMiniProgramCode(String jsCode) throws WeChatException;

    /** 公众号 H5 / PC 扫码:{@code /sns/oauth2/access_token}(必要时再取 unionid)。 */
    WeChatIdentity exchangeOAuthCode(WeChatEntry entry, String code) throws WeChatException;

    /**
     * 小程序手机号快速验证:{@code phonenumber.getPhoneNumber}。
     *
     * <p>它把「微信登录」和「手机号登录」合成一步 —— 用户点一下就同时有了 unionid 和手机号,
     * 两条通道从第一天起就落在同一个账号上,{@code R-33} 那个「两端被拆两半」根本不会发生。
     *
     * <p>🔴 <b>但它是收费的:0.03 元/次成功调用,每个小程序 1000 次体验额度。</b>
     * 而且它<b>不进额度体系</b> —— 和短信一样,是注册环节的按次外部账单
     * (docs/technical/后端系统设计与组件接入.md §1.8 引 {@code 11} §3.2:对它计费等于对注册计费)。
     * 所以它同样必须被频控与滑块管住,不能因为「体验好」就无限量开着。
     *
     * @param phoneCode 前端 {@code bindgetphonenumber} 回调里的动态令牌。<b>5 分钟有效,单次消费</b>
     * @return 不带国家码的 11 位手机号
     */
    String exchangePhoneCode(String phoneCode) throws WeChatException;

    /** 这个实现是否真的在调微信。为 {@code false} 时端点应当拒绝服务而不是返回假数据。 */
    boolean isReal();
}
