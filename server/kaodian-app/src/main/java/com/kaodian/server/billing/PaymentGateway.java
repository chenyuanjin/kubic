package com.kaodian.server.billing;

import java.util.Map;
import java.util.Optional;

/**
 * 支付平台那一侧 —— 我方与外部对手方之间的唯一一道口子。
 *
 * <h2>为什么是接口:今天<b>没有对手方可测</b></h2>
 *
 * {@code M7-额度与订单} §十四 第 3 条:「是否上架 iOS」需人裁定,注册开发者账号之前
 * <b>收据校验那条路没有对手方</b>;微信侧同理,商户号与平台证书都还不存在。
 * 密钥一律 {@code ${ENV_VAR}} 占位、不写默认值、不进仓库(§8.5 / {@code B0} §8.3),
 * 于是<b>默认实现只能是一个「什么都办不了」的实现</b>({@link DisabledPaymentGateway})——
 * 与 {@code kaodian-auth} 的 {@code DisabledWeChatClient} / {@code LoggingSmsSender} 同一条纪律:
 * <b>默认组合零成本、零外部依赖,而且不假装成功</b>。
 *
 * <p>🔴 <b>它不解释 {@code payParams}</b>:调起参数的形状随 {@code channel} 变,
 * 端原样透传给平台 SDK,服务端不解释、端也不解析(§8.2)。
 */
public interface PaymentGateway {

    /**
     * 上游下单,拿回调起参数。
     *
     * @throws PaymentGatewayException 上游下单失败 / 超时 → {@code 502} / {@code 504} {@code SERVER_ERROR}
     */
    Map<String, Object> prepay(PaymentOrder order);

    /**
     * 主动查单(路二 / 路三)。
     *
     * @return 归一后的上游态。查不到 / 读不懂一律 {@link UpstreamState.UpstreamStatus#UNKNOWN} ——
     *         🔴 不猜成功也不猜失败
     * @throws PaymentGatewayException 网络 / 上游错误
     */
    UpstreamState query(PaymentOrder order);

    /**
     * Apple 收据校验(§4.5)。
     *
     * @return {@link UpstreamState.UpstreamStatus#PAID_UPSTREAM} 校验通过;
     *         {@link UpstreamState.UpstreamStatus#UNKNOWN} 收据无效 → {@code 422 RECEIPT_INVALID},
     *         🔴 <b>不是 {@code NOT_PAID}</b> —— 一张读不懂的收据不说明这一笔没付
     * @throws PaymentGatewayException 网络 / 上游错误 → {@code 502} / {@code 504},🔴 <b>根本不调 {@code settle}</b>
     */
    UpstreamState verifyReceipt(PaymentOrder order, String receipt);

    /**
     * 微信回调的验签 + 报文解密(§7.3)。
     *
     * <p>🔴 <b>验签失败 → 直接拒,不进任何业务。</b>返回 {@link Optional#empty()} 即表示验签没过。
     * <p>
     * 🔴 <b>原始报文不落库</b>:只取解析后的交易号与金额,原始报文进结构化日志且不含任何学习内容
     * ({@code 技术架构与接口契约} §5.5.1)。
     */
    Optional<Notification> verifyNotification(Map<String, String> headers, String body);

    /**
     * 回调里我方唯一关心的四个值。
     *
     * <p>🔴 <b>没有第五个字段</b> —— 尤其没有一个装原始报文的 {@code String}。
     *
     * @param outTradeNo    商户订单号
     * @param tradeState    上游原始状态串,交给 {@link UpstreamState#ofWeChatTradeState} 归一
     * @param amountFen     上游报的金额,整数分
     * @param transactionId 上游交易号
     */
    record Notification(String outTradeNo, String tradeState, int amountFen, String transactionId) {
    }

    /** 上游够不着 —— 网络、超时、证书、凭证缺失。调用方一律翻成 {@code 502}/{@code 504 SERVER_ERROR}。 */
    class PaymentGatewayException extends RuntimeException {
        public PaymentGatewayException(String message) {
            super(message);
        }
    }
}
