package com.kaodian.server.api.support;

import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「请求键」幂等守卫 —— HTTP 头 {@code Idempotency-Key}
 * ({@code 接口契约-签名与错误码全集} §1.5 / §3.3,{@code B0-平台底座与横切契约} §7.3)。
 *
 * <h2>🔴 锚定键是 {@code (userId, path, Idempotency-Key)},不是参数哈希</h2>
 *
 * 参数哈希在「重试」与「合法的二次操作」之间<b>分不出来</b> —— 用户真的想再问一次同样的问题,
 * 哈希去重会把第二次静默吞掉:接口回 {@code 200}、返回上一次的答案,而用户以为是新答的。
 * <p>
 * 三列缺一不可:
 * <ul>
 *   <li>少了 {@code userId} —— A 的键会顶掉 B 的,或者任何人猜一个键就能读到别人的结果</li>
 *   <li>少了 {@code path} —— 端上被允许复用 {@code record_event.client_token} 当这个头
 *       (§6.7.2 约束 2),同一条记录先走转写、再走打标建议时两次带着同一个键。
 *       单列唯一会把第二次当成重放:<b>不扣额度,而且返回第一次的转写结果</b> ——
 *       用户拿到一个牛头不对马嘴的答案,账单却真实发生了</li>
 * </ul>
 *
 * <h2>保留期由调用方按端点给,这里不定一个统一天数</h2>
 *
 * 🔴 {@code B0} 刻意不定:下单与注销的合理窗口差一个数量级(§3.3 那张表是 24 小时 / 30 分钟 / 10 分钟),
 * 统一成一个数就是给其中一个定错。所以 {@link #begin} 的最后一个参数是保留期,<b>没有默认值</b>。
 * <b>九个端点各自是多少,以 {@code 接口契约} §3.3 那张表为唯一真源</b> —— 那张映射表不落在这里,
 * 复述一份就是第二真源,而它已经因为「各数各的」错过两次。
 *
 * <h2>用法</h2>
 *
 * <pre>{@code
 * switch (guard.begin(userId, "/api/billing/orders", key, Duration.ofHours(24))) {
 *     case IdempotencyGuard.Replay r -> return (OrderResponse) r.result();   // 不再扣额度、不再产生第二笔账单
 *     case IdempotencyGuard.InFlight ignored -> throw new ApiException(HttpStatus.CONFLICT, "IN_PROGRESS", "...");
 *     case IdempotencyGuard.Fresh ignored -> { }                            // 往下真的执行
 * }
 * }</pre>
 * 失败的那一次要调 {@link #fail} 放掉槽位,否则重试会一直撞在 {@code InFlight} 上 ——
 * §1.5 的「上次失败 → 允许重试」就是靠这一句兑现的。
 *
 * <p>⚠ 本轮<b>不挂在任何 controller 上</b>:{@code POST /billing/orders} 与 {@code POST /ai/ask}
 * 今天还不存在,{@code DELETE /account} 由 {@code M5} 自己挂。{@code B0} 只交组件。
 *
 * <p>ponytail: 单进程内存态 —— {@link ConcurrentHashMap} + 每次 {@link #begin} 一遍 O(n) 过期清扫。
 * 天花板有两条:①<b>多实例或重启即失效</b>,重启后紧接着的一次重放会真的再扣一次额度
 * (与 {@code B0} §2.3「文件存储的全部前提是整个进程一份」同一条前提,{@code B0-1} 裁定本轮不写 DDL);
 * ②进程崩溃留下的 in-flight 记录要等保留期到点才自己消失,那段时间里重试拿到的是 {@code InFlight}。
 * 升级路径:{@code B0-1} 的判据触发迁库时,换成 {@code (user_id, endpoint, idempotency_key)}
 * 三列唯一的表 + 一个过期清理任务,<b>本类三个方法的签名不变</b>,调用方一行不用改。
 */
public final class IdempotencyGuard {

    /** {@link #begin} 的三种结局。 */
    public sealed interface Outcome permits Fresh, Replay, InFlight {
    }

    /** 这个键第一次见(或上一次已过保留期)—— 往下真的执行。 */
    public record Fresh() implements Outcome {
    }

    /**
     * 上一次已经成功过 —— 返回上一次的结果。
     *
     * <p>🔴 <b>不再扣额度、不再产生第二笔账单。</b>
     */
    public record Replay(Object result) implements Outcome {
    }

    /** 上一次还在进行中 —— 调用方回 {@code 409 IN_PROGRESS}。 */
    public record InFlight() implements Outcome {
    }

    private record Key(long userId, String path, String key) {
    }

    private record Entry(Instant expiresAt, boolean done, Object result) {
    }

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public IdempotencyGuard(Clock clock) {
        this.clock = clock;
    }

    /**
     * 认领这一次执行。
     *
     * @param retention 这个端点的保留期,见 {@code 接口契约} §3.3(每个端点各自一个数)
     * @throws ApiException 没带 {@code Idempotency-Key} —— 400 {@code IDEMPOTENCY_KEY_REQUIRED}
     */
    public Outcome begin(long userId, String path, String key, Duration retention) {
        Key anchor = anchor(userId, path, key);
        Instant now = clock.instant();
        entries.values().removeIf(e -> !e.expiresAt().isAfter(now));
        Entry existing = entries.putIfAbsent(anchor, new Entry(now.plus(retention), false, null));
        if (existing == null) {
            return new Fresh();
        }
        return existing.done() ? new Replay(existing.result()) : new InFlight();
    }

    /**
     * 这一次成功了 —— 把结果留给后来的重放。
     *
     * <p>保留期从 {@link #begin} 起算,这里不续期:窗口说的是「同一次重试能持续多久」,
     * 不是「结果能存多久」。
     */
    public void complete(long userId, String path, String key, Object result) {
        entries.computeIfPresent(anchor(userId, path, key),
                (ignored, e) -> new Entry(e.expiresAt(), true, result));
    }

    /**
     * 这一次失败了 —— 放掉槽位,让重试重新是 {@link Fresh}(§1.5「上次失败 → 允许重试」)。
     *
     * <p>{@code B0} §7.3 的签名里只列了 {@code begin} / {@code complete},但只有这两个的话,
     * 一次失败会把这个键<b>永久</b>钉在 {@code InFlight} 上直到保留期到点 ——
     * 而「允许重试」是契约里写着的一档语义,不是可选项。
     */
    public void fail(long userId, String path, String key) {
        entries.computeIfPresent(anchor(userId, path, key), (ignored, e) -> e.done() ? e : null);
    }

    private static Key anchor(long userId, String path, String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "这个端点必须带 Idempotency-Key 请求头。");
        }
        return new Key(userId, Objects.requireNonNull(path, "path"), key);
    }
}
