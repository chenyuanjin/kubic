package com.kaodian.server.auth.vendor;

/**
 * 第①道闸 —— 行为验证 / 滑块(docs/13 §1.8)。
 *
 * <h2>🔴 它必须在「发短信」之前,不是在「校验验证码」之前</h2>
 *
 * 短信费在<b>发送</b>那一步就花掉了。把滑块摆在校验前,拦住的只是登录,
 * 没拦住花钱 —— 刷子根本不会去校验,他只需要让你一直发。
 * <p>
 * 而这两道计数闸({@code 单号 1/60s}、{@code 单 IP 20/日})挡不住换 IP 换号的分布式刷:
 * 两条都不触发,账单照涨。<b>所以滑块不是「加强」,它是这条链路上唯一真正的闸。</b>
 */
public interface CaptchaVerifier {

    /**
     * 核查前端拿到的票据。
     *
     * @param ticket  前端回调给的 {@code ticket}
     * @param randstr 前端回调给的 {@code randstr}。<b>两个都要,少一个校验必然失败</b>
     * @param userIp  调用方 IP,供应商侧的风控要它
     */
    Verdict verify(String ticket, String randstr, String userIp);

    /** 这个实现是否真的在校验。为 {@code false} 时启动期会打一条 WARN。 */
    boolean isReal();

    /**
     * 判定结果。
     *
     * @param passed 通过了吗
     * @param reason 没通过的原因,<b>只进日志不进响应</b> —— 告诉刷子他哪一步被识破了没有好处
     */
    record Verdict(boolean passed, String reason) {

        public static Verdict pass() {
            return new Verdict(true, null);
        }

        public static Verdict fail(String reason) {
            return new Verdict(false, reason);
        }
    }
}
