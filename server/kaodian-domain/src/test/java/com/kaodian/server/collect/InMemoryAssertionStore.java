package com.kaodian.server.collect;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 {@link AssertionStore} 契约实现的内存版 —— 接口测试用。
 *
 * <p>为什么不用 {@code FileAssertionStore} 顶上,与 {@link InMemoryRecordTagStore} 同一个理由:
 * 存储实现属于另一条线,而一个 {@code @WebMvcTest} 不该往真实的 {@code ~/.kaodian} 里写文件。
 *
 * <h2>🔴 两个方法的幂等<b>必须</b>在这里也实现一遍</h2>
 *
 * 「重复声明不新增一行、不刷新时刻」和「取消一个没声明过的考点不报错」都是<b>契约</b>
 * ({@link AssertionStore#put} / {@link AssertionStore#remove}),不是文件版的实现细节。
 * 只让文件版守着的话,接口层的测试会跑在一个<b>比生产更宽松</b>的存储上,
 * 而「接口层有没有依赖存储去重」恰恰只有在这一层才看得见。
 */
public final class InMemoryAssertionStore implements AssertionStore {

    private final List<UserAssertion> assertions = new ArrayList<>();

    @Override
    public List<UserAssertion> findAll(long userId) {
        return assertions.stream().filter(a -> a.userId() == userId).toList();
    }

    @Override
    public List<UserAssertion> findAllAcrossUsers() {
        return List.copyOf(assertions);
    }

    @Override
    public UserAssertion find(long userId, String nodeCode) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return null;
        }
        return assertions.stream()
                .filter(a -> a.userId() == userId && a.nodeCode().equals(nodeCode))
                .findFirst()
                .orElse(null);
    }

    /** 契约见 {@link AssertionStore#put} —— 已经声明过就原样返回,<b>不刷新 assertedAt</b>。 */
    @Override
    public UserAssertion put(UserAssertion assertion) {
        UserAssertion existing = find(assertion.userId(), assertion.nodeCode());
        if (existing != null) {
            return existing;
        }
        assertions.add(assertion);
        return assertion;
    }

    /** 契约见 {@link AssertionStore#remove} —— 没有那一行就返回 false,<b>不抛</b>。 */
    @Override
    public boolean remove(long userId, String nodeCode) {
        UserAssertion existing = find(userId, nodeCode);
        if (existing == null) {
            return false;
        }
        assertions.remove(existing);
        return true;
    }

    @Override
    public int count(long userId) {
        return (int) assertions.stream().filter(a -> a.userId() == userId).count();
    }

    /** 测试夹具用:把这张表清空。 */
    public void reset() {
        assertions.clear();
    }
}
