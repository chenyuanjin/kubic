package com.kaodian.server.auth;

import java.time.Instant;

/**
 * 第②③道闸 —— 频控(docs/后端详设 §1.8)。
 *
 * <h2>它挡的是钱,不是登录</h2>
 *
 * 短信是<b>整条链路上唯一一处「还没有账号的人就能让你花钱」的地方</b>。
 * 所以这两道闸的判据不是「这个人是不是坏人」,而是「这一次调用值不值得花那几分钱」。
 *
 * <h2>🔴 为什么必须有第①道滑块,这两道挡不住</h2>
 *
 * 换一批 IP、换一批号,{@code 单号 1/60s} 与 {@code 单 IP 20/日} 两条<b>都不触发</b>,
 * 而每一条短信都是真金白银。纯计数频控在分布式刷子面前只是一个上限,不是一道闸。
 */
public interface SmsRateLimiter {

    /**
     * 占一个名额。<b>先占再发</b> —— 反过来的话,并发的两个请求会同时通过检查。
     *
     * @param ip 调用方 IP。取不到时传空串 —— 那种情况下这一道闸形同虚设,
     *           而这正是滑块必须在它<b>前面</b>的又一个理由
     */
    Decision reserve(String phoneHmac, String ip, Instant now);

    /**
     * 把日额度还回去 —— <b>只在「确定没发出去」时调用</b>。
     *
     * <p>🔴 只还日额度,<b>不还 60 秒冷却</b>。两者的用途不同:
     * <ul>
     *   <li>日额度是<b>用户的</b>,我们自己的供应商故障不该吃掉它</li>
     *   <li>60 秒冷却是<b>系统的</b>,还回去等于允许客户端对着一个正在故障的接口连打</li>
     * </ul>
     */
    void releaseDaily(String phoneHmac, String ip);

    /** 一次判定的结果。带上准确的恢复时点 —— 「请稍后再试」只惩罚真实用户。 */
    sealed interface Decision {

        record Allowed(int phoneUsedToday, int ipUsedToday) implements Decision {
        }

        /** 单号 1/60s。 */
        record TooFrequent(Instant retryAt) implements Decision {
        }

        /** 单号 10/日。 */
        record PhoneDailyExhausted(Instant resetAt, int limit) implements Decision {
        }

        /** 单 IP 20/日。 */
        record IpDailyExhausted(Instant resetAt, int limit) implements Decision {
        }
    }
}
