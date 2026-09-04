package com.kaodian.server.tagging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link TagAttemptStore} 的内存替身 —— 与 {@code InMemoryRecordTagStore} 同一个用法。
 *
 * <p>🔴 <b>队列上限那条逻辑照抄进来</b>,不是「测试里不管它」:
 * 上限是这张表的一条不变量,而不变量只在两个实现都守着的时候才是不变量 ——
 * 内存替身放行 201 条,那条断言在文件实现上过、在这里不过,而两者本该一样。
 */
public class InMemoryTagAttemptStore implements TagAttemptStore {

    private final List<TagAttempt> rows = new ArrayList<>();

    @Override
    public TagAttempt find(long userId, String recordId) {
        if (recordId == null) {
            return null;
        }
        return rows.stream()
                .filter(a -> a.userId() == userId && a.recordId().equals(recordId))
                .findFirst()
                .orElse(null);
    }

    @Override
    public TagAttempt put(TagAttempt attempt) {
        rows.removeIf(a -> a.userId() == attempt.userId() && a.recordId().equals(attempt.recordId()));
        rows.add(attempt);

        List<TagAttempt> queued = rows.stream()
                .filter(a -> a.userId() == attempt.userId() && a.queued())
                .sorted(Comparator.comparing(TagAttempt::updatedAt))
                .toList();
        for (int i = 0; i < queued.size() - TagAttempt.QUEUE_CAPACITY; i++) {
            rows.remove(queued.get(i));
        }
        return attempt;
    }

    @Override
    public List<TagAttempt> dueForRetry(Instant now, int limit) {
        return rows.stream()
                .filter(a -> a.dueAt(now))
                .sorted(Comparator.comparing(TagAttempt::nextRetryAt))
                .limit(Math.max(limit, 0))
                .toList();
    }

    @Override
    public int pendingCount(long userId) {
        return (int) rows.stream().filter(a -> a.userId() == userId && a.queued()).count();
    }

    @Override
    public int deleteByRecord(long userId, String recordId) {
        return rows.removeIf(a -> a.userId() == userId && a.recordId().equals(recordId)) ? 1 : 0;
    }
}
