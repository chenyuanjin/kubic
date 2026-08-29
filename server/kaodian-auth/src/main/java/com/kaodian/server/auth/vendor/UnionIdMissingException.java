package com.kaodian.server.auth.vendor;

/**
 * 微信回来了,但<b>没有 unionid</b> —— 而配置说这里必须有。
 *
 * <h2>为什么这值得单独一个异常,而不是继续降级用 openid</h2>
 *
 * 拿不到 unionid 的原因<b>只有一个</b>:这个应用没有绑定到微信开放平台账号
 * (`UnionID 机制`:同一开放平台账号下的公众号 / 小程序 / 网站应用,unionid 唯一)。
 * 那是一次控制台配置,不是运行时状况。
 * <p>
 * 于是:<b>已经有开放平台账号、并且绑定做对了的话,unionid 必然存在</b>。
 * 这时候还拿不到,说明绑定掉了或者配错了应用 —— 而降级用 openid 建账号的后果是
 * <b>攒出一批 {@code WX_OPEN} 账号,等着将来靠 {@code R-63} 那条自愈路径去修</b>。
 * <p>
 * 能自愈不等于该发生。这与 {@code AuthBeans#checkVendorPairing}、{@link
 * com.kaodian.server.auth.PhoneKeyGuard} 是同一条纪律:
 * <b>配置错误要在它开始产生脏数据之前响亮地失败。</b>
 *
 * <h2>⚪ 它是可以关掉的,而且关掉是合法选择</h2>
 *
 * {@code kaodian.auth.wechat.require-unionid=false} → 回到降级行为。
 * 「确知没有开放平台账号、接受账号分裂风险」是一个真实存在的状态
 * (比如只做一个入口、根本不会有第二个入口的时候)。
 * <p>
 * 与 {@code accept-key-loss} 同一形态:<b>没有出路的守卫会被撞上它的人在半夜关掉</b>,
 * 所以出路要给,而且要写清楚代价。
 */
public class UnionIdMissingException extends WeChatException {

    public UnionIdMissingException(WeChatEntry entry) {
        super("微信返回的身份里没有 unionid,而 kaodian.auth.wechat.require-unionid=true。"
                + "入口=" + entry.wireName() + "。"
                + "唯一的原因是这个应用没有绑定到微信开放平台账号 —— "
                + "请在开放平台后台把它绑上(同主体的公众号/小程序/网站应用都要绑到【同一个】开放平台账号,"
                + "unionid 才会一致)。"
                + "确知没有开放平台、接受同一个人从不同入口进会得到两个账号(R-33)时,"
                + "可置 kaodian.auth.wechat.require-unionid=false", -1);
    }
}
