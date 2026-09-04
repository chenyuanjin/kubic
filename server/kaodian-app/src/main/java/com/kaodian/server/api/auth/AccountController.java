package com.kaodian.server.api.auth;

import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.dto.auth.AccountDto;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.api.dto.auth.DeactivateResponse;
import com.kaodian.server.auth.AccountService;
import com.kaodian.server.auth.AppUser;
import com.kaodian.server.auth.TokenService;
import com.kaodian.server.auth.UserIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 「我的账号」—— 界面 D11 / D27。
 *
 * <p>⚠️ <b>登录设备列表已经不在这里了</b>:{@code GET /account/sessions} 迁到
 * {@code GET /api/v1/tokens}({@link TokenController}),契约 §7.4 的路径。
 * 理由见 {@code M5-账号与登录通道} §9.7 裁定 1 —— 把设备列表留在 {@code /account} 下,
 * 「只读令牌不能管理令牌」这条锁就要写两处路径。
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

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
                Long.toString(user.id()), user.createdAt(),
                accounts.maskedPhoneOf(user.id()).orElse(null),
                accounts.identitiesOf(user.id()).stream()
                        .map(UserIdentity::type)
                        .map(com.kaodian.server.auth.IdentityType::wireName)
                        .toList(),
                active);
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
