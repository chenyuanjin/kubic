package com.kaodian.server.collect;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 {@link RecordTagStore} 契约实现的内存版 —— 接口测试用。
 *
 * <h2>为什么不用 {@code FileRecordTagStore} 顶上</h2>
 *
 * 与 {@code ApiContractTest.InMemoryTouchStore} 同一个理由:存储实现属于另一条线,
 * 它换成什么都不该影响接口契约(docs/10 §2.2「包之间只通过接口调用」)。
 * 顺带避免接口测试往磁盘上写文件 —— 一个 {@code @WebMvcTest} 不该留下副作用。
 *
 * <h2>🔴 {@code put} 的那道拒绝<b>必须</b>在这里也实现一遍</h2>
 *
 * 「{@code origin} 写入后不可变」是契约的一部分,不是 {@code FileRecordTagStore} 的实现细节。
 * 只让文件版守着的话,接口层的测试会跑在一个<b>比生产更宽松</b>的存储上,
 * 而「接口层有没有试图改 origin」恰恰只有在这一层才看得见。
 */
public final class InMemoryRecordTagStore implements RecordTagStore {

    private final List<RecordTag> tags = new ArrayList<>();

    @Override
    public List<RecordTag> findAll() {
        return List.copyOf(tags);
    }

    @Override
    public List<RecordTag> findByRecord(String recordId) {
        return tags.stream().filter(t -> t.recordId().equals(recordId)).toList();
    }

    @Override
    public RecordTag find(String tagId) {
        if (tagId == null || tagId.isBlank()) {
            return null;
        }
        return tags.stream().filter(t -> t.id().equals(tagId)).findFirst().orElse(null);
    }

    /** 契约见 {@link RecordTagStore#put} —— 三个字段一个都不许变。 */
    @Override
    public RecordTag put(RecordTag tag) {
        RecordTag existing = find(tag.id());
        if (existing != null) {
            if (existing.origin() != tag.origin()) {
                throw new IllegalArgumentException(
                        "标签的 origin 写入后不可变:" + existing.origin().wireName()
                                + " → " + tag.origin().wireName());
            }
            if (!existing.recordId().equals(tag.recordId())) {
                throw new IllegalArgumentException("标签不能换宿主记录");
            }
            if (!existing.nodeCode().equals(tag.nodeCode())) {
                throw new IllegalArgumentException("标签不能原地改挂考点");
            }
            tags.set(tags.indexOf(existing), tag);
            return tag;
        }
        tags.add(tag);
        return tag;
    }

    @Override
    public int deleteByRecord(String recordId) {
        int before = tags.size();
        tags.removeIf(t -> t.recordId().equals(recordId));
        return before - tags.size();
    }

    @Override
    public int count() {
        return tags.size();
    }
}
