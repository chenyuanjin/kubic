package com.kaodian.server.billing;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 额度这一侧的编排 —— <b>周期算一次、按有效订阅懒发放、再谈扣减</b>。
 *
 * <h2>🔴 {@code periodYm} 在最外层算一次,一路传下去(§2.5)</h2>
 *
 * 不许在 {@link QuotaStore} 内部再算一次:{@code 00:00:00} 前后各算一次会落到两行上,
 * 而那正好是<b>「扣了两个月各一次」</b>。所以本类是唯一一处调用 {@link #currentPeriod} 的地方,
 * 拿到的串作为参数往下传。
 *
 * <h2>下一个自然月的额度从哪来:首次使用时懒发放(§3.3)</h2>
 *
 * <b>不做月初批量任务。</b>于是 {@code U7.6} §2.6 那一格「会员已过期但当月额度尚未重置」
 * 就是结构事实:{@link QuotaStore#grant} <b>只升不降</b>,这一行的 {@code granted}
 * 是当初发的那个数,<b>不因订阅到期而回落</b>;界面按服务端返回显示,前端不推算。
 */
@Service
public class QuotaService {

    private final QuotaStore quotas;
    private final SubscriptionStore subscriptions;
    private final BillingProperties properties;
    private final Clock clock;

    public QuotaService(QuotaStore quotas, SubscriptionStore subscriptions,
                        BillingProperties properties, Clock clock) {
        this.quotas = quotas;
        this.subscriptions = subscriptions;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 当前自然月标识,如 {@code "2026-09"}。
     *
     * <p>🔴 时区从配置来({@link BillingProperties#getZone()}),<b>这里不写时区字面量</b>。
     * 前端不参与:周期标识由服务端返回,端只显示({@code U7.1} §2.4,已冻结)。
     */
    public String currentPeriod() {
        return YearMonth.from(clock.instant().atZone(properties.getZone())).toString();
    }

    /**
     * 按当前有效订阅把这个周期的两个池子补齐,然后返回它们。
     *
     * <p>{@link QuotaStore#grant} 只升不降,所以对已经发过的行这一步是个空操作 ——
     * 「首次被使用时懒发放」与「续费时按新档位抬档」走的是同一条路。
     */
    public Map<QuotaType, QuotaPeriod> provision(long userId, String periodYm) {
        Optional<BillingProperties.Plan> plan = effectivePlan(userId);
        Map<QuotaType, QuotaPeriod> result = new LinkedHashMap<>();
        for (QuotaType type : QuotaType.values()) {
            int granted = plan.map(p -> p.quotaOf(type)).orElse(0);
            result.put(type, quotas.grant(userId, periodYm, type, granted));
        }
        return result;
    }

    /** 这个人此刻算哪一档:订阅还在有效期内就是订阅那一档,否则是配置里的免费兜底档。 */
    public Optional<BillingProperties.Plan> effectivePlan(long userId) {
        Instant now = clock.instant();
        return subscriptions.find(userId)
                .filter(s -> s.isActive(now))
                .flatMap(s -> properties.plan(s.planCode()))
                .or(properties::defaultPlan);
    }

    /** 预检 —— 🔴 <b>只读不扣减</b>({@code 接口契约} §6.7)。 */
    public QuotaPeriod peek(long userId, String periodYm, QuotaType type) {
        provision(userId, periodYm);
        return quotas.find(userId, periodYm, type)
                .orElse(new QuotaPeriod(userId, periodYm, type, 0, 0));
    }

    /**
     * 扣一次 —— 🔴 <b>只在外部模型调用<u>成功之后</u>调用</b>({@code 接口契约} §6.7.1 的时序)。
     *
     * <p>⚠ 一条要认下来的代价({@code M7} §2.6):外部调用发生在条件更新之前,于是极端并发下
     * 可能出现「<b>外部账单已产生、扣不进去</b>」—— 两端同时在 {@code used == granted - 1} 时
     * 通过预检、各自调了一次模型,第二次扣减撞回滚。<b>这一次的成本我方自己承担</b>,
     * 界面按耗尽处置({@code U7.2} §2.5 第 9 行)。
     * <p>
     * 🔴 <b>不许为了消掉这一格而改成「先扣后调」</b> —— 那会让每一次模型调用失败都变成
     * 一次替自己的故障收费,而那是 {@code 接口契约} §6.7.2 约束 1 明令禁止的。
     */
    public ConsumeResult consume(long userId, String periodYm, AiCallLog call) {
        provision(userId, periodYm);
        return quotas.consume(userId, periodYm, call.quotaType(), call);
    }

    /** 失败调用只留流水、不动 {@code used}。 */
    public void recordFailure(AiCallLog failedCall) {
        quotas.recordFailure(failedCall);
    }
}
