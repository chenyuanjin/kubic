package com.kaodian.server.recognize;

/**
 * 识别服务不可用 —— 没配密钥、超时、限流、厂商挂了。
 *
 * <h2>为什么要有一个专门的异常,而不是返回 NO_MATCH</h2>
 *
 * 「模型看了,说不匹配」和「模型压根没看成」对用户是两句不同的话:
 * 前者该提示「自己从树里挑一个」,后者该提示「稍后重试」。
 * 混成一个返回值,界面上就只能说一句含糊的话。
 * <p>
 * docs/technical/INDEX.md §3.1 对 {@link AsrClient} 的注释是「<b>失败抛异常,不返回半成品</b>」——
 * 半成品比失败危险,因为它会被当成真结果用下去。
 *
 * <h2>抛这个异常不等于记录失败</h2>
 *
 * docs/execution/INDEX.md §1.3.7.1:<b>识别服务不可用时,记录动作本身永不失败。</b>
 * 所以这个异常的正确处理方式是在
 * {@link com.kaodian.server.collect.CaptureService} 里被接住 ——
 * 用户已经挑了考点就照样落地,只有「没人挑、也没识别出来」时才拒绝。
 */
public class RecognitionUnavailableException extends RuntimeException {

    public RecognitionUnavailableException(String message) {
        super(message);
    }

    public RecognitionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
