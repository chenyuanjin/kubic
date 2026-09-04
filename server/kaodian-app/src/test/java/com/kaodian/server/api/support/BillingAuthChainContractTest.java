package com.kaodian.server.api.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商业化这一侧对 {@code B0-4} 那道闸的两条断言({@code M7-额度与订单} §7.2)。
 *
 * <h2>为什么这个类在 {@code api.support} 而不是 {@code billing}</h2>
 *
 * {@link ApiAuthFilter} 的 {@code WHITELIST} / {@code PREFIX} /
 * {@code READONLY_FORBIDDEN_PREFIXES} 是<b>包内可见</b>的 —— 而把它们放宽成 {@code public}
 * 就是改 {@code B0} 的横切件(KUBI-101 共同约束第 2 条:改包络 / 白名单要停手报回)。
 * <b>测试搬过来比把生产代码放宽便宜得多。</b>
 *
 * <p>🔴 本类<b>只断言,不修改</b>那三个常量。白名单本身归 {@code B0-4};
 * 这里验的是「商业化没有偷偷往里加一行」。
 */
class BillingAuthChainContractTest {

    @Test
    @DisplayName("🔴 白名单里带 billing 的只有回调那一行 —— GET /billing/plans 要令牌")
    void 档位列表不是匿名端点() {
        List<String> billingRows = ApiAuthFilter.WHITELIST.stream()
                .map(ApiAuthFilter.Anonymous::path)
                .filter(path -> path.contains("billing"))
                .toList();

        assertEquals(List.of(ApiAuthFilter.PREFIX + "/billing/notify/wxpay"), billingRows, """
                白名单里带 billing 的行不止回调那一条。
                🔴 GET /billing/plans 要令牌(`接口契约` §8.1)。理由不是「未登录的人不该看价格」,
                   是【那个界面不存在】—— 未登录只能看到产品说明与登录门,
                   没有一个未登录的用户走得到定价这一屏(U7.3 §2.4 的枚举里没有「未登录」这一态)。
                   一个匿名端点服务于一个不存在的界面,它唯一的实际用途是给爬价格的人省事。
                🔴 「加一行」只许因为真的多了一个匿名入口而发生,
                   不许靠改路径前缀把入口移出统计。""");
    }

    @Test
    @DisplayName("🔴 只读令牌打 /billing/** 与 /quota/** 一律 403,不论方法(锁 4)")
    void 只读令牌在商业化域一律被挡() {
        assertTrue(ApiAuthFilter.READONLY_FORBIDDEN_PREFIXES.contains("/billing/"),
                "只读令牌的前缀黑名单里少了 /billing/ —— 锁 4 要的是「不论方法」,"
                        + "只挡写方法会把 GET /billing/orders 放进 MCP/CLI");
        assertTrue(ApiAuthFilter.READONLY_FORBIDDEN_PREFIXES.contains("/quota/"),
                "只读令牌的前缀黑名单里少了 /quota/");
    }

    @Test
    @DisplayName("回调那一行在白名单里 —— 它不是匿名,是另一条鉴权链")
    void 回调那一行必须在白名单里() {
        assertTrue(ApiAuthFilter.WHITELIST.contains(new ApiAuthFilter.Anonymous(
                        org.springframework.http.HttpMethod.POST,
                        ApiAuthFilter.PREFIX + "/billing/notify/wxpay")), """
                白名单里没有 POST /billing/notify/wxpay。
                🔴 过滤器放行的是【应用令牌那一道,不是全部】:验签由 WxPayNotifyController 自己做
                   (平台证书验签 + 报文解密),验签失败直接拒,不进任何业务(§7.3)。
                   它在这张表上,是为了让「七行里真正匿名的只有六行」不被误读成「回调漏了」。""");
    }
}
