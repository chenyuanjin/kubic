package com.kaodian.server.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

/**
 * 退出某一台设备(D26)。
 *
 * @param tokenHash 从 {@link SessionDto} 里拿到的那个哈希。<b>不是令牌明文</b> ——
 *                  服务端手里本来就只有别的设备的哈希,拿不到它们的明文
 * @param confirmedPendingUploads 客户端是否已经确认过「本机还有未上传的记录」。
 *                  🔴 退出登录会连同本地缓存一起清掉,而<b>「记录动作永不失败」这条线
 *                  不能被一次退出登录从背后捅穿</b>(docs/13 §1.9)。
 *                  所以这个确认由客户端做,服务端只负责把它写进日志 ——
 *                  服务端看不见别人机器上的离线队列
 */
public record RevokeSessionRequest(
        @NotBlank(message = "缺少会话标识") String tokenHash,
        boolean confirmedPendingUploads
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
