package com.kaodian.server.billing;

import java.time.Instant;

/**
 * 一次外部模型调用的流水({@code M7-额度与订单} §2.2)。
 *
 * <h2>🔴 唯一键是三列 {@code (userId, endpoint, idempotencyKey)},不是单列</h2>
 *
 * {@code 接口契约} §6.7.2 约束 2 明写「客户端可复用 {@code record_event.client_token}」:
 * 同一条记录先走 {@code POST /records/{id}/audio}(语音转写)、再走
 * {@code POST /records/{id}/tags/suggest}(闭集分类)时,<b>两次外部调用带着同一个键</b>。
 * <p>
 * 单列唯一会把第二次当成重放 —— 🔴 <b>不扣额度,而且返回第一次的转写结果</b>:
 * 用户拿到一个牛头不对马嘴的答案,而账单真实发生了。三列则是两个 {@code endpoint} 两行,
 * 各扣一次、各返回各自的结果({@code M7} §2.7)。
 *
 * <p>这三列与 {@code B0} §7.3 {@code IdempotencyGuard} 的锚定键
 * {@code (userId, path, Idempotency-Key)} 是<b>同一个概念,不是第二套</b>:
 * 守卫是第一道(命中就返回上次结果,请求根本不进业务),这里的唯一键是兜底
 * (守卫失效或并发穿透时撞唯一键)。两道并存不冲突。
 *
 * <p>🔴 <b>这个 record 里没有 prompt、没有答案、没有图片。</b>{@code costMicro} 是记账不是内容
 * ({@code 技术架构与接口契约} §5.5.2)——{@code NoStemFieldTest} 与 {@code ImageRetentionTest}
 * 扫到它应当零命中,<b>不需要为它加例外</b>。
 *
 * @param id             流水号
 * @param userId         谁
 * @param quotaType      扣的是哪个池子
 * @param endpoint       扣减发生在哪个端点内部 —— <b>唯一键的第二列</b>,见类注释
 * @param idempotencyKey 唯一键的第三列
 * @param provider       供应商标识,记账用
 * @param model          模型标识,记账用
 * @param status         这一次成没成。{@code FAILED} 只留流水、不动 {@code used}
 * @param latencyMs      耗时
 * @param costMicro      整数微元。🔴 <b>记账,不是内容</b>
 * @param createdAt      落流水的时刻
 */
public record AiCallLog(
        long id,
        long userId,
        QuotaType quotaType,
        String endpoint,
        String idempotencyKey,
        String provider,
        String model,
        CallStatus status,
        int latencyMs,
        long costMicro,
        Instant createdAt) {

    /** 唯一键那三列。存储层拿它当 key,不自己再拼一次字符串。 */
    public record Key(long userId, String endpoint, String idempotencyKey) {
    }

    public Key key() {
        return new Key(userId, endpoint, idempotencyKey);
    }

    public AiCallLog withId(long newId) {
        return new AiCallLog(newId, userId, quotaType, endpoint, idempotencyKey,
                provider, model, status, latencyMs, costMicro, createdAt);
    }
}
