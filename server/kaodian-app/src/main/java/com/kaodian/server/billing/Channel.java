package com.kaodian.server.billing;

import com.kaodian.server.api.support.ApiException;

/**
 * 支付通道 —— 🔴 <b>恰好三个取值</b>({@code 接口契约} §8.3,{@code M7-额度与订单} §5.2)。
 *
 * <h2>🚫 支付宝不加</h2>
 *
 * {@code Q-8} / {@code U7.4} 缺口 4 是「<b>做不做</b>」还没裁定,
 * 而<b>一个取值加进枚举就等于替它答了「做」</b>。
 *
 * <h2>🔴 {@link #APPLE_IAP} 是三值那一版,不是二值</h2>
 *
 * {@code 技术架构与接口契约} §5.5.2 与它那张 ER 图写的是<b>二值</b>
 * ({@code wx_jsapi} / {@code wx_virtual_ios});{@code 接口契约} §8.3 与 {@code M7} §5.4 是<b>三值</b>。
 * 两边都是目标态,裁定<b>取三值</b>({@code M7} §十二 冲突 7):§8.3 是端点级真源,
 * 且逐字写着 {@code apple_iap} 是 {@code U7.5} 缺口「内购通道取值」要的那一个。
 * 判据 {@code values().length == 3} 会让照二值建的枚举<b>当场跑红</b>。
 */
public enum Channel {

    /** 小程序 / 公众号内。 */
    WX_JSAPI("wx_jsapi", "微信支付"),

    /** iOS 上的微信虚拟支付。 */
    WX_VIRTUAL_IOS("wx_virtual_ios", "微信支付"),

    /** Apple 内购。收据校验走 {@code POST …/receipt/verify}(§4.5)。 */
    APPLE_IAP("apple_iap", "Apple 内购");

    private final String wireName;
    private final String displayName;

    Channel(String wireName, String displayName) {
        this.wireName = wireName;
        this.displayName = displayName;
    }

    public String wireName() {
        return wireName;
    }

    /** {@code GET /billing/channels} 那一格的 {@code name}。 */
    public String displayName() {
        return displayName;
    }

    /** 请求体 → 枚举。不认识的取值 → 400,回声截断。 */
    public static Channel ofWireName(String s) {
        if (s != null) {
            for (Channel c : values()) {
                if (c.wireName.equals(s.trim())) {
                    return c;
                }
            }
        }
        throw ApiException.unknownValue("VALIDATION_FAILED", "支付通道", s);
    }

    /** Apple 那一条走收据校验,不走微信查单。 */
    public boolean isAppleIap() {
        return this == APPLE_IAP;
    }
}
