package com.kaodian.server.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 三条路里的<b>路三</b> —— 定时补偿({@code M7-额度与订单} §3.1,
 * {@code 后端系统设计与组件接入} §1.10)。
 *
 * <p>扫 {@code state ∈ {PENDING, CONFIRMING}} 且早于阈值的单,<b>独立于客户端是否在线</b>。
 * 它拿到的上游态与另外两条路走同一张归一表、同一个 {@link PaymentSettleService#settle} ——
 * <b>不各写一段发放代码</b>。
 *
 * <h2>它盯的是哪一档</h2>
 *
 * 最要紧的一档是 {@code CONFIRMING} + {@code grantState = FAILED}:
 * <b>钱已经在我方,而权益还没到手</b>。这一档没有任何客户端动作能推动它 ——
 * 端看到的是「确认中」,而端能做的只有再查一次,查单那条路撞到同一个失败。
 *
 * <p>ponytail: 固定间隔轮询 + 单实例。多实例会两边同时扫同一批单 —— 重复 {@code settle}
 * 是幂等的(终态直接返回 + 交易号唯一键),所以后果是多打几次上游而不是多发一次货。
 * 升级路径是迁库(B0-1)之后加一把 {@code SELECT … FOR UPDATE SKIP LOCKED}。
 */
@Component
public class SettleCompensationTask {

    private static final Logger log = LoggerFactory.getLogger(SettleCompensationTask.class);

    private final PaymentOrderStore orders;
    private final PaymentSettleService settle;
    private final PaymentGateway gateway;
    private final BillingProperties properties;
    private final Clock clock;

    public SettleCompensationTask(PaymentOrderStore orders, PaymentSettleService settle,
                                  PaymentGateway gateway, BillingProperties properties, Clock clock) {
        this.orders = orders;
        this.settle = settle;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 固定间隔跑一遍。间隔与阈值是同一个配置项
     * ({@code kaodian.billing.settle.scan-after-seconds},默认 120)——
     * 两个数会各自漂,而「扫多久以前的」和「多久扫一次」在这里本来就是同一个量级。
     */
    @Scheduled(fixedDelayString = "${kaodian.billing.settle.scan-after-seconds:120}000")
    public void sweep() {
        List<PaymentOrder> stale = orders.findStale(
                clock.instant().minus(properties.getSettle().getScanAfterSeconds(), ChronoUnit.SECONDS));
        for (PaymentOrder order : stale) {
            try {
                SettleResult result = settle.settle(order.outTradeNo(), gateway.query(order));
                if (result == SettleResult.GRANT_FAILED) {
                    log.error("补偿重试仍未发放成功,钱在我方而权益没到手 outTradeNo={}", order.outTradeNo());
                }
            } catch (PaymentGateway.PaymentGatewayException e) {
                // 上游够不着就下一轮再来 —— 🔴 绝不因为查不到就把订单关掉。
                log.warn("补偿扫单时上游够不着,留到下一轮 outTradeNo={}:{}",
                        order.outTradeNo(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("补偿扫单出错,留到下一轮 outTradeNo={}", order.outTradeNo(), e);
            }
        }
    }
}
