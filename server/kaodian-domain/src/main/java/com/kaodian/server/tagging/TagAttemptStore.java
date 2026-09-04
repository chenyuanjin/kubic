package com.kaodian.server.tagging;

import java.time.Instant;
import java.util.List;

/**
 * 「最近一次打标尝试」的存储 —— {@code M2-打标管线与模型接入} §5.1。
 *
 * <h2>🔴 待补队列不是一张新表</h2>
 *
 * 队列 = {@code outcome=UNAVAILABLE} 且还排着下一次的那些行,<b>不建第二个存储</b>。
 * 一个独立的队列表必然与这张表分叉(出队了但 {@code outcome} 没改,或者反过来),
 * 而两份数据的唯一区别就是一个谓词。{@code U2.5} §2.4 的判据是
 * 「用户全程不知道队列存在也能正常用完产品」——<b>一个连自己的存储都不需要的队列最符合它</b>。
 *
 * <h2>唯一性</h2>
 *
 * {@code (userId, recordId)} 唯一:一条记录只有一行「最近一次尝试」。
 * {@link #put} 是覆盖,不是追加 —— 这张表不是一部历史。
 *
 * <h2>🔴 必须原子的写</h2>
 *
 * {@code TagAttempt.outcome} 与 {@code RecordTag} 的落库<b>必须同一次落盘</b>
 * ({@code M2} §10.2)。分两次写会出现「标签写进去了但 {@code outcome} 还停在
 * {@code RUNNING}」—— 那条记录会被队列反复捞起来重认,而它其实已经认成了。
 * 这一条今天由 {@code TaggingService} 的写入顺序保证(先标签后尝试,且中间不抛),
 * 换 JDBC 那天变成一个事务,<b>形状不变</b>。
 */
public interface TagAttemptStore {

    /** 这个用户某条记录的最近一次尝试;没有返回 {@code null}(还没触发过 = {@code TS-00})。 */
    TagAttempt find(long userId, String recordId);

    /**
     * 新增或覆盖一行。
     *
     * <p>🔴 <b>队列满时丢最旧</b>:这个用户排着队的行数超过
     * {@link TagAttempt#QUEUE_CAPACITY} 时,按 {@code updatedAt} 升序丢掉最旧的那些
     * ({@code T-36})。丢的是「稍后再帮你认一次」,<b>记录本身一个字都不动</b>({@code I-1})。
     *
     * @return 落下的那行
     */
    TagAttempt put(TagAttempt attempt);

    /**
     * 待补队列:到点该重试的行,按 {@code nextRetryAt} 升序,最多 {@code limit} 条。
     *
     * <p>🔴 <b>跨用户</b>:自动重试没有「当前用户」——它是一条后台的路,
     * 每一行自己带着 {@code userId}。
     */
    List<TagAttempt> dueForRetry(Instant now, int limit);

    /** 队列长度 —— 顶栏那个计数读的就是它。<b>队列内容不提供端点</b>({@code U2.5} §三)。 */
    int pendingCount(long userId);

    /**
     * 删掉某条记录的那一行 —— {@code DELETE /records/{id}} 的一部分。
     *
     * @return 删掉了几行(0 或 1)
     */
    int deleteByRecord(long userId, String recordId);
}
