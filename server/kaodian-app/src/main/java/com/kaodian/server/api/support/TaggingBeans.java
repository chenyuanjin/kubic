package com.kaodian.server.api.support;

import com.kaodian.server.tagging.ModelCallGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 打标那条路在 {@code app} 侧要接的两根线。
 *
 * <h2>🔴 {@code QuotaModelCallGate} 不在这里 —— 它归 {@code M7}</h2>
 *
 * 分工写在 {@code backend/INDEX.md} §3.2:<b>接口落 {@code domain.tagging},
 * 真实实现落 {@code app.billing},由 {@code M7} 写。</b>
 * 本模块在这里放的是一道<b>永远放行</b>的闸,它只在 {@code M7} 那个 bean 还不存在时生效
 * ({@link ConditionalOnMissingBean})——{@code M7} 一落地,这一个自动让位,<b>不用改任何调用点</b>。
 *
 * <p>⚪ 这是一处如实登记的落差,不是「先这样跑起来」:今天没有任何东西在计外部调用,
 * 所以 {@code M2} §2.4 那条恒等式在生产上还没有第二个数可以对。
 * 它在测试里可以对 —— 桩闸计数 vs 桩 {@code VisionTagger} 的调用次数,判据见
 * {@code ModelCallGateTest}。
 */
@Configuration
public class TaggingBeans {

    /**
     * 幂等守卫 —— {@code B0} §7.3 的那一个,本模块只是把它接上,<b>不另写一版</b>。
     *
     * <p>锚定 {@code (userId, path, Idempotency-Key)},🔴 <b>不是参数哈希</b>:
     * {@code suggest} 的请求体是空对象,参数哈希会把「用户真的想再认一次」
     * 和「网络重试」压成同一个值。
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyGuard idempotencyGuard(Clock clock) {
        return new IdempotencyGuard(clock);
    }

    /**
     * 一道永远放行的闸 —— <b>只在 {@code M7} 的实现还不存在时生效</b>。
     *
     * <p>🔴 它<b>不叫</b> {@code QuotaModelCallGate},也不在 {@code api.billing} 包里:
     * 同名同位置的两份实现会让 {@code M7} 落地那天变成一次合并冲突,
     * 而冲突的两边都能编译 —— 那种冲突最后留下来的往往是错的那一份。
     */
    @Bean
    @ConditionalOnMissingBean(ModelCallGate.class)
    public ModelCallGate openModelCallGate() {
        return new ModelCallGate() {
            @Override
            public boolean acquire() {
                return true;
            }

            @Override
            public void release() {
                // 没拿走什么,也就没有什么可退。
            }
        };
    }
}
