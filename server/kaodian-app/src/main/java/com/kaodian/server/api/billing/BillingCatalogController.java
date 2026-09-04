package com.kaodian.server.api.billing;

import com.kaodian.server.api.billing.dto.ChannelsResponse;
import com.kaodian.server.api.billing.dto.PlansResponse;
import com.kaodian.server.api.billing.dto.SubscriptionResponse;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.billing.BillingProperties;
import com.kaodian.server.billing.PaymentOrderStore;
import com.kaodian.server.billing.QuotaService;
import com.kaodian.server.billing.Subscription;
import com.kaodian.server.billing.SubscriptionStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * 档位、通道、订阅三个只读端点({@code M7-额度与订单} §五 / §8.4)。
 *
 * <h2>🔴 {@code GET /billing/plans} 要令牌</h2>
 *
 * {@code 接口契约} §8.1 已裁定。理由<b>不是</b>「未登录的人不该看价格」,是<b>那个界面不存在</b>:
 * 未登录只能看到产品说明与登录门,<b>没有一个未登录的用户走得到定价这一屏</b>
 * ({@code U7.3} §2.4:枚举里没有「未登录」这一态)。
 * 一个匿名端点服务于一个不存在的界面,<b>它唯一的实际用途是给爬价格的人省事</b>。
 * <p>
 * 落法:它<b>不在 {@code ApiAuthFilter.WHITELIST} 那七行里</b>,走默认档 {@code scope=full}。
 * 本类不去动那个常量 —— 白名单归 {@code B0-4},这里只是不出现在里面。
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingCatalogController {

    private final BillingProperties properties;
    private final PaymentOrderStore orders;
    private final SubscriptionStore subscriptions;
    private final QuotaService quotas;
    private final Clock clock;

    public BillingCatalogController(BillingProperties properties, PaymentOrderStore orders,
                                    SubscriptionStore subscriptions, QuotaService quotas, Clock clock) {
        this.properties = properties;
        this.orders = orders;
        this.subscriptions = subscriptions;
        this.quotas = quotas;
        this.clock = clock;
    }

    /** 档位列表。⚠ 数字的真源是 {@code 商业化与额度设计} §一,改额度 = 改一行配置。 */
    @GetMapping("/plans")
    public PlansResponse plans() {
        return PlansResponse.of(properties.getPlans());
    }

    /**
     * 这一端此刻能用哪些通道。
     *
     * <p>⚠️ <b>本轮只按配置里的开通状态给,没有做「按请求头里的端标识取交集」那一半</b>(§5.2)。
     * 理由是<b>没有任何一份文档定义过那个端标识请求头</b> —— 自己发明一个头就是凭空造一条契约。
     * 已回本议题登记,归 stage 3 与端矩阵那一轨一起定。
     * <p>
     * 🔴 <b>确定不做的那一条已经做到了</b>:这个端点不返回任何其它能力位,
     * 也不做「按 {@code userId} 灰度」——那是一个能悄悄改的产品边界。
     */
    @GetMapping("/channels")
    public ChannelsResponse channels() {
        return ChannelsResponse.of(properties.getChannels());
    }

    /**
     * 当前订阅 + 未终结的单。
     *
     * <p>🔴 <b>{@code expiresAt} 只在会员期还没过时才有值</b>:已过期的那个时点不返回 ——
     * 那一行的语义是「会员期到什么时候」,过了就是免费档,而免费档那一行<b>整行不渲染</b>
     * ({@code U7.6} §2.6)。<b>不返回「永久有效」这类字符串。</b>
     *
     * <p>{@code planCode} 走 {@link QuotaService#effectivePlan}(订阅有效就是订阅那一档,
     * 否则是配置里的免费兜底档)—— 与额度懒发放读的是<b>同一个判断</b>,
     * 不在这里再写一遍「什么算有效」。
     */
    @GetMapping("/subscription")
    public SubscriptionResponse subscription(CurrentSession session) {
        long userId = session.userId();
        java.time.Instant now = clock.instant();
        java.time.Instant expiresAt = subscriptions.find(userId)
                .filter(s -> s.isActive(now))
                .map(Subscription::expiresAt)
                .orElse(null);

        String planCode = quotas.effectivePlan(userId)
                .map(BillingProperties.Plan::getCode)
                .orElse(null);

        return SubscriptionResponse.of(planCode, expiresAt, orders.findOpenByUser(userId));
    }
}
