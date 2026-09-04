package com.kaodian.server.api.billing;

import com.kaodian.server.billing.PaymentGateway;
import com.kaodian.server.billing.PaymentSettleService;
import com.kaodian.server.billing.SettleResult;
import com.kaodian.server.billing.UpstreamState;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code POST /api/v1/billing/notify/wxpay} —— 三条路的<b>路一</b>({@code M7-额度与订单} §7.3)。
 *
 * <h2>🔴 它不是匿名,是<u>另一条鉴权链</u></h2>
 *
 * 过滤器在白名单里放行的是<b>应用令牌那一道,不是全部</b>:验签由本 controller 自己做
 * (平台证书验签 + 报文解密)。<b>验签失败 → 直接拒,不进任何业务。</b>
 * 白名单第七行 {@code POST /billing/notify/wxpay} 在那张表上,是为了让
 * 「七行里真正匿名的只有六行」不被误读成「回调漏了」——那一行归 {@code B0-4},本类不去动它。
 *
 * <h2>🔴 响应体不是 {@code ApiError}</h2>
 *
 * 平台要求的是 <code>{"code":"SUCCESS"}</code> / <code>{"code":"FAIL","message":"…"}</code>,
 * 与 {@code 接口契约} §1.3 的错误体<b>是两套</b>。这一处例外必须写进契约(§契约增量 6),
 * 否则下一个人会拿 {@code ApiError} 去回它 —— 而<b>平台会因为读不到期望的字段而一直重推同一条通知</b>,
 * 重推撞唯一键之后表现为「一切正常、日志一条没有」。
 *
 * <h2>🔴 原始报文不落库</h2>
 *
 * 只存解析后的交易号与金额;原始报文进结构化日志且<b>不含任何学习内容</b>
 * ({@code 技术架构与接口契约} §5.5.1)。所以本类既不写文件,也不把 {@code body} 打进日志。
 */
@RestController
@RequestMapping("/api/v1/billing/notify")
public class WxPayNotifyController {

    private static final Logger log = LoggerFactory.getLogger(WxPayNotifyController.class);

    private final PaymentGateway gateway;
    private final PaymentSettleService settle;

    public WxPayNotifyController(PaymentGateway gateway, PaymentSettleService settle) {
        this.gateway = gateway;
        this.settle = settle;
    }

    @PostMapping("/wxpay")
    public ResponseEntity<Map<String, String>> wxpay(HttpServletRequest request,
                                                     @RequestBody(required = false) String body) {
        Optional<PaymentGateway.Notification> verified =
                gateway.verifyNotification(headersOf(request), body == null ? "" : body);

        if (verified.isEmpty()) {
            // 🔴 验签没过 → 直接拒,不进任何业务。日志里不带 body。
            log.warn("微信回调验签未通过,已拒绝");
            return fail("验签未通过");
        }

        PaymentGateway.Notification notification = verified.get();
        SettleResult result = settle.settle(notification.outTradeNo(),
                UpstreamState.ofWeChatTradeState(notification.tradeState(),
                        notification.amountFen(), notification.transactionId()));

        // 金额不符那一档也回 SUCCESS:平台重推解决不了金额对不上,重推只会把日志刷满。
        // 告警已经在 settle 里打过了(SettleResult.AMOUNT_MISMATCH → log.error)。
        log.info("微信回调已处理 outTradeNo={} result={}", notification.outTradeNo(), result);
        return success();
    }

    private static ResponseEntity<Map<String, String>> success() {
        return ResponseEntity.ok(Map.of("code", "SUCCESS"));
    }

    /**
     * 验签没过。
     *
     * <p>🔴 状态取 {@code 400} 而不是 {@code 401},有两条理由:
     * <ul>
     *   <li><b>语义</b>:这个端点根本不走应用令牌(它是另一条鉴权链),{@code 401} 会让人以为
     *       「补一个 Bearer 就能进」;验不过的是<b>报文签名</b>,那是请求本身的问题</li>
     *   <li><b>判据</b>:{@code ApiAuthDefaultDenyTest} 断言白名单那七行「不许被挡成 401」——
     *       回 {@code 401} 会让那条判据判红,而它红得对:白名单里的一行返回 401,
     *       从外面看和「这一行没被放行」一模一样</li>
     * </ul>
     */
    private static ResponseEntity<Map<String, String>> fail(String message) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("code", "FAIL");
        payload.put("message", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(payload);
    }

    /** 验签要的那几个头由网关自己挑 —— 本类不知道是哪几个,那是平台的事。 */
    private static Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
