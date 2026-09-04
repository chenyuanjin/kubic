package com.kaodian.server.billing;

/**
 * 一次 {@link QuotaStore#consume} 的三种结局。
 *
 * <h2>🔴 为什么是密封接口而不是一个 {@code boolean}</h2>
 *
 * 三种结果在界面上是<b>三句不同的话</b>({@code U7.2} §2.6:耗尽 / 重试不重复扣 / 正常扣一次),
 * 一个 {@code boolean} 表达不了「重放」这一档 —— 而<b>重放被当成成功扣一次的后果,
 * 是一次断网重连把用户的额度扣光</b>({@code M7-额度与订单} §2.3)。
 */
public sealed interface ConsumeResult permits
        ConsumeResult.Consumed, ConsumeResult.Replayed, ConsumeResult.Exhausted {

    /** 正常扣了一次。 */
    record Consumed(int remaining) implements ConsumeResult {
    }

    /**
     * 同一个唯一键上次已经成功过 —— 🔴 <b>不再扣</b>,把上次那一行原样带回去。
     *
     * <p>调用方应当返回上一次的结果,而不是当成一次新的成功。
     */
    record Replayed(AiCallLog previous) implements ConsumeResult {
    }

    /**
     * 条件更新受影响 0 行 —— 这个月这个池子用完了。
     *
     * <p>🔴 <b>不扣、不留流水</b>({@code M7} §2.3 步 ②:整体回滚)。
     */
    record Exhausted(int granted, int used) implements ConsumeResult {
    }
}
