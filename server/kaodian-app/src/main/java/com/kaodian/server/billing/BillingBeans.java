package com.kaodian.server.billing;

import com.kaodian.server.api.support.IdempotencyGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * 商业化的装配点。<b>谁组装,谁依赖框架</b> —— 与 {@code DomainBeans} 同一条纪律。
 *
 * <p>🔴 <b>不新建第五个 Maven 模块</b>({@code M7-额度与订单} §11.1):四个模块是按<b>真实的包依赖方向</b>切的,
 * 而商业化只被 {@code app} 用、也只用 {@code app} 已有的东西 ——
 * <b>一个只有一个使用者的模块是一层没有人跨过的边界</b>。
 */
@Configuration
@EnableConfigurationProperties(BillingProperties.class)
@EnableScheduling
public class BillingBeans {

    /**
     * 支付网关。默认 {@link DisabledPaymentGateway} —— 🔴 <b>什么都办不了,而且不假装办得了</b>。
     *
     * <p>与鉴权那一侧的默认组合(不发真短信、不校验滑块、微信整个关着)同一条纪律。
     * 接真通道要补的密钥一律 {@code ${ENV_VAR}} 占位、不进仓库(§8.5)。
     */
    @Bean
    @ConditionalOnMissingBean(PaymentGateway.class)
    public PaymentGateway paymentGateway() {
        return new DisabledPaymentGateway();
    }

    /**
     * 「请求键」幂等守卫({@code B0} §7.3)。
     *
     * <p>⚠️ {@code B0} <b>只交了组件,没有交 bean</b>(它的类注释写着「本轮不挂在任何 controller 上」)。
     * 而 {@code M1} / {@code M4} / {@code M5} 也各要一个 —— 四条分支各声明一次的话,
     * stage 3 合起来就是四个重名 bean、启动直接失败。
     * <p>
     * 🔴 所以这里带 {@link ConditionalOnMissingBean}:<b>先声明的那一个赢,重复的静默让位</b>,
     * 合并时不会炸。<b>正解是把它挪回 {@code B0} 的装配点</b>,已回本议题登记给 stage 3。
     */
    @Bean
    @ConditionalOnMissingBean(IdempotencyGuard.class)
    public IdempotencyGuard idempotencyGuard(Clock clock) {
        return new IdempotencyGuard(clock);
    }
}
