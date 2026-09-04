package com.kaodian.server.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 商业化的配置源({@code M7-额度与订单} §8.5)。
 *
 * <h2>🔴 定价是配置,不是编译期枚举</h2>
 *
 * {@code 接口契约} §6.6 已写死「校验依据是服务端 {@code plans} 列表,<b>不是写死在代码里的枚举</b>」。
 * 所以档位在这里是一个 {@code List},加一档 = 改一行配置,前端不改、后端不改。
 * <p>
 * ⚠ <b>数字的真源是 {@code 商业化与额度设计} §一</b>,不是这个类,也不是
 * {@code application.properties} 里那几行 —— 那几行是把真源抄进运行时的地方。
 *
 * <h2>🔴 时区是 {@link ZoneId} 类型,不是一个待解析的字符串</h2>
 *
 * §2.5 的判据里有一条 {@code grep 'ZoneId.of(' …/billing/ 期望 0}:
 * 时区从配置来,<b>代码里不写时区字面量,也不自己解析一次</b>。
 * 绑定成 {@link ZoneId} 类型之后,解析那一步归 Spring 的转换器,这个包里一次都不出现。
 *
 * <p>为什么必须是 {@code Asia/Shanghai} 而不是系统默认:仓库里已有先例 ——
 * {@code FileSmsRateLimiter} 的日限计数按同一个时区切天。<b>再引入第二个时区口径,
 * 就会出现「短信日限已经跨天、额度还没跨月」这种没人能解释的组合</b>。
 */
@ConfigurationProperties(prefix = "kaodian.billing")
public class BillingProperties {

    /** 自然月边界按哪个时区切(§2.5)。 */
    private ZoneId zone;

    /**
     * 没有有效订阅时落在哪一档 —— 🔴 <b>做成配置项而不是代码里的 {@code "free"} 字面量</b>。
     *
     * <p>§5.1 的判据是「{@code "plus"}/{@code "free"} 不出现在 {@code if} 分支里」:
     * 一个写死的免费档 code 就是那种分支的第一行。
     */
    private String defaultPlan;

    private final Order order = new Order();
    private final Settle settle = new Settle();

    /** 档位列表。🔴 顺序即展示顺序,加档只加值。 */
    private List<Plan> plans = new ArrayList<>();

    /** 这一端此刻开着哪些通道。空 = 该端全部不可用 → {@code channels: []}(§5.2)。 */
    private List<Channel> channels = new ArrayList<>();

    public static class Order {
        /**
         * 🔴 <b>一个数字,两个用途</b>:写进订单的 {@code expireAt},同时作为传给支付平台的过期时点。
         *
         * <p>{@code U7.4} §五 缺口 2 问的「以谁为准」,答案是<b>以我方这一个值为准并下发给平台</b>,
         * 而不是两边各有一个。
         */
        private int expireMinutes = 120;

        public int getExpireMinutes() {
            return expireMinutes;
        }

        public void setExpireMinutes(int expireMinutes) {
            this.expireMinutes = expireMinutes;
        }
    }

    public static class Settle {
        /** 定时补偿扫「早于这个秒数仍未终结」的单({@code 后端系统设计与组件接入} §1.10)。 */
        private int scanAfterSeconds = 120;

        public int getScanAfterSeconds() {
            return scanAfterSeconds;
        }

        public void setScanAfterSeconds(int scanAfterSeconds) {
            this.scanAfterSeconds = scanAfterSeconds;
        }
    }

    /**
     * 一个档位。
     *
     * <p>🔴 <b>没有 {@code originalPriceFen} / {@code listPrice},也不许加</b>
     * ({@code 接口契约} §8.2):任何「别端价格」「划线价」字段都不存在。
     */
    public static class Plan {
        private String code;
        private String name;
        private int priceFen;
        private boolean purchasable;

        /** {@code "month"} / {@code "year"}。🔴 <b>配置里没写就整个 key 不出现</b>,不填 {@code null}。 */
        private String billingPeriod;

        /**
         * 🔴 <b>是文字,不是一个布尔 {@code recommended}</b>:布尔会逼端自己编一句文案,
         * 而那句文案<b>要朗读得出来</b>({@code U7.3} §2.5)。
         */
        private String badge;

        /** 键是闭集两值,与 {@code GET /quota} 同一套键名(§8.3)。 */
        private Map<QuotaType, Integer> quota = new LinkedHashMap<>();

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getPriceFen() {
            return priceFen;
        }

        public void setPriceFen(int priceFen) {
            this.priceFen = priceFen;
        }

        public boolean isPurchasable() {
            return purchasable;
        }

        public void setPurchasable(boolean purchasable) {
            this.purchasable = purchasable;
        }

        public String getBillingPeriod() {
            return billingPeriod;
        }

        public void setBillingPeriod(String billingPeriod) {
            this.billingPeriod = billingPeriod;
        }

        public String getBadge() {
            return badge;
        }

        public void setBadge(String badge) {
            this.badge = badge;
        }

        public Map<QuotaType, Integer> getQuota() {
            return quota;
        }

        public void setQuota(Map<QuotaType, Integer> quota) {
            this.quota = quota;
        }

        /** 这一档给这个池子发几次。配置没写 = 0(不是「无限」)。 */
        public int quotaOf(QuotaType type) {
            return quota.getOrDefault(type, 0);
        }
    }

    /**
     * 按 code 找一档。找不到 = 这个 code 不存在 → {@code 422 PLAN_NOT_PURCHASABLE}(§9.2)。
     *
     * <p>用 {@link Objects#equals} 而不是 {@code p.getCode().equals(code)}:配置里漏写一个
     * {@code code} 时,后者抛的是 {@code NullPointerException},而那句话指不出是哪一行配置错了。
     */
    public Optional<Plan> plan(String code) {
        return plans.stream().filter(p -> Objects.equals(p.getCode(), code)).findFirst();
    }

    /** 免费兜底档。配置缺失时返回空 —— 调用方按「没有任何额度」处置,不编一个出来。 */
    public Optional<Plan> defaultPlan() {
        return defaultPlan == null ? Optional.empty() : plan(defaultPlan);
    }

    public ZoneId getZone() {
        return zone;
    }

    public void setZone(ZoneId zone) {
        this.zone = zone;
    }

    public String getDefaultPlan() {
        return defaultPlan;
    }

    public void setDefaultPlan(String defaultPlan) {
        this.defaultPlan = defaultPlan;
    }

    public Order getOrder() {
        return order;
    }

    public Settle getSettle() {
        return settle;
    }

    public List<Plan> getPlans() {
        return plans;
    }

    public void setPlans(List<Plan> plans) {
        this.plans = plans;
    }

    public List<Channel> getChannels() {
        return channels;
    }

    public void setChannels(List<Channel> channels) {
        this.channels = channels;
    }
}
