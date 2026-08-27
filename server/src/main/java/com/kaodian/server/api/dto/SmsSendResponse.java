package com.kaodian.server.api.dto;

import java.time.Instant;

/**
 * 发送成功。
 *
 * @param expiresAt 这条码什么时候过期。前端拿它做倒计时,<b>而不是自己写死 5 分钟</b> ——
 *                  写死的那一份会在服务端调整时长时静默错开
 * @param devCode   🔴 <b>仅本机开发模式下非空</b>。真实供应商在用时永远是 {@code null}
 */
public record SmsSendResponse(Instant expiresAt, String devCode) {
}
