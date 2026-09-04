package com.kaodian.server.api.billing;

import com.kaodian.server.api.billing.dto.CreateOrderRequest;
import com.kaodian.server.api.billing.dto.CreateOrderResponse;
import com.kaodian.server.api.billing.dto.OrderDetailResponse;
import com.kaodian.server.api.billing.dto.OrderSummaryDto;
import com.kaodian.server.api.billing.dto.ReceiptVerifyRequest;
import com.kaodian.server.api.dto.common.Cursor;
import com.kaodian.server.api.dto.common.ErrorCode;
import com.kaodian.server.api.dto.common.Page;
import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.api.support.IdempotencyGuard;
import com.kaodian.server.billing.Channel;
import com.kaodian.server.billing.OrderService;
import com.kaodian.server.billing.PaymentGateway;
import com.kaodian.server.billing.PaymentOrder;
import com.kaodian.server.billing.PaymentOrderStore;
import com.kaodian.server.billing.PaymentSettleService;
import com.kaodian.server.billing.UpstreamState;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 订单侧五个端点({@code M7-额度与订单} §8.2)。
 *
 * <h2>🔴 三个写端点的幂等保留期是 <b>24 小时</b>(§10.2)</h2>
 *
 * 理由:订单的合理重试窗口是<b>一次支付会话</b>,而 {@code expireAt} 默认 120 分钟 ——
 * 24 小时已经覆盖「断网一夜后回来重放」,<b>再长就是给一个不会发生的场景付存储</b>。
 * <p>
 * 锚定键是 {@code (userId, path, Idempotency-Key)},🔴 <b>不是参数哈希</b> ——
 * 哈希在「重试」与「合法的二次操作」之间分不出来。
 *
 * <p>🔴 只读令牌打 {@code /billing/**} 一律 {@code 403 READONLY_TOKEN},<b>不论方法</b>(锁 4)——
 * 那一道在 {@code ApiAuthFilter} 的前缀黑名单里,进不到这里。
 */
@RestController
@RequestMapping("/api/v1/billing/orders")
public class OrderController {

    /** 🔴 本模块三个写端点的保留期。数与理由见类注释 / {@code M7} §10.2。 */
    private static final Duration RETENTION = Duration.ofHours(24);

    private final OrderService orderService;
    private final PaymentOrderStore orders;
    private final PaymentSettleService settle;
    private final PaymentGateway gateway;
    private final IdempotencyGuard guard;

    public OrderController(OrderService orderService, PaymentOrderStore orders,
                           PaymentSettleService settle, PaymentGateway gateway,
                           IdempotencyGuard guard) {
        this.orderService = orderService;
        this.orders = orders;
        this.settle = settle;
        this.gateway = gateway;
        this.guard = guard;
    }

    /** 下单。🔴 body 不接受金额、不接受订单号,见 {@link CreateOrderRequest}。 */
    @PostMapping
    public CreateOrderResponse create(CurrentSession session,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      @Valid @RequestBody CreateOrderRequest request) {
        return idempotent(session, "/api/v1/billing/orders", key, CreateOrderResponse.class,
                () -> CreateOrderResponse.of(orderService.create(
                        session.userId(), request.planCode(), Channel.ofWireName(request.channel()))));
    }

    /**
     * 订单列表 —— 倒序,游标分页。
     *
     * <p>🔴 <b>不返回 {@code total} / {@code hasMore}</b>({@code U7.6} 逐字要求「不做『加载更多』按钮」)。
     * <b>档位屏区四</b>用同一个端点取 {@code limit=3},不建专用端点。
     */
    @GetMapping
    public Page<OrderSummaryDto> list(CurrentSession session,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        Page<PaymentOrder> page = orders.findByUser(
                session.userId(), Cursor.decode(cursor), Cursor.limit(limit));
        return new Page<>(page.items().stream().map(OrderSummaryDto::of).toList(), page.nextCursor());
    }

    /**
     * 订单详情 —— ⚠ <b>这个 GET 会触发一次上游反查并可能推进订单状态</b>(三条路的路二,§3.1)。
     *
     * <p>它仍然是幂等的:多次调用结果相同,发放本身撞唯一键。
     * <b>不为它另建一个 {@code POST /orders/{no}/query}</b> —— 那会让端多记一条规矩,
     * 而 {@code U7.4} 要的就是「支付后必须主动查一次」。
     *
     * <p>🔴 上游够不着时<b>不让整个详情跟着 502</b>:订单是我方的数据,查得到就该返回。
     * 反查只是顺带推进,推进不了就按当前状态返回 —— 端接着轮询就是了。
     */
    @GetMapping("/{outTradeNo}")
    public OrderDetailResponse detail(CurrentSession session, @PathVariable String outTradeNo) {
        PaymentOrder order = orderService.require(session.userId(), outTradeNo);
        if (!order.state().isTerminal()) {
            try {
                settle.settle(order.outTradeNo(), gateway.query(order));
            } catch (PaymentGateway.PaymentGatewayException ignored) {
                // 够不着就算了,下面按库里的状态返回。补偿任务还会再来一次。
            }
        }
        return OrderDetailResponse.of(orderService.require(session.userId(), outTradeNo));
    }

    /** 主动关单。四档见 {@link OrderService#close}。 */
    @PostMapping("/{outTradeNo}/close")
    public OrderDetailResponse close(CurrentSession session, @PathVariable String outTradeNo,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return idempotent(session, "/api/v1/billing/orders/" + outTradeNo + "/close", key,
                OrderDetailResponse.class,
                () -> OrderDetailResponse.of(orderService.close(session.userId(), outTradeNo)));
    }

    /**
     * Apple 收据校验(§4.5)。
     *
     * <table>
     *   <caption>三档</caption>
     *   <tr><th>档</th><th>HTTP</th><th>{@code code}</th><th>之后</th></tr>
     *   <tr><td>校验通过</td><td>200</td><td>——</td><td>归一为 {@code PAID_UPSTREAM} → 走 {@code settle}</td></tr>
     *   <tr><td>收据无效</td><td>422</td><td>{@code RECEIPT_INVALID}</td>
     *       <td>归一为 {@code UNKNOWN} → <b>订单不动</b>。端只说「没能确认」,🔴 不说「失败」</td></tr>
     *   <tr><td>网络 / 上游错误</td><td>502</td><td>{@code SERVER_ERROR}</td>
     *       <td>🔴 <b>不调 {@code settle}</b>,订单不动。端主按钮 = 再试一次</td></tr>
     * </table>
     *
     * 🔴 {@code 422} 与 {@code 5xx} 必须分得开,而<b>这条要求靠 HTTP 状态分类就满足了</b>
     * ({@code 接口契约} §8.5 原文)。上游错误那一档由
     * {@link BillingExceptionHandler} 翻成 502,这里不 catch。
     *
     * <p><b>未完成事务的接续是静默的</b>({@code U7.5} §2.4):端进屏时把待校验的收据直接打过来,
     * 服务端按 {@code Idempotency-Key} 幂等,<b>没有任何一个「接续」端点</b>。
     */
    @PostMapping("/{outTradeNo}/receipt/verify")
    public OrderDetailResponse verifyReceipt(CurrentSession session, @PathVariable String outTradeNo,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             @Valid @RequestBody ReceiptVerifyRequest request) {
        return idempotent(session, "/api/v1/billing/orders/" + outTradeNo + "/receipt/verify", key,
                OrderDetailResponse.class, () -> {
                    PaymentOrder order = orderService.require(session.userId(), outTradeNo);
                    UpstreamState upstream = gateway.verifyReceipt(order, request.receipt());
                    if (upstream.status() != UpstreamState.UpstreamStatus.PAID_UPSTREAM) {
                        // 🔴 收据无效 → 订单不动,而且不说「失败」。settle 也不必调:
                        //    settle(UNKNOWN) 的动作就是「不动 + 告警」,而这里已经知道原因了。
                        throw new ApiException(ErrorCode.RECEIPT_INVALID,
                                "这张收据没能确认 —— 不是说这一笔没付,再试一次或过一会儿再看。");
                    }
                    settle.settle(order.outTradeNo(), upstream);
                    return OrderDetailResponse.of(orderService.require(session.userId(), outTradeNo));
                });
    }

    /**
     * 三个写端点共用的幂等外壳。
     *
     * <ul>
     *   <li>上次成功 → 返回上次结果,🔴 <b>不再产生第二笔账单</b></li>
     *   <li>上次进行中 → {@code 409 IN_PROGRESS}</li>
     *   <li>上次失败 → 放掉槽位,允许重试({@code 接口契约} §1.5)</li>
     *   <li>没带头 → {@code 400 IDEMPOTENCY_KEY_REQUIRED}(由 {@code IdempotencyGuard} 自己抛)</li>
     * </ul>
     */
    private <T> T idempotent(CurrentSession session, String path, String key,
                             Class<T> type, Supplier<T> action) {
        long userId = session.userId();
        switch (guard.begin(userId, path, key, RETENTION)) {
            case IdempotencyGuard.Replay replay -> {
                return type.cast(replay.result());
            }
            case IdempotencyGuard.InFlight ignored -> throw new ApiException(ErrorCode.IN_PROGRESS,
                    "上一次同样的请求还在处理中,等它有结果再来。");
            case IdempotencyGuard.Fresh ignored -> {
                // 往下真的执行
            }
        }
        try {
            T result = action.get();
            guard.complete(userId, path, key, result);
            return result;
        } catch (RuntimeException e) {
            // 🔴 失败必须放掉槽位,否则重试会一直撞在 InFlight 上,
            //    而「上次失败 → 允许重试」是契约里写着的一档语义。
            guard.fail(userId, path, key);
            throw e;
        }
    }
}
