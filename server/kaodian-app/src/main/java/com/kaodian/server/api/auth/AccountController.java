package com.kaodian.server.api.auth;

import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.dto.auth.AccountDto;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.api.dto.auth.DeactivateResponse;
import com.kaodian.server.api.dto.auth.RevokeSessionRequest;
import com.kaodian.server.api.dto.auth.SessionDto;
import com.kaodian.server.api.dto.auth.AccountDto;
import com.kaodian.server.api.dto.auth.DeactivateResponse;
import com.kaodian.server.api.dto.auth.RevokeSessionRequest;
import com.kaodian.server.api.dto.auth.SessionDto;
import com.kaodian.server.auth.AccessToken;
import com.kaodian.server.auth.AccountService;
import com.kaodian.server.auth.AppUser;
import com.kaodian.server.auth.TokenService;
import com.kaodian.server.auth.UserIdentity;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 「我的账号」与设备管理 —— 界面 D11 / D26 / D27。
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accounts;
    private final TokenService tokens;

    public AccountController(AccountService accounts, TokenService tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    /** 我的账号。🔴 不含手机号明文、不含 openid/unionid,见 {@link AccountDto}。 */
    @GetMapping
    public AccountDto me(CurrentSession session) {
        AppUser user = accounts.find(session.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                        "账号不存在。"));
        Instant now = Instant.now();
        int active = (int) tokens.sessionsOf(user.id()).stream()
                .filter(t -> t.isUsableAt(now)).count();
        return new AccountDto(
                // 🔴 JSON 线上仍是字符串(契约 §1.1):变的是里面装什么,"u_3f2a…" → "10001"
                String.valueOf(user.id()), user.nickname(), user.createdAt(),
                accounts.maskedPhoneOf(user.id()).orElse(null),
                accounts.identitiesOf(user.id()).stream()
                        .map(UserIdentity::type)
                        .map(com.kaodian.server.auth.IdentityType::wireName)
                        .toList(),
                active);
    }

    /**
     * 登录设备列表(D26)。
     *
     * <p>D11 上那个「登录设备 3 台 [查看]」点进来就是这里 —— 在这一版之前它<b>点了没地方去</b>。
     */
    @GetMapping("/sessions")
    public List<SessionDto> sessions(CurrentSession session) {
        Instant now = Instant.now();
        String currentHash = session.token().tokenHash();
        return tokens.sessionsOf(session.userId()).stream()
                // 已经吊销或过期的不显示:那一页要回答的是「现在有谁登着」,不是历史。
                .filter(t -> t.isUsableAt(now))
                .map(t -> SessionDto.from(t, currentHash))
                .toList();
    }

    /**
     * 退出某一台(D26)。
     *
     * <h2>🔴 客户端必须先自己拦一次「本机还有未上传的记录」</h2>
     *
     * 离线队列在设备本地。退出登录会连同本地缓存一起清掉 ——
     * <b>「记录动作永不失败」这条线,不能被一次退出登录从背后捅穿</b>(docs/technical/后端系统设计与组件接入.md §1.9)。
     * <p>
     * 服务端看不见别人机器上的队列,所以这里只把客户端的确认写进日志:
     * 真要复盘「用户的记录哪去了」,这一行是唯一的线索。
     */
    @PostMapping("/sessions/revoke")
    public java.util.Map<String, Object> revoke(CurrentSession session,
                                                @Valid @RequestBody RevokeSessionRequest req) {
        session.requireWrite();
        if (!req.confirmedPendingUploads()) {
            log.info("退出设备时客户端未确认离线队列 userId={}", session.userId());
        }
        try {
            return java.util.Map.of("revoked", tokens.revokeByHash(session.userId(), req.tokenHash()));
        } catch (IllegalArgumentException e) {
            // 越权吊销别人的会话是显式失败,不是静默无事发生。
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_SESSION", e.getMessage());
        }
    }

    /**
     * 注销账号(D27)。
     *
     * <p>响应里<b>必须</b>带导出入口提示 —— docs/technical/INDEX.md §6.1 的硬要求({@code 1.3.1.3.3})。
     * <p>
     * ⚪ 服务端数据的硬删时点未定,留给 {@code L-A5} 的律师稿。
     * <b>所以这里也不说任何具体天数</b>,见 {@link DeactivateResponse}。
     */
    @DeleteMapping
    public DeactivateResponse deactivate(CurrentSession session) {
        session.requireWrite();
        int before = tokens.sessionsOf(session.userId()).size();
        accounts.deactivate(session.userId());
        return new DeactivateResponse(before, DeactivateResponse.EXPORT_HINT);
    }

    /**
     * 阶段 3 的那个累计数 —— <b>「累计陌生注册」</b>。
     *
     * <p>⚪ 返回的是「累计注册」。<b>「陌生」两个字数据里没有</b>,也不该有:
     * 一个人是不是熟人,库里没有这个字段。判定要靠 {@code referrer} 由人来做
     * (见 {@code SignupLedger})。
     * <p>
     * 它放在这里而不是某个后台管理页,是因为 总路线图 §六 那条自检:
     * <b>合规与数据两条轨都能产出让人满意的可量化进展,而两者都不需要面对一个真实用户。</b>
     * 这个数是少数几个必须由真人产生的数之一,把它放在随手能看到的地方是有意的。
     */
    @GetMapping("/signup-count")
    public java.util.Map<String, Object> signupCount() {
        return java.util.Map.of(
                "totalSignups", accounts.totalSignups(),
                "note", "这是累计注册数。「陌生」需人工判定,见 SignupLedger.Entry#referrer。");
    }
}
