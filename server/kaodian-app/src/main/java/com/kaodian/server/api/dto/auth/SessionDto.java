package com.kaodian.server.api.dto.auth;

import com.kaodian.server.auth.AccessToken;

import java.time.Instant;

/**
 * 设备管理页(D26)的一行。
 *
 * <h2>为什么 {@code deviceLabel} 上没有 {@code @Size}</h2>
 *
 * 因为这是<b>响应</b>。Bean Validation 只跑在 {@code @Valid} 标住的入参上,
 * 往响应记录上挂一个校验注解,什么都不校验 —— 它唯一的作用是让人以为这里已经收口了。
 * <p>
 * {@code deviceLabel} 的收口点在<b>写入口</b>:三个登录请求体
 * ({@link SmsVerifyRequest}、{@link WeChatLoginRequest}、{@link WeChatPhoneLoginRequest})
 * 上的 {@code @Size(max = }{@link LoginFieldLimits#MAX_DEVICE_LABEL}{@code )}。
 * 全仓库再没有第二条把 {@code deviceLabel} 写进 {@code tokens.json} 的生产路径,
 * 所以这里就是那个数的下游投影,跟 {@code TimelineItemDto#sourceName} 之于
 * {@code CreateRecordRequest#sourceName} 是同一种关系。<b>在下游再写一遍上限,会出现两个数。</b>
 *
 * <p>⚠️ 这一段成立有两个前提,哪个塌了这里就得重新算:
 * ① 那三个 {@code @Size} 一直在;② {@code TokenService#issue} 不出现第二个生产调用方 ——
 * 它是 public 的,谁都能绕过 dto 层直接调。真开了第二个,上限就该下沉到 {@code issue()} 里去。
 *
 * @param tokenHash 吊销这一条时要回传的值。<b>它是哈希,不是令牌</b> ——
 *                  拿着它登不了任何东西,只能用来指认「就是这一条」
 * @param current   是不是当前这台。<b>界面必须标出来</b>,否则用户会把自己踢下线然后以为是 bug
 */
public record SessionDto(
        String tokenHash,
        String deviceLabel,
        Instant issuedAt,
        Instant lastUsedAt,
        Instant expiresAt,
        boolean revoked,
        boolean current
) {

    public static SessionDto from(AccessToken t, String currentHash) {
        return new SessionDto(t.tokenHash(), t.deviceLabel(), t.issuedAt(), t.lastUsedAt(),
                t.expiresAt(), t.isRevoked(), t.tokenHash().equals(currentHash));
    }
}
