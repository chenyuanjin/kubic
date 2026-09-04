package com.kaodian.server.billing;

import java.util.Map;
import java.util.Optional;

/**
 * 默认的支付网关 —— 🔴 <b>什么都办不了,而且不假装办得了</b>。
 *
 * <h2>为什么默认是这一个</h2>
 *
 * 商户号、平台证书、Apple 共享密钥今天都不存在({@code M7-额度与订单} §十四 第 3 条:
 * 「是否上架 iOS」还等人裁定),而密钥一律 {@code ${ENV_VAR}} 占位、不进仓库(§8.5)。
 * 与鉴权那一侧的默认组合(不发真短信、不校验滑块、微信整个关着)是同一条纪律:
 * <b>默认零成本、零外部依赖</b>。
 *
 * <p>🔴 <b>关键是它不返回一个假的成功。</b>一个「本地假装付款成功」的默认实现会让
 * {@code POST /billing/orders} 在没有任何对手方的情况下把权益发出去 ——
 * 而那正是「金额校验是最后一道」(§3.3 步 ②)想挡的事情的<b>内部版本</b>。
 *
 * <p>验签返回 {@link Optional#empty()}:没有平台证书就验不了签,而
 * <b>验不了签就必须当成没过</b>(§7.3)——失败方向朝安全那边倒。
 */
public class DisabledPaymentGateway implements PaymentGateway {

    private static final String WHY =
            "支付通道未配置:商户号 / 平台证书 / Apple 共享密钥都走环境变量,仓库里没有默认值(M7 §8.5)。";

    @Override
    public Map<String, Object> prepay(PaymentOrder order) {
        throw new PaymentGatewayException(WHY);
    }

    @Override
    public UpstreamState query(PaymentOrder order) {
        throw new PaymentGatewayException(WHY);
    }

    @Override
    public UpstreamState verifyReceipt(PaymentOrder order, String receipt) {
        throw new PaymentGatewayException(WHY);
    }

    @Override
    public Optional<Notification> verifyNotification(Map<String, String> headers, String body) {
        return Optional.empty();   // 🔴 验不了签 = 没过。不进任何业务。
    }
}
