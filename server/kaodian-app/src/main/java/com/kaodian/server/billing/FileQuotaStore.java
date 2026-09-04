package com.kaodian.server.billing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 额度账本的文件实现({@code B0-1}:本轮交付 store 接口 + 文件 JSON 实现,<b>不写 DDL</b>)。
 *
 * <h2>🔴 红线:额度不许为负 —— 由结构保证,不由纪律保证</h2>
 *
 * {@link #consume} 的三步全部在<b>同一把写锁内</b>完成,与 {@code FileTouchStore.append}
 * 的「查 + 写在自己的写锁里完成」同一形态。这把锁就是 {@code M7-额度与订单} §2.6 论证第 2 步里
 * 那句「条件更新在同一行上由存储层串行化」在文件态下的落点(JDBC 态是行锁)。
 * <p>
 * 于是每一次成功的第 ② 步都能看到之前所有成功的 ②,{@code used} 严格递增 1,
 * 且执行前满足 {@code used < granted} ⇒ 执行后满足 {@code used <= granted}。
 * 第 {@code granted+1} 次到达时前置条件不成立 → <b>整体回滚 → {@code Exhausted},
 * 且不产生第 {@code granted+1} 行流水</b>。
 *
 * <h2>提交点是那一次落盘,不是那几行赋值</h2>
 *
 * 三步改的是<b>副本</b>,落盘成功之后才把副本换上去。落盘抛异常时内存一格不动 ——
 * 否则会出现「内存说扣了、盘上没扣」,而重启之后那一次扣减凭空消失。
 *
 * <p>ponytail: 每次写复制一遍两张表(O(n))+ 全量重写。在几百行量级完全够用;
 * 撑不住的那天正是 {@code B0-1} 那两个判据触发迁库的那天,届时换成
 * {@code (user_id, period_ym, quota_type)} 与 {@code (user_id, endpoint, idempotency_key)}
 * 两个唯一索引 + 一条带 {@code AND used < granted} 的 UPDATE,<b>本类实现的接口签名不变</b>。
 */
@Component
public class FileQuotaStore implements QuotaStore {

    private static final String FILE_NAME = "billing-quota.json";

    private final BillingJsonFile file;
    private final Object lock = new Object();

    /** {@code null} 表示还没载入。载入推迟到第一次访问。 */
    private Map<PeriodKey, QuotaPeriod> periods;
    private Map<AiCallLog.Key, AiCallLog> calls;
    private long nextCallId;

    private record PeriodKey(long userId, String periodYm, QuotaType quotaType) {
    }

    @Autowired
    public FileQuotaStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public FileQuotaStore(Path file) {
        this.file = new BillingJsonFile(file);
    }

    /** 数据文件的位置。「我的额度账本存在哪」指着它。 */
    public Path dataFile() {
        return file.path();
    }

    @Override
    public Optional<QuotaPeriod> find(long userId, String periodYm, QuotaType type) {
        synchronized (lock) {
            ensureLoaded();
            return Optional.ofNullable(periods.get(new PeriodKey(userId, periodYm, type)));
        }
    }

    @Override
    public QuotaPeriod grant(long userId, String periodYm, QuotaType type, int granted) {
        if (granted < 0) {
            throw new IllegalArgumentException("发放数不能为负:" + granted);
        }
        synchronized (lock) {
            ensureLoaded();
            PeriodKey key = new PeriodKey(userId, periodYm, type);
            QuotaPeriod existing = periods.get(key);
            // 🔴 只升不降,used 一格不动。granted 下调是退款写法 A 的动作,而 A/B/C 未选(§6.2)。
            QuotaPeriod updated = existing == null
                    ? new QuotaPeriod(userId, periodYm, type, granted, 0)
                    : new QuotaPeriod(userId, periodYm, type,
                            Math.max(existing.granted(), granted), existing.used());
            if (updated.equals(existing)) {
                return existing;
            }
            Map<PeriodKey, QuotaPeriod> nextPeriods = new LinkedHashMap<>(periods);
            nextPeriods.put(key, updated);
            commit(nextPeriods, calls, nextCallId);
            return updated;
        }
    }

    @Override
    public ConsumeResult consume(long userId, String periodYm, QuotaType type, AiCallLog call) {
        synchronized (lock) {
            ensureLoaded();

            // ① 唯一键 (userId, endpoint, idempotencyKey)。
            AiCallLog previous = calls.get(call.key());
            if (previous != null && previous.status() == CallStatus.SUCCESS) {
                return new ConsumeResult.Replayed(previous);   // 整体回滚:什么都没写
            }

            // ② used = used + 1 且带条件 used < granted。
            //    🔴 这一行就是「条件更新」——不满足就整体回滚,不扣、不留流水。
            PeriodKey key = new PeriodKey(userId, periodYm, type);
            QuotaPeriod period = periods.get(key);
            if (period == null) {
                return new ConsumeResult.Exhausted(0, 0);      // 还没发放过 = 一格都没有
            }
            if (!period.hasRemaining()) {
                return new ConsumeResult.Exhausted(period.granted(), period.used());
            }

            QuotaPeriod after = new QuotaPeriod(
                    userId, periodYm, type, period.granted(), period.used() + 1);

            Map<PeriodKey, QuotaPeriod> nextPeriods = new LinkedHashMap<>(periods);
            nextPeriods.put(key, after);
            Map<AiCallLog.Key, AiCallLog> nextCalls = new LinkedHashMap<>(calls);
            // 撞唯一键且旧行 FAILED → 就地覆盖那一行(`接口契约` §1.5:上次失败允许重试),
            // 沿用旧 id 免得一次重试在账上凭空多一个号。
            long id = previous == null ? nextCallId : previous.id();
            nextCalls.put(call.key(), call.withId(id));

            // ③ 提交。落盘成功之后内存才换上去,见类注释。
            commit(nextPeriods, nextCalls, previous == null ? nextCallId + 1 : nextCallId);
            return new ConsumeResult.Consumed(after.remaining());
        }
    }

    @Override
    public void recordFailure(AiCallLog failedCall) {
        if (failedCall.status() != CallStatus.FAILED) {
            throw new IllegalArgumentException(
                    "recordFailure 只收 FAILED 的流水 —— 成功的那次必须走 consume,否则就是一次不扣额度的扣减。");
        }
        synchronized (lock) {
            ensureLoaded();
            AiCallLog previous = calls.get(failedCall.key());
            if (previous != null && previous.status() == CallStatus.SUCCESS) {
                return;   // 🔴 已经成功过的一行不许被一次失败盖掉,那会让扣掉的那一次消失
            }
            Map<AiCallLog.Key, AiCallLog> nextCalls = new LinkedHashMap<>(calls);
            long id = previous == null ? nextCallId : previous.id();
            nextCalls.put(failedCall.key(), failedCall.withId(id));
            commit(periods, nextCalls, previous == null ? nextCallId + 1 : nextCallId);
        }
    }

    @Override
    public long countCallsByUser(long userId) {
        synchronized (lock) {
            ensureLoaded();
            return calls.values().stream().filter(c -> c.userId() == userId).count();
        }
    }

    // ——————————————————— 落盘 ———————————————————

    /** 🔴 落盘是提交点:盘上写成了,内存才换上去。 */
    private void commit(Map<PeriodKey, QuotaPeriod> nextPeriods,
                        Map<AiCallLog.Key, AiCallLog> nextCalls, long nextId) {
        file.write(toJson(nextPeriods, nextCalls, nextId));
        this.periods = nextPeriods;
        this.calls = nextCalls;
        this.nextCallId = nextId;
    }

    private void ensureLoaded() {
        if (periods != null) {
            return;
        }
        Loaded loaded = file.read(FileQuotaStore::parse,
                () -> new Loaded(new LinkedHashMap<>(), new LinkedHashMap<>(), 1L));
        this.periods = loaded.periods();
        this.calls = loaded.calls();
        this.nextCallId = loaded.nextId();
    }

    private record Loaded(Map<PeriodKey, QuotaPeriod> periods,
                          Map<AiCallLog.Key, AiCallLog> calls, long nextId) {
    }

    /**
     * 🔴 逐字段列举,不用反射映射。
     *
     * <p>反射映射意味着「往 record 上加一个字段」就会静默地多一个落盘的键,
     * 而红线 4(库里不留能装题干的字段)靠的正是「只写列出来的这几个」。
     */
    private static ObjectNode toJson(Map<PeriodKey, QuotaPeriod> periods,
                                     Map<AiCallLog.Key, AiCallLog> calls, long nextId) {
        ObjectNode root = BillingJsonFile.newObject();
        ArrayNode periodArray = root.putArray("periods");
        for (QuotaPeriod p : periods.values()) {
            ObjectNode n = periodArray.addObject();
            n.put("userId", p.userId());
            n.put("periodYm", p.periodYm());
            n.put("quotaType", p.quotaType().wireName());
            n.put("granted", p.granted());
            n.put("used", p.used());
        }
        ArrayNode callArray = root.putArray("calls");
        for (AiCallLog c : calls.values()) {
            ObjectNode n = callArray.addObject();
            n.put("id", c.id());
            n.put("userId", c.userId());
            n.put("quotaType", c.quotaType().wireName());
            n.put("endpoint", c.endpoint());
            n.put("idempotencyKey", c.idempotencyKey());
            n.put("provider", c.provider());
            n.put("model", c.model());
            n.put("status", c.status().name());
            n.put("latencyMs", c.latencyMs());
            n.put("costMicro", c.costMicro());
            n.put("createdAt", c.createdAt().toString());
        }
        root.put("nextId", nextId);
        return root;
    }

    private static Loaded parse(JsonNode root) {
        Map<PeriodKey, QuotaPeriod> periods = new LinkedHashMap<>();
        for (JsonNode n : root.path("periods")) {
            QuotaPeriod p = new QuotaPeriod(
                    n.get("userId").asLong(),
                    n.get("periodYm").asString(),
                    QuotaType.ofWireName(n.get("quotaType").asString()),
                    n.get("granted").asInt(),
                    n.get("used").asInt());
            if (p.used() > p.granted()) {
                // 认不出来就吵着失败:这一行违反了不变式,继续跑等于把「余量为负」当成正常数据。
                throw new IllegalStateException(
                        "额度账本里出现 used > granted:" + p.userId() + " " + p.periodYm()
                                + " " + p.quotaType().wireName() + " " + p.used() + ">" + p.granted());
            }
            periods.put(new PeriodKey(p.userId(), p.periodYm(), p.quotaType()), p);
        }
        Map<AiCallLog.Key, AiCallLog> calls = new LinkedHashMap<>();
        for (JsonNode n : root.path("calls")) {
            AiCallLog c = new AiCallLog(
                    n.get("id").asLong(),
                    n.get("userId").asLong(),
                    QuotaType.ofWireName(n.get("quotaType").asString()),
                    n.get("endpoint").asString(),
                    n.get("idempotencyKey").asString(),
                    n.get("provider").asString(),
                    n.get("model").asString(),
                    CallStatus.valueOf(n.get("status").asString()),
                    n.get("latencyMs").asInt(),
                    n.get("costMicro").asLong(),
                    Instant.parse(n.get("createdAt").asString()));
            calls.put(c.key(), c);
        }
        long nextId = root.path("nextId").asLong(1L);
        return new Loaded(periods, calls, nextId);
    }
}
