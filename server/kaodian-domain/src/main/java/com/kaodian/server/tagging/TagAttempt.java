package com.kaodian.server.tagging;

import com.kaodian.server.collect.Tenant;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 一条记录「最近一次打标尝试」走到了哪一步 —— {@code M2-打标管线与模型接入} §4.2。
 *
 * <h2>🔴 一个枚举,不是四个布尔</h2>
 *
 * 未分类有六种成因,它们在界面上要说的下一句话完全不同。落成四个布尔字段会让
 * 「同时是无匹配又是拿不到许可」<b>写得出来</b>,而它没有对应的界面;
 * 落成一个枚举则<b>结构上写不出来</b>,而且加第五种成因时编译器会逼所有 switch 补齐。
 *
 * <h2>为什么要落库:成因今天一落地就没了</h2>
 *
 * {@code TaggingService.Outcome} 原本只是一次方法调用的返回值。进程重启之后
 * 「这条为什么没对上」在库里查不出答案,而四张空态各要说一句不同的话。
 *
 * <h2>🔴 {@link Outcome#NOT_RECALLED} 与 {@link Outcome#NO_MATCH} 在库里必须分得开</h2>
 *
 * 界面上这两格一字不差({@code U2.3} §2.5 已裁定不区分),但缺口 {@code C-1}
 * ——「纪律生效」与「召回太窄」在数据上同形 —— 要靠一次抽样人工复核来分,
 * <b>那次复核查的就是这个字段</b>。库里合并它们,{@code C-1} 就从「已知盲点」
 * 变成「永远查不出来」。
 *
 * @param recordId    主键:<b>一条记录只有一行</b>。类型跟着 {@code RecordTag.recordId} 走
 *                    (见 §契约增量 8:{@code B0} 落地时业务实体主键仍是 {@code String},
 *                    只有 {@code userId} 收成了 {@code long})
 * @param userId      {@code B0-3} 租户列:必填、无默认、无哨兵
 * @param outcome     🔴 一个枚举,不是四个布尔
 * @param attempts    已自动重试次数,{@code 0..}{@link #MAX_ATTEMPTS}
 * @param nextRetryAt 下一次该自动重试的时刻;<b>不再自动重试时为 {@code null}</b>
 * @param updatedAt   这一行最后一次被写的时刻。队列满时按它丢最旧
 */
public record TagAttempt(
        String recordId,
        long userId,
        Outcome outcome,
        int attempts,
        Instant nextRetryAt,
        Instant updatedAt
) {

    /** 一次打标尝试的结局。<b>六种未分类成因在这里分得开,在 wire 上才合并</b>。 */
    public enum Outcome {

        /** {@code TS-00} 已落地未触发 —— 离线记的、批量补录的。 */
        PENDING,

        /** {@code TS-01} 已触发,结果还没回。 */
        RUNNING,

        /** 落了候选 → {@code TS-02}。<b>管线的出口是「待确认」,不是「已确认」</b>。 */
        SUGGESTED,

        /** 这个考点已经挂在这条记录上了(可能是之前丢弃过的那条)。 */
        ALREADY_TAGGED,

        /** 成因⑤ 召回为空 —— 界面上与 {@link #NO_MATCH} 一格不差,<b>库里必须分得开</b>。 */
        NOT_RECALLED,

        /** 成因⑥ 有候选但服务端没有可送的素材 —— 今天 {@code /tags/suggest} 的常态({@code M2-G1})。 */
        NO_MATERIAL,

        /** 成因① 认过了,确实对不上(含低于阈值、含出口自检降级)。 */
        NO_MATCH,

        /** 成因② 链路不通 —— 🔴 <b>唯一进自动重试队列的一档</b>。 */
        UNAVAILABLE,

        /** 成因③ 许可拿不到 —— 🔴 <b>不进队列</b>。这是用户侧状态,不是链路故障。 */
        QUOTA_EXHAUSTED,

        /** 成因④ 该科目骨架未建好 —— 不进队列,<b>人工出口也禁用</b>。 */
        SYLLABUS_EMPTY;

        /**
         * 这一档进不进自动重试队列。
         *
         * <p>🔴 <b>只有 {@link #UNAVAILABLE} 一档</b>({@code M2} §5.2)。
         * 其余五档一次都不试:它们的结论不会因为再问一遍而改变,
         * 而 {@link #QUOTA_EXHAUSTED} 重试还会反复撞同一道闸。
         */
        public boolean retryable() {
            return this == UNAVAILABLE;
        }
    }

    /**
     * 自动重试次数上限 —— {@code 接口契约} §4.1 已关 {@code T-5}。
     *
     * <p>到上限停在 {@code TS-06},<b>停止自动重试</b>;手动重试({@code POST …/tags/suggest})
     * 与手动挂载两个出口都留着。
     */
    public static final int MAX_ATTEMPTS = 3;

    /**
     * 退避 —— {@code 30s / 5min / 30min}({@code 接口契约} §4.1 已关 {@code T-4})。
     *
     * <p>长度就是 {@link #MAX_ATTEMPTS}:第 n 次失败之后等第 n 个间隔。
     * 两个数写在一起而不是各写一处,是因为它们改起来必然同时改 ——
     * 分开写,加一次重试而忘了加一个间隔会静默地按最后一个间隔无限退避。
     */
    public static final List<Duration> BACKOFF = List.of(
            Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(30));

    /**
     * 待补队列长度上限 —— <b>200 条,超出丢最旧</b>({@code T-36})。
     *
     * <p>🔴 队列满时<b>记录照样落地</b>({@code I-1}):丢的是「稍后再帮你认一次」这件事,
     * 不是用户记的那一笔。
     */
    public static final int QUEUE_CAPACITY = 200;

    public TagAttempt {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("打标尝试必须挂在一条记录上");
        }
        Tenant.requireUserId(userId);
        if (outcome == null) {
            throw new IllegalArgumentException("打标尝试必须说明它走到了哪一步");
        }
        if (attempts < 0 || attempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "已重试次数必须在 0.." + MAX_ATTEMPTS + " 之间:" + attempts);
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("打标尝试必须有时刻 —— 队列满时按它丢最旧");
        }
        // 🔴 只有 UNAVAILABLE 能排下一次。别的结局带着 nextRetryAt 落库,
        //    队列会把一条已经认完的记录反复捞起来重认,而它其实早就有结论了。
        if (nextRetryAt != null && !outcome.retryable()) {
            throw new IllegalArgumentException(
                    "只有 UNAVAILABLE 进自动重试队列,拿到的是 " + outcome
                            + " —— 见 M2-打标管线与模型接入 §5.2");
        }
    }

    /**
     * 一次已经有结论、不再自动重试的尝试。
     *
     * <p>{@code attempts} 归零:这一行的语义是「最近一次尝试」,而不是一部历史。
     * 上一轮重试过几次,在它有结论的那一刻就不再影响任何判断。
     */
    public static TagAttempt settled(String recordId, long userId, Outcome outcome, Instant now) {
        return new TagAttempt(recordId, userId, outcome, 0, null, now);
    }

    /**
     * 链路不通 —— 排下一次自动重试,或者到上限就停。
     *
     * <p>{@code attempts} 从 {@code previous} 累加。<b>拿 {@code null} 当「第一次」</b>:
     * 第一次失败时库里本来就没有行。
     *
     * @return {@code attempts} 已到 {@link #MAX_ATTEMPTS} 时 {@code nextRetryAt} 为 {@code null}
     *         —— 停在 {@code TS-06},两个人工出口都还在
     */
    public static TagAttempt unavailable(String recordId, long userId, TagAttempt previous, Instant now) {
        int attempts = Math.min(previous == null ? 1 : previous.attempts() + 1, MAX_ATTEMPTS);
        Instant next = attempts >= MAX_ATTEMPTS ? null : now.plus(BACKOFF.get(attempts - 1));
        return new TagAttempt(recordId, userId, Outcome.UNAVAILABLE, attempts, next, now);
    }

    /** 这一行此刻是不是在待补队列里 —— <b>队列就是这个谓词,不是第二个存储</b>({@code M2} §5.1)。 */
    public boolean queued() {
        return nextRetryAt != null;
    }

    /** 到点了没有。 */
    public boolean dueAt(Instant now) {
        return nextRetryAt != null && !nextRetryAt.isAfter(now);
    }
}
