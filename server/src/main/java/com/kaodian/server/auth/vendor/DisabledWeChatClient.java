package com.kaodian.server.auth.vendor;

/**
 * 默认的微信实现 —— <b>一律拒绝,而不是返回假数据</b>。
 *
 * <h2>为什么不做一个 Stub 返回假 openid</h2>
 *
 * 因为那会让「微信登录已经能用了」这件事在本机为真、在线上为假,
 * 而两者之间没有任何一次报错来提醒。{@code recognize} 包的 Stub 之所以可以返回假结果,
 * 是因为它的失败是<b>局部的</b>(一条记录没打上标);登录的失败是<b>全局的</b>
 * (账号体系整个建错)。
 *
 * <h2>它默认开着,是因为微信通道排在关卡 2 之后</h2>
 *
 * docs/10 §6.1 的阶段列里,{@code /auth/wechat/login} 与两个 {@code bind}、
 * 两个 {@code merge} 全部标着「关卡 2 后」;§7.2 的原文是
 * <b>「{@code 1.3.1.1.1} 已定:阶段 2 只做手机号,不做微信登录」</b>。
 * <p>
 * 而三条入口全开需要三笔认证费({@link WeChatEntry})和一次 ICP 备案。
 * <b>在关卡 2 之前掏这笔钱,买的是一个还没被验证的方向</b> —— 04 的关卡判据说的就是这件事。
 * <p>
 * 代码留着、开关关着,是这条纪律唯一正确的落地形态:
 * 到那天只需要改配置,而在那天之前一分钱不花。
 */
public class DisabledWeChatClient implements WeChatClient {

    private static final String WHY =
            "微信登录尚未启用 —— docs/10 §7.2 定的是关卡 2 后。"
                    + "启用需要:①三条入口各自的 appid/secret ②配置 kaodian.auth.wechat.enabled=true";

    @Override
    public String buildAuthorizeUrl(WeChatEntry entry, String redirectUri, String state) {
        throw new IllegalStateException(WHY);
    }

    @Override
    public WeChatIdentity exchangeMiniProgramCode(String jsCode) throws WeChatException {
        throw new WeChatException(WHY, -1);
    }

    @Override
    public WeChatIdentity exchangeOAuthCode(WeChatEntry entry, String code) throws WeChatException {
        throw new WeChatException(WHY, -1);
    }

    @Override
    public String exchangePhoneCode(String phoneCode) throws WeChatException {
        throw new WeChatException(WHY, -1);
    }

    @Override
    public boolean isReal() {
        return false;
    }
}
