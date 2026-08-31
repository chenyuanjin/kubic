package com.kaodian.server.api.auth;

import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.dto.auth.BindPhoneRequest;
import com.kaodian.server.api.dto.auth.BindResponse;
import com.kaodian.server.api.dto.auth.BindWeChatRequest;
import com.kaodian.server.api.support.ClientIp;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.api.dto.auth.LoginResponse;
import com.kaodian.server.api.dto.auth.MergePreviewResponse;
import com.kaodian.server.api.dto.auth.MergeRequest;
import com.kaodian.server.api.dto.auth.RefreshResponse;
import com.kaodian.server.api.dto.auth.SmsSendRequest;
import com.kaodian.server.api.dto.auth.SmsSendResponse;
import com.kaodian.server.api.dto.auth.SmsVerifyRequest;
import com.kaodian.server.api.dto.auth.WeChatAuthorizeUrlResponse;
import com.kaodian.server.api.dto.auth.WeChatLoginRequest;
import com.kaodian.server.api.dto.auth.WeChatPhoneLoginRequest;
import com.kaodian.server.api.dto.auth.BindPhoneRequest;
import com.kaodian.server.api.dto.auth.BindResponse;
import com.kaodian.server.api.dto.auth.BindWeChatRequest;
import com.kaodian.server.api.dto.auth.LoginResponse;
import com.kaodian.server.api.dto.auth.MergePreviewResponse;
import com.kaodian.server.api.dto.auth.MergeRequest;
import com.kaodian.server.api.dto.auth.RefreshResponse;
import com.kaodian.server.api.dto.auth.SmsSendRequest;
import com.kaodian.server.api.dto.auth.SmsSendResponse;
import com.kaodian.server.api.dto.auth.SmsVerifyRequest;
import com.kaodian.server.api.dto.auth.WeChatAuthorizeUrlResponse;
import com.kaodian.server.api.dto.auth.WeChatLoginRequest;
import com.kaodian.server.api.dto.auth.WeChatPhoneLoginRequest;
import com.kaodian.server.auth.AccountMergeLog;
import com.kaodian.server.auth.AccountService;
import com.kaodian.server.auth.IdentityType;
import com.kaodian.server.auth.OneTimeStateStore;
import com.kaodian.server.auth.SmsRateLimiter;
import com.kaodian.server.auth.SmsCodeService;
import com.kaodian.server.auth.SmsPurpose;
import com.kaodian.server.auth.TokenService;
import com.kaodian.server.auth.vendor.WeChatClient;
import com.kaodian.server.auth.vendor.WeChatEntry;
import com.kaodian.server.auth.vendor.WeChatException;
import com.kaodian.server.auth.vendor.WeChatIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 鉴权端点 —— docs/技术架构 §6.1 那张表的落地。
 *
 * <h2>这里没有 {@code /auth/register}</h2>
 *
 * 契约里没有,以后也不会有。{@link #verify} 通过的那一刻,号码没见过就建号、见过就登进去
 * (docs/后端详设 §1.7)。
 *
 * <h2>控制器不含规则</h2>
 *
 * docs/后端详设 §二:{@code api} 包只做「收参数、翻 DTO、出错误码」。
 * 四道闸的顺序在 {@link SmsCodeService},建号与合并在 {@link AccountService} ——
 * <b>controller 可以再写一个,service 只有这一个。</b>
 * 这里唯一的实质工作是把 sealed 的结果类型翻成错误码与人话,
 * 而那件事本身就是契约的一部分:四种终态要给四句不同的话。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final SmsCodeService sms;
    private final AccountService accounts;
    private final TokenService tokens;
    private final WeChatClient wechat;
    private final OneTimeStateStore states;
    private final ClientIp clientIp;

    /**
     * 微信一步登录那条路要用它做频控。
     *
     * <p>它叫 {@code SmsRateLimiter},但管的其实是<b>「按次外部账单」这一类动作</b> ——
     * 短信 0.03 元一条、微信换手机号 0.03 元一次,两者是同一类东西
     * (`商业化设计` §3.2:不进额度,由频控与滑块管住)。键上加前缀区分,计数空间各自独立。
     */
    private final SmsRateLimiter limiter;

    private final ZoneId zone;

    public AuthController(SmsCodeService sms, AccountService accounts, TokenService tokens,
                          WeChatClient wechat, OneTimeStateStore states, ClientIp clientIp,
                          SmsRateLimiter limiter,
                          @Value("${kaodian.auth.sms.zone:Asia/Shanghai}") String zone) {
        this.sms = sms;
        this.accounts = accounts;
        this.tokens = tokens;
        this.wechat = wechat;
        this.states = states;
        this.clientIp = clientIp;
        this.limiter = limiter;
        this.zone = ZoneId.of(zone);
    }

    // —— 手机号通道(阶段 2)——

    /** 发验证码。四道闸的顺序见 {@link SmsCodeService#send}。 */
    @PostMapping("/sms/send")
    public SmsSendResponse send(@Valid @RequestBody SmsSendRequest req, HttpServletRequest http) {
        SmsPurpose purpose = parsePurpose(req.purpose());
        SmsCodeService.SendOutcome outcome = sms.send(
                req.phone(), purpose, req.captchaTicket(), req.captchaRandstr(), clientIp.of(http));

        return switch (outcome) {
            case SmsCodeService.SendOutcome.Sent s -> new SmsSendResponse(s.expiresAt(), s.devCode());

            case SmsCodeService.SendOutcome.BadPhone b -> throw new ApiException(
                    HttpStatus.BAD_REQUEST, "BAD_PHONE", b.detail());

            case SmsCodeService.SendOutcome.CaptchaFailed ignored -> throw new ApiException(
                    HttpStatus.BAD_REQUEST, "CAPTCHA_FAILED", "请先完成滑动验证。");

            // 429 而不是 400:这是频控,不是请求不合法。前端据此判断要不要禁用按钮并倒计时。
            case SmsCodeService.SendOutcome.TooFrequent t -> throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "SMS_TOO_FREQUENT",
                    "发得太频繁了,请在 " + at(t.retryAt()) + " 之后再试。");

            case SmsCodeService.SendOutcome.DailyExhausted d -> throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    d.perPhone() ? "SMS_PHONE_DAILY_LIMIT" : "SMS_IP_DAILY_LIMIT",
                    (d.perPhone() ? "这个号今天已经发了 " : "当前网络今天已经发了 ")
                            + d.limit() + " 条验证码,请到 " + at(d.resetAt()) + " 之后再试。");

            case SmsCodeService.SendOutcome.PhoneLocked p -> throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "PHONE_LOCKED",
                    "验证码连续输错次数过多,请在 " + at(p.unlockAt()) + " 之后再试。");

            // 🔴 502 而不是 500:失败发生在供应商那一侧。
            // 具体的 vendorCode 只进日志 —— 那是我们和供应商之间的事。
            case SmsCodeService.SendOutcome.SendFailed f -> throw new ApiException(
                    HttpStatus.BAD_GATEWAY, "SMS_SEND_FAILED",
                    f.quotaRefunded()
                            ? "短信服务暂时不可用,这次不计入今天的次数,请稍后重试。"
                            : "短信可能已经发出,请先查收;若一直没收到,请稍后重试。");
        };
    }

    /**
     * 验证码换令牌 —— <b>注册即登录</b>。
     *
     * <p>{@code purpose=bind} 走不到这里:绑定要求已登录,见 {@link #bindPhone}。
     */
    @PostMapping("/sms/verify")
    public LoginResponse verify(@Valid @RequestBody SmsVerifyRequest req) {
        SmsPurpose purpose = parsePurpose(req.purpose());
        if (purpose != SmsPurpose.LOGIN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_PURPOSE",
                    "这个端点只处理登录。绑定手机号请调用 /api/auth/bind/phone。");
        }
        var passed = requirePassed(sms.verify(req.phone(), req.code(), SmsPurpose.LOGIN));
        AccountService.LoginResult result =
                accounts.loginByPhone(passed, req.deviceLabel(), req.referrer());
        return toLoginResponse(result);
    }

    /** 续期。为什么不换发新令牌,见 {@link RefreshResponse}。 */
    @PostMapping("/refresh")
    public RefreshResponse refresh(CurrentSession session) {
        // 参数解析器已经在 verify 里滑过一次了,这里直接回最新的过期时刻。
        return new RefreshResponse(session.token().expiresAt());
    }

    /** 退出这一台 —— <b>只吊销当前令牌</b>(docs/技术架构 §6.1)。 */
    @PostMapping("/logout")
    public java.util.Map<String, Object> logout(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        boolean revoked = header != null && header.startsWith("Bearer ")
                && tokens.revoke(header.substring(7).trim());
        // 🔴 没带令牌 / 令牌已失效也回 200。「退出登录」在网络不稳时会被点两次,
        // 第二次报错只会让人以为没退成功,然后反复点。
        return java.util.Map.of("revoked", revoked);
    }

    // —— 微信通道(关卡 2 后)——

    /**
     * 生成授权 URL 与配套的一次性 {@code state}。
     *
     * <p>URL 由服务端拼,因为 state 必须由服务端生成与校验 —— 见 {@link OneTimeStateStore}。
     */
    @GetMapping("/wechat/authorize-url")
    public WeChatAuthorizeUrlResponse authorizeUrl(@RequestParam String entry,
                                                   @RequestParam String redirectUri) {
        requireWeChatEnabled();
        String state;
        try {
            state = states.issue();
        } catch (IllegalStateException e) {
            // 429 而不是 500:这是频控,不是服务端出错。而这条端点不需要登录,
            // 所以它是整个鉴权面上少数几个「任何人都能施压」的入口之一。
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "WECHAT_AUTHORIZE_BUSY",
                    "微信登录请求过于频繁,请稍后再试。");
        }
        try {
            return new WeChatAuthorizeUrlResponse(
                    wechat.buildAuthorizeUrl(WeChatEntry.ofWireName(entry), redirectUri, state), state);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WECHAT_ENTRY_INVALID", e.getMessage());
        }
    }

    /**
     * 微信 code 换会话。unionid / openid 的联合查找与账号分裂识别在
     * {@link AccountService#loginByWeChat} —— <b>controller 不含规则</b>。
     */
    @PostMapping("/wechat/login")
    public LoginResponse wechatLogin(@Valid @RequestBody WeChatLoginRequest req) {
        requireWeChatEnabled();
        WeChatEntry entry = parseEntry(req.entry());
        WeChatIdentity id = exchange(entry, req.code(), req.state());
        return toLoginResponse(accounts.loginByWeChat(id, req.deviceLabel(), req.referrer()));
    }

    /**
     * 小程序<b>一步登录</b>:同一次交互里拿到微信身份 + 手机号。
     *
     * <h2>🔴 顺序就是这个方法的全部要点</h2>
     *
     * <pre>
     *   ① wx.login 的 code 换 openid  ← <b>免费</b>,而且它给出了做频控用的那把键
     *   ② 频控(按 openid,60s / 日限)   ← 超限:拒绝,<b>一分钱没花</b>
     *   ③ 换手机号                      ← <b>0.03 元/次,这一步开始花钱</b>
     * </pre>
     *
     * 与验证码四道闸完全同构(docs/后端详设 §1.8):<b>拦要拦在花钱那一步之前。</b>
     * 反过来写 —— 先换手机号再限流 —— 前面那道闸就只是在给账单排队。
     * <p>
     * 频控键用 openid 而不是手机号,是因为<b>手机号要花完那 0.03 元才知道</b>。
     * 这正是第①步必须在前面的原因:它免费,而且它产出了限流所需的身份。
     *
     * <p>⚠ 这条路径 docs/技术架构 §6.1 那张表里<b>没有</b> —— 它写于手机号快速验证未纳入考虑时。
     * 这是新增,不是对既有条目的改写。
     */
    @PostMapping("/wechat/phone-login")
    public LoginResponse wechatPhoneLogin(@Valid @RequestBody WeChatPhoneLoginRequest req,
                                          HttpServletRequest http) {
        requireWeChatEnabled();

        // ① 免费:换出 openid
        WeChatIdentity id;
        try {
            id = wechat.exchangeMiniProgramCode(req.loginCode());
        } catch (com.kaodian.server.auth.vendor.UnionIdMissingException e) {
            log.error("小程序未绑定到开放平台账号 —— unionid 拿不到,拒绝降级建号", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_UNIONID_MISSING",
                    "微信登录暂不可用:该小程序尚未绑定到微信开放平台账号。");
        } catch (WeChatException e) {
            log.warn("小程序 code 换取失败 errcode={}", e.errcode(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WECHAT_EXCHANGE_FAILED", "微信授权失败,请重试。");
        }

        // ② 频控。前缀是为了不和短信那边的 phoneHmac 撞进同一个计数格 ——
        // 两条路各自的额度必须独立,否则用了一条就少了另一条。
        String ip = clientIp.of(http);
        String quotaKey = "wxphone:" + id.openid();
        SmsRateLimiter.Decision decision = limiter.reserve(quotaKey, ip, Instant.now());
        switch (decision) {
            case SmsRateLimiter.Decision.TooFrequent d -> throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "WECHAT_PHONE_TOO_FREQUENT",
                    "操作太频繁了,请在 " + at(d.retryAt()) + " 之后再试。");
            case SmsRateLimiter.Decision.PhoneDailyExhausted d -> throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "WECHAT_PHONE_DAILY_LIMIT",
                    "今天获取手机号的次数已达上限,请到 " + at(d.resetAt()) + " 之后再试。");
            case SmsRateLimiter.Decision.IpDailyExhausted d -> throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "WECHAT_PHONE_IP_LIMIT",
                    "当前网络今天的次数已达上限,请到 " + at(d.resetAt()) + " 之后再试。");
            case SmsRateLimiter.Decision.Allowed ignored -> {
                // 继续
            }
        }

        // ③ 花钱
        String phone;
        try {
            phone = wechat.exchangePhoneCode(req.phoneCode());
        } catch (WeChatException e) {
            // 微信明确拒绝(code 过期/已消费)= 确定没扣费,把日额度还给用户;
            // 与 SmsCodeService 对 definitelyNotCharged 的处理是同一条。
            limiter.releaseDaily(quotaKey, ip);
            log.warn("换取手机号失败 errcode={}", e.errcode(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WECHAT_PHONE_FAILED",
                    "没能拿到手机号,请重试。");
        }

        return toLoginResponse(
                accounts.loginByWeChatWithPhone(id, phone, req.deviceLabel(), req.referrer()));
    }

    // —— 绑定与合并(关卡 2 后)——

    /** 已登录账号绑手机号。目标号已属他人 → 返回可合并提示,<b>不自动合并</b>。 */
    @PostMapping("/bind/phone")
    public BindResponse bindPhone(CurrentSession session, @Valid @RequestBody BindPhoneRequest req) {
        requireWeChatEnabled();
        session.requireWrite();
        var passed = requirePassed(sms.verify(req.phone(), req.code(), SmsPurpose.BIND));
        return toBindResponse(accounts.bind(
                session.userId(), IdentityType.PHONE, passed.phoneHmac(), passed.phone()));
    }

    /** 已登录账号绑微信 —— docs/技术架构 §7.1 说这是<b>最顺的那条路径,产品应主动引导走这条</b>。 */
    @PostMapping("/bind/wechat")
    public BindResponse bindWeChat(CurrentSession session, @Valid @RequestBody BindWeChatRequest req) {
        requireWeChatEnabled();
        session.requireWrite();
        WeChatIdentity id = exchange(parseEntry(req.entry()), req.code(), req.state());
        // unionid / openid 挂哪一条、挂几条,是 service 的规则 —— controller 不做这个判断。
        return toBindResponse(accounts.bindWeChat(session.userId(), id));
    }

    /** 合并预览 —— <b>只读,不产生副作用</b>。 */
    @PostMapping("/merge/preview")
    public MergePreviewResponse mergePreview(CurrentSession session, @Valid @RequestBody MergeRequest req) {
        requireWeChatEnabled();
        try {
            AccountService.MergePreview p = accounts.previewMerge(session.userId(), req.mergeToken());
            return new MergePreviewResponse(p.fromLabel(), p.toLabel(), p.movedRecordCount(),
                    p.expiresAt(), MergePreviewResponse.NOTICE);
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_TOKEN_INVALID", e.getMessage());
        }
    }

    /** 执行合并 —— <b>不可逆</b>,写 {@code account_merge_log}。 */
    @PostMapping("/merge/confirm")
    public java.util.Map<String, Object> mergeConfirm(CurrentSession session,
                                                      @Valid @RequestBody MergeRequest req) {
        requireWeChatEnabled();
        session.requireWrite();
        try {
            AccountMergeLog merged = accounts.confirmMerge(session.userId(), req.mergeToken());
            return java.util.Map.of(
                    "merged", true,
                    "movedRecordCount", merged.movedRecordCount(),
                    "mergedAt", merged.mergedAt().toString());
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_TOKEN_INVALID", e.getMessage());
        }
    }

    // —— 翻译层 ——

    /**
     * 六种终态 → 六句不同的话。
     *
     * <p>docs/后端详设 §1.8 那张表列的就是合并成一句「验证码错误」的代价:
     * 用户拿着过期的码反复输,把自己输到锁定。
     */
    private SmsCodeService.VerifyOutcome.Passed requirePassed(SmsCodeService.VerifyOutcome outcome) {
        return switch (outcome) {
            case SmsCodeService.VerifyOutcome.Passed p -> p;

            case SmsCodeService.VerifyOutcome.BadPhone b -> throw new ApiException(
                    HttpStatus.BAD_REQUEST, "BAD_PHONE", b.detail());

            case SmsCodeService.VerifyOutcome.Wrong w -> throw new ApiException(
                    HttpStatus.BAD_REQUEST, "CODE_WRONG",
                    "验证码不对,还可以再试 " + w.remainingAttempts() + " 次。");

            case SmsCodeService.VerifyOutcome.Expired ignored -> throw new ApiException(
                    HttpStatus.BAD_REQUEST, "CODE_EXPIRED",
                    "验证码已过期,请重新获取。");           // 「重新获取」不是「重试」

            case SmsCodeService.VerifyOutcome.Superseded ignored -> throw new ApiException(
                    HttpStatus.BAD_REQUEST, "CODE_SUPERSEDED",
                    "这条验证码已失效 —— 请用最新收到的那一条。");

            case SmsCodeService.VerifyOutcome.NoneOutstanding ignored -> throw new ApiException(
                    HttpStatus.BAD_REQUEST, "CODE_NONE",
                    "没有待验证的验证码,请先获取。");

            case SmsCodeService.VerifyOutcome.Locked l -> throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "PHONE_LOCKED",
                    "验证码连续输错次数过多,请在 " + at(l.unlockAt()) + " 之后再试。");
        };
    }

    private BindResponse toBindResponse(AccountService.BindResult result) {
        return switch (result) {
            case AccountService.BindResult.Bound ignored -> new BindResponse(true, null);
            // 🔴 只回令牌,不回对方是谁。回一句「这个号已被 xxx 占用」等于让任何人
            // 拿别人的号试一下就能确认对方是不是本产品的用户。
            case AccountService.BindResult.TakenByAnother t ->
                    new BindResponse(false, t.pending().token());
            // 400 而不是 500:这是一个用户能自己处理的状况(先解绑旧号),
            // 而 500 只会让他去念一串 traceId。
            case AccountService.BindResult.Refused r -> throw new ApiException(
                    HttpStatus.CONFLICT, "BIND_REFUSED", r.reason());
        };
    }

    private LoginResponse toLoginResponse(AccountService.LoginResult r) {
        String masked = accounts.maskedPhoneOf(r.user().id()).orElse(null);
        return new LoginResponse(
                r.token().plaintext(),                  // 唯一一次出现明文令牌的地方
                r.token().stored().expiresAt(),
                r.user().id(),
                r.isNewAccount(),
                masked,
                // 🔴 没有手机号 = 这个人下次换个入口进来可能又多一个账号(R-33)。
                // 引导补绑是最顺的那条路,比事后走合并便宜得多(docs/技术架构 §7.1)。
                masked == null,
                r.splitMergeToken());
    }

    private WeChatIdentity exchange(WeChatEntry entry, String code, String state) {
        // 🔴 小程序没有回跳,因此没有 state;其余两条入口必须校验,否则可被 CSRF 绑号。
        if (entry != WeChatEntry.MINI_PROGRAM && !states.consume(state)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WECHAT_STATE_INVALID",
                    "授权已过期,请重新发起微信登录。");
        }
        try {
            return entry == WeChatEntry.MINI_PROGRAM
                    ? wechat.exchangeMiniProgramCode(code)
                    : wechat.exchangeOAuthCode(entry, code);
        } catch (com.kaodian.server.auth.vendor.UnionIdMissingException e) {
            // 🔴 503 而不是 502:这不是微信那边出了问题,是【我们的配置】没做对。
            // 消息里点名要做什么 —— 一个只说「授权失败,请重试」的提示会让人一直重试。
            log.error("微信应用未绑定到开放平台账号 —— unionid 拿不到,拒绝降级建号", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_UNIONID_MISSING",
                    "微信登录暂不可用:该应用尚未绑定到微信开放平台账号。");
        } catch (WeChatException e) {
            log.warn("微信 code 换取失败 entry={} errcode={}", entry.wireName(), e.errcode(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WECHAT_EXCHANGE_FAILED",
                    "微信授权失败,请重试。");
        }
    }

    private void requireWeChatEnabled() {
        if (!wechat.isReal()) {
            // 503 而不是 404:端点存在,只是这个阶段还没开。
            // docs/技术架构 §7.2:阶段 2 只做手机号,微信在关卡 2 后。
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_NOT_ENABLED",
                    "微信登录尚未开放(docs/技术架构 §7.2:关卡 2 后)。");
        }
    }

    private static SmsPurpose parsePurpose(String s) {
        if (s == null || s.isBlank()) {
            return SmsPurpose.LOGIN;
        }
        try {
            return SmsPurpose.ofWireName(s);
        } catch (IllegalArgumentException e) {
            // 🔴 不能回 e.getMessage() —— 那条消息里带着用户原样输入的串,而它没有长度上限。
            throw ApiException.unknownValue("BAD_PURPOSE", "验证码用途", s);
        }
    }

    private static WeChatEntry parseEntry(String s) {
        try {
            return WeChatEntry.ofWireName(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.unknownValue("WECHAT_ENTRY_INVALID", "微信入口", s);
        }
    }

    /**
     * 把时点说成人话 —— <b>「请稍后再试」只惩罚真实用户</b>(docs/后端详设 §1.8)。
     *
     * <p>刷子不会因为文案含糊而少刷,而真实用户会因为不知道要等多久而反复点、
     * 把自己撞进下一道限制。
     */
    private String at(Instant t) {
        var local = t.atZone(zone);
        LocalDate today = LocalDate.now(zone);
        if (local.toLocalDate().equals(today)) {
            return local.format(HHMM);
        }
        if (local.toLocalDate().equals(today.plusDays(1))) {
            return "明天 " + local.format(HHMM);
        }
        return local.toLocalDate() + " " + local.format(HHMM);
    }
}
