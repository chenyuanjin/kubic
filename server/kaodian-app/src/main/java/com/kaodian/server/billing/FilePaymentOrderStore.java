package com.kaodian.server.billing;

import com.kaodian.server.api.dto.common.Cursor;
import com.kaodian.server.api.dto.common.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单存储的文件实现({@code B0-1}:store 接口 + 文件 JSON,<b>不写 DDL</b>)。
 *
 * <p>与 {@link FileQuotaStore} 同一形态:一把写锁、改副本、落盘成功之后内存才换上去。
 *
 * <p>ponytail: 翻页是「整表排序后线性扫」(O(n log n))。订单量在这个阶段是个位数到几十,
 * 而 §4.3 明写不设保留期 —— 撑不住的那天是迁库那天,届时换成
 * {@code ORDER BY created_at DESC, out_trade_no DESC} + 索引,本类实现的接口签名不变。
 */
@Component
public class FilePaymentOrderStore implements PaymentOrderStore {

    private static final String FILE_NAME = "billing-orders.json";

    private final BillingJsonFile file;
    private final Object lock = new Object();

    /** {@code null} 表示还没载入。 */
    private Map<String, PaymentOrder> orders;

    @Autowired
    public FilePaymentOrderStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public FilePaymentOrderStore(Path file) {
        this.file = new BillingJsonFile(file);
    }

    public Path dataFile() {
        return file.path();
    }

    @Override
    public Optional<PaymentOrder> findByOutTradeNo(String outTradeNo) {
        synchronized (lock) {
            ensureLoaded();
            return Optional.ofNullable(orders.get(outTradeNo));
        }
    }

    @Override
    public Page<PaymentOrder> findByUser(long userId, Cursor.Position cursor, int limit) {
        synchronized (lock) {
            ensureLoaded();
            List<PaymentOrder> sorted = orders.values().stream()
                    .filter(o -> o.userId() == userId)
                    // 倒序两级:时间越大越靠前;同一毫秒内按订单号降序,与游标的比较逐字一致。
                    .sorted(Comparator.comparing(PaymentOrder::createdAt)
                            .thenComparing(PaymentOrder::outTradeNo).reversed())
                    .toList();

            List<PaymentOrder> after = cursor == null ? sorted : sorted.stream()
                    .filter(o -> isStrictlyAfter(cursor, o))
                    .toList();

            List<PaymentOrder> page = after.stream().limit(limit).toList();
            // 🔴 没有下一页时 nextCursor 传 null —— Page 上的 @JsonInclude(NON_NULL) 会让整个 key 不出现。
            String next = after.size() > limit
                    ? Cursor.encode(page.getLast().createdAt().toEpochMilli(), page.getLast().outTradeNo())
                    : null;
            return new Page<>(page, next);
        }
    }

    /** 严格小于 —— 等于的那条就是上一页的最后一条,再吐一次就是重复。 */
    private static boolean isStrictlyAfter(Cursor.Position cursor, PaymentOrder order) {
        long at = order.createdAt().toEpochMilli();
        if (at != cursor.sortKey()) {
            return at < cursor.sortKey();
        }
        return order.outTradeNo().compareTo(cursor.id()) < 0;
    }

    @Override
    public List<PaymentOrder> findOpenByUser(long userId) {
        synchronized (lock) {
            ensureLoaded();
            return orders.values().stream()
                    .filter(o -> o.userId() == userId && !o.state().isTerminal())
                    .sorted(Comparator.comparing(PaymentOrder::createdAt).reversed())
                    .toList();
        }
    }

    @Override
    public List<PaymentOrder> findStale(Instant before) {
        synchronized (lock) {
            ensureLoaded();
            return orders.values().stream()
                    .filter(o -> !o.state().isTerminal() && o.createdAt().isBefore(before))
                    .sorted(Comparator.comparing(PaymentOrder::createdAt))
                    .toList();
        }
    }

    @Override
    public PaymentOrder save(PaymentOrder order) {
        synchronized (lock) {
            ensureLoaded();
            Map<String, PaymentOrder> next = new LinkedHashMap<>(orders);
            next.put(order.outTradeNo(), order);
            commit(next);
            return order;
        }
    }

    /**
     * 自然键 {@code transactionId} 的唯一性检查({@code M7-额度与订单} §3.3 步 ③)。
     *
     * <h2>🔴 「这一单自己已经认领过同一个交易号」返回 {@code true},不是 {@code false}</h2>
     *
     * 那一档是<b>补偿重试在续自己那次没做完的发放</b>:③ 写成了、⑤ 挂了,
     * {@code grantState=FAILED} 停在 {@code CONFIRMING}。这时候如果 ③ 回 {@code false}
     * 让它跳过发放,这一单就<b>永远停在「收了钱没发货」</b>,而补偿任务每扫一次都跳过一次。
     * <p>
     * 「重复到达不重复发放」那一档由第 ① 步的<b>终态直接返回</b>兜着:发放成功之后订单是
     * {@code PAID},三条路再到达都走不到这里。
     */
    @Override
    public boolean claimTransactionId(String outTradeNo, String transactionId) {
        synchronized (lock) {
            ensureLoaded();
            PaymentOrder self = orders.get(outTradeNo);
            if (self == null) {
                return false;
            }
            for (PaymentOrder other : orders.values()) {
                if (transactionId.equals(other.transactionId())) {
                    // 别人占着 → 真的撞了唯一键;自己占着 → 是自己那次没做完的,放行。
                    return other.outTradeNo().equals(outTradeNo);
                }
            }
            Map<String, PaymentOrder> next = new LinkedHashMap<>(orders);
            next.put(outTradeNo, self.withTransactionId(transactionId));
            commit(next);
            return true;
        }
    }

    // ——————————————————— 落盘 ———————————————————

    private void commit(Map<String, PaymentOrder> next) {
        file.write(toJson(next));
        this.orders = next;
    }

    private void ensureLoaded() {
        if (orders == null) {
            orders = file.read(FilePaymentOrderStore::parse, LinkedHashMap::new);
        }
    }

    /** 🔴 逐字段列举,不用反射映射 —— 理由见 {@link FileQuotaStore}。 */
    private static ObjectNode toJson(Map<String, PaymentOrder> orders) {
        ObjectNode root = BillingJsonFile.newObject();
        ArrayNode array = root.putArray("orders");
        for (PaymentOrder o : orders.values()) {
            ObjectNode n = array.addObject();
            n.put("outTradeNo", o.outTradeNo());
            n.put("userId", o.userId());
            n.put("planCode", o.planCode());
            n.put("productName", o.productName());
            n.put("amountFen", o.amountFen());
            n.put("channel", o.channel().wireName());
            n.put("state", o.state().name());
            n.put("grantState", o.grantState() == null ? null : o.grantState().name());
            n.put("transactionId", o.transactionId());
            n.put("createdAt", o.createdAt().toString());
            n.put("expireAt", o.expireAt().toString());
            n.put("paidAt", o.paidAt() == null ? null : o.paidAt().toString());
            n.put("refundedAt", o.refundedAt() == null ? null : o.refundedAt().toString());
        }
        return root;
    }

    private static Map<String, PaymentOrder> parse(JsonNode root) {
        Map<String, PaymentOrder> orders = new LinkedHashMap<>();
        for (JsonNode n : root.path("orders")) {
            PaymentOrder o = new PaymentOrder(
                    n.get("outTradeNo").asString(),
                    n.get("userId").asLong(),
                    n.get("planCode").asString(),
                    n.get("productName").asString(),
                    n.get("amountFen").asInt(),
                    Channel.ofWireName(n.get("channel").asString()),
                    OrderState.valueOf(n.get("state").asString()),
                    text(n, "grantState") == null ? null : GrantState.valueOf(text(n, "grantState")),
                    text(n, "transactionId"),
                    Instant.parse(n.get("createdAt").asString()),
                    Instant.parse(n.get("expireAt").asString()),
                    instant(text(n, "paidAt")),
                    instant(text(n, "refundedAt")));
            orders.put(o.outTradeNo(), o);
        }
        return orders;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Instant instant(String s) {
        return s == null ? null : Instant.parse(s);
    }

    /** 内存态清零 —— 只给测试用:换一个文件之后要重读,不然读到的是上一份。 */
    void reload() {
        synchronized (lock) {
            orders = null;
        }
    }

    /** 全部订单,只给测试与补偿任务的统计用。 */
    List<PaymentOrder> all() {
        synchronized (lock) {
            ensureLoaded();
            return new ArrayList<>(orders.values());
        }
    }
}
