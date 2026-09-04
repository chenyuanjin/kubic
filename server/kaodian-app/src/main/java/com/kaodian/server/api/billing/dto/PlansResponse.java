package com.kaodian.server.api.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kaodian.server.billing.BillingProperties;
import com.kaodian.server.billing.QuotaType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/v1/billing/plans} 的响应({@code M7-额度与订单} §5.1 / §8.4)。
 *
 * <p>⚠ 数字的真源是 {@code 商业化与额度设计} §一,不是这里 —— 本类只定形状。
 *
 * @param autoRenew 🔴 <b>恒 {@code false},落成常量不是配置项</b>:配置项意味着有人能改它,
 *                  而<b>改它需要的平台资质根本不存在</b>({@code 商业化与额度设计} §6.3)
 * @param plans     数组 —— 档位数会变(加档只加值),前端不改
 */
public record PlansResponse(boolean autoRenew, List<PlanDto> plans) {

    /** 🔴 全仓唯一一处给 {@code autoRenew} 赋值的地方,右边是字面 {@code false}(§5.4 判据)。 */
    public static final boolean AUTO_RENEW = false;

    public static PlansResponse of(List<BillingProperties.Plan> plans) {
        return new PlansResponse(AUTO_RENEW, plans.stream().map(PlanDto::of).toList());
    }

    /**
     * 一个档位。
     *
     * <p>🔴 <b>{@code originalPriceFen} 之类的锚点字段与任何「别端价格」字段不存在,也不许加</b>
     * ({@code 接口契约} §8.2)。
     *
     * @param billingPeriod {@code "month"} / {@code "year"}。🔴 <b>配置里没写就整个 key 不出现</b>,
     *                      不填 {@code null} —— {@code @JsonInclude(NON_NULL)} 是执行装置
     * @param badge         🔴 <b>是文字,不是一个布尔 {@code recommended}</b>:布尔会逼端自己编一句文案,
     *                      而那句文案<b>要朗读得出来</b>({@code U7.3} §2.5)
     * @param quota         🔴 键是闭集两值的<b>对象</b>,与 {@code GET /quota} 同一套键名
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanDto(
            String code,
            String name,
            int priceFen,
            String billingPeriod,
            Map<String, Integer> quota,
            boolean purchasable,
            String badge) {

        static PlanDto of(BillingProperties.Plan plan) {
            Map<String, Integer> quota = new LinkedHashMap<>();
            for (QuotaType type : QuotaType.values()) {
                quota.put(type.wireName(), plan.quotaOf(type));
            }
            return new PlanDto(plan.getCode(), plan.getName(), plan.getPriceFen(),
                    blankToNull(plan.getBillingPeriod()), quota,
                    plan.isPurchasable(), blankToNull(plan.getBadge()));
        }

        /** 配置里写成空串与压根没写是同一件事:这个 key 不出现。 */
        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }
}
