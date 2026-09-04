package com.kaodian.server.auth;

import java.time.Instant;
import java.util.List;

/**
 * 建账号的流水 —— <b>阶段 3 判据的唯一数据源</b>。
 *
 * <h2>为什么不能从 {@code app_user} 数一行 {@code count(*)}</h2>
 *
 * 两个理由,第二个是致命的:
 *
 * <ol>
 *   <li><b>界面上没有「注册」这个动作。</b> 注册即登录(docs/technical/后端系统设计与组件接入.md §1.7),
 *       服务端能看见的只有一次次「登录成功」,而那个数里混着老用户。
 *       所以「建账号」这条分支必须<b>单独打点</b>。</li>
 *   <li>🔴 <b>合并账号会把已经发生过的注册从 {@code app_user} 里抹掉。</b>
 *       两端各建过一个账号、用户走了 {@code merge/confirm} 之后,
 *       从主表数出来的注册数<b>会变少</b> —— 一个只会单调增长的累计指标开始往回走,
 *       而且全程不报错。阶段 3 的判据是「<b>累计</b> 50 个陌生注册」,
 *       它必须记在一本只追加的账上。</li>
 * </ol>
 *
 * <h2>⚪ 「陌生」这两个字数据里没有</h2>
 *
 * 判据的原文是「累计 50 个<b>陌生</b>注册」。数据能回答的只有「累计 50 个注册」——
 * 一个人是不是熟人,库里没有、也不该有这个字段。
 * <p>
 * 所以这一层记下 {@link Entry#channel} 与 {@link Entry#referrer}(从哪个入口来的),
 * 让「熟人 vs 陌生」<b>可以被人工判定</b>,但不假装它已经被自动算出来了。
 * <b>这是一个未决项,不是一个已实现的指标。</b>
 */
public interface SignupLedger {

    /**
     * 记一笔。<b>只追加,不修改,不删除</b> —— 注销与合并都不动它。
     *
     * <p>注销之后那条流水还在,是有意的:阶段 3 问的是「有多少人注册过」,
     * 不是「现在还有多少人在」。后者是留存,是另一个数(而且 08 没有把它写成判据)。
     */
    void record(Entry entry);

    /** 累计注册数。阶段 3 就读这一个数。 */
    int totalCount();

    List<Entry> all();

    /**
     * @param userId   新建的账号
     * @param at       建号时刻
     * @param channel  从哪条通道进来的
     * @param referrer 从哪个入口来的,如渠道码 / 落地页标识。⚪ 空表示不知道 ——
     *                 <b>而「不知道从哪来的」正是判定「陌生」时最需要的那一格</b>
     */
    record Entry(long userId, Instant at, IdentityType channel, String referrer) {

        public Entry {
            if (userId < AppUser.FIRST_USER_ID) {
                throw new IllegalArgumentException("注册流水必须有账号 id,实得:" + userId);
            }
            if (at == null || channel == null) {
                throw new IllegalArgumentException("注册流水必须有时刻与通道");
            }
        }
    }
}
