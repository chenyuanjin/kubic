package com.kaodian.server.api.support;

import com.kaodian.server.auth.AccountService;
import com.kaodian.server.auth.AccountStore;
import com.kaodian.server.auth.OneTimeStateStore;
import com.kaodian.server.auth.PhoneCipher;
import com.kaodian.server.auth.PhoneKeyGuard;
import com.kaodian.server.auth.SignupLedger;
import com.kaodian.server.auth.SmsCodeService;
import com.kaodian.server.auth.SmsCodeStore;
import com.kaodian.server.auth.SmsRateLimiter;
import com.kaodian.server.auth.TokenService;
import com.kaodian.server.auth.TokenStore;
import com.kaodian.server.auth.vendor.CaptchaVerifier;
import com.kaodian.server.auth.vendor.DisabledCaptchaVerifier;
import com.kaodian.server.auth.vendor.DisabledWeChatClient;
import com.kaodian.server.auth.vendor.HttpWeChatClient;
import com.kaodian.server.auth.vendor.LoggingSmsSender;
import com.kaodian.server.auth.vendor.SmsSender;
import com.kaodian.server.auth.vendor.TencentCaptchaVerifier;
import com.kaodian.server.auth.vendor.TencentCloudSmsSender;
import com.kaodian.server.auth.vendor.WeChatClient;
import com.kaodian.server.auth.vendor.WeChatCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 鉴权这一层的装配点 —— <b>谁组装,谁依赖框架</b>。
 *
 * <p>与 {@link com.kaodian.server.config.DomainBeans} 同一形态:{@code auth} 包里的领域类
 * ({@code TokenService} / {@code SmsCodeService} / {@code AccountService})
 * 都不认识 Spring,能在没有容器的情况下直接 new 出来测试。
 *
 * <h2>🔴 一条启动期红线:短信是真的,滑块就必须也是真的</h2>
 *
 * 见 {@link #checkVendorPairing}。这是本文件存在的最主要理由 ——
 * 它把一条只在文档里写着的纪律变成了一次<b>起不来的启动</b>。
 */
@Configuration
public class AuthBeans {

    private static final Logger log = LoggerFactory.getLogger(AuthBeans.class);

    @Bean
    public TokenService tokenService(TokenStore store, Clock clock) {
        return new TokenService(store, clock);
    }

    @Bean
    public SmsCodeService smsCodeService(SmsCodeStore store, SmsRateLimiter limiter,
                                         CaptchaVerifier captcha, SmsSender sender,
                                         PhoneCipher cipher, Clock clock) {
        return new SmsCodeService(store, limiter, captcha, sender, cipher, clock);
    }

    @Bean
    public AccountService accountService(AccountStore accounts, SignupLedger signups,
                                         TokenService tokens, PhoneCipher cipher, Clock clock) {
        return new AccountService(accounts, signups, tokens, cipher, clock);
    }

    @Bean
    public OneTimeStateStore weChatStateStore(Clock clock) {
        return new OneTimeStateStore(clock);
    }

    /**
     * 🔴 {@code R-59} 的防线 —— 启动期跑一次,把「换了手机号密钥」从一次无声的数据损坏
     * 变成一次响亮的事件。四种情形四种处置,只有「AES 也变了且救不回来」才拒绝启动。
     *
     * <p>它<b>必须在任何一次登录之前跑完</b>,所以做成一个启动期就会被实例化的 bean,
     * 而不是第一次调用时的懒检查 —— 懒检查意味着第一个用户已经被建成了新账号。
     *
     * @see PhoneKeyGuard
     */
    @Bean
    public PhoneKeyGuard.Outcome phoneKeyGuard(
            AccountStore accounts, PhoneCipher cipher,
            @Value("${kaodian.auth.keys.phone-hmac-previous:}") String prevHmac,
            @Value("${kaodian.auth.keys.phone-aes-previous:}") String prevAes,
            @Value("${kaodian.auth.keys.accept-key-loss:false}") boolean acceptKeyLoss) {

        // 只换了一把时,另一把沿用当前的 —— 这是有计划轮换里最常见的形态,
        // 逼人把没变的那把也抄一遍只会增加抄错的机会。派生在 PhoneCipher 内部完成,
        // 密钥字节不经过这里。
        PhoneCipher previous = (prevHmac.isBlank() && prevAes.isBlank())
                ? null
                : cipher.previousOf(prevHmac, prevAes);
        return new PhoneKeyGuard(accounts, cipher, previous, acceptKeyLoss).check();
    }

    @Bean
    public CurrentSessionResolver currentSessionResolver(TokenService tokens) {
        return new CurrentSessionResolver(tokens);
    }

    // —— 供应商 ——

    /**
     * 短信发送器。默认 {@link LoggingSmsSender} —— <b>不发真短信,不花一分钱</b>。
     *
     * <p>切成真实供应商只需要改 {@code kaodian.auth.sms.provider=tencent} 并补齐四个配置项。
     * 但那之前签名与模板必须已报备(各 1-3 个工作日,需主体资质)—— {@code R-34}。
     */
    @Bean
    public SmsSender smsSender(
            @Value("${kaodian.auth.sms.provider:logging}") String provider,
            @Value("${kaodian.auth.sms.tencent.secret-id:}") String secretId,
            @Value("${kaodian.auth.sms.tencent.secret-key:}") String secretKey,
            @Value("${kaodian.auth.sms.tencent.sdk-app-id:}") String sdkAppId,
            @Value("${kaodian.auth.sms.tencent.sign-name:}") String signName,
            @Value("${kaodian.auth.sms.tencent.template-id:}") String templateId,
            @Value("${kaodian.auth.sms.tencent.region:ap-guangzhou}") String region,
            @Value("${kaodian.auth.sms.tencent.template-param-count:2}") int paramCount) {
        if (!"tencent".equalsIgnoreCase(provider)) {
            log.warn("短信发送器 = 开发模式(验证码打进日志,不发真短信)。"
                    + "任何能读到日志的人都能登录任何账号 —— 生产必须切到 tencent。");
            return new LoggingSmsSender();
        }
        requireConfigured("kaodian.auth.sms.tencent.secret-id", secretId);
        requireConfigured("kaodian.auth.sms.tencent.secret-key", secretKey);
        requireConfigured("kaodian.auth.sms.tencent.sdk-app-id", sdkAppId);
        requireConfigured("kaodian.auth.sms.tencent.sign-name", signName);
        requireConfigured("kaodian.auth.sms.tencent.template-id", templateId);
        return new TencentCloudSmsSender(secretId, secretKey, sdkAppId, signName, templateId,
                region, paramCount, (int) SmsCodeService.CODE_TTL.toMinutes());
    }

    /** 行为验证。默认不校验 —— 与默认的短信发送器成对,见 {@link #checkVendorPairing}。 */
    @Bean
    public CaptchaVerifier captchaVerifier(
            @Value("${kaodian.auth.captcha.provider:disabled}") String provider,
            @Value("${kaodian.auth.captcha.tencent.secret-id:}") String secretId,
            @Value("${kaodian.auth.captcha.tencent.secret-key:}") String secretKey,
            @Value("${kaodian.auth.captcha.tencent.app-id:0}") long appId,
            @Value("${kaodian.auth.captcha.tencent.app-secret-key:}") String appSecretKey) {
        if (!"tencent".equalsIgnoreCase(provider)) {
            return new DisabledCaptchaVerifier();
        }
        requireConfigured("kaodian.auth.captcha.tencent.secret-id", secretId);
        requireConfigured("kaodian.auth.captcha.tencent.secret-key", secretKey);
        requireConfigured("kaodian.auth.captcha.tencent.app-secret-key", appSecretKey);
        if (appId <= 0) {
            throw new IllegalStateException("缺少配置:kaodian.auth.captcha.tencent.app-id");
        }
        return new TencentCaptchaVerifier(secretId, secretKey, appId, appSecretKey);
    }

    /**
     * 微信。默认 {@link DisabledWeChatClient} —— <b>关卡 2 后才启用</b>(docs/技术架构 §7.2)。
     *
     * <p>三条入口是三个不同的应用,各要一笔认证费与一次审核。
     * 在关卡 2 之前掏这笔钱,买的是一个还没被验证的方向。
     */
    @Bean
    public WeChatClient weChatClient(
            @Value("${kaodian.auth.wechat.enabled:false}") boolean enabled,
            @Value("${kaodian.auth.wechat.mini-program.app-id:}") String mpId,
            @Value("${kaodian.auth.wechat.mini-program.secret:}") String mpSecret,
            @Value("${kaodian.auth.wechat.official-account.app-id:}") String oaId,
            @Value("${kaodian.auth.wechat.official-account.secret:}") String oaSecret,
            @Value("${kaodian.auth.wechat.website.app-id:}") String webId,
            @Value("${kaodian.auth.wechat.website.secret:}") String webSecret,
            @Value("${kaodian.auth.wechat.require-unionid:true}") boolean requireUnionId) {
        if (!enabled) {
            return new DisabledWeChatClient();
        }
        // 🔴 默认要求 unionid:有开放平台账号且绑定做对了,它必然存在。
        // 拿不到就是配置坏了 —— 降级用 openid 只会攒出一批将来要靠 R-63 自愈的账号。
        return new HttpWeChatClient(requireUnionId, new WeChatCredentials(
                new WeChatCredentials.App(mpId, mpSecret),
                new WeChatCredentials.App(oaId, oaSecret),
                new WeChatCredentials.App(webId, webSecret)));
    }

    /**
     * 🔴 <b>短信是真的,滑块就必须也是真的。</b>
     *
     * <p>{@link DisabledCaptchaVerifier} 一律放行,于是第①道闸消失;
     * 而第①道闸是这条链路上<b>唯一真正的闸</b> —— 单号 1/60s 与单 IP 20/日 都是纯计数,
     * 换一批 IP、换一批号两条都不触发,而每一条短信都是真金白银(docs/后端详设 §1.8)。
     * <p>
     * 默认组合(假短信 + 不校验)是安全的:没有账单可刷。
     * 危险的只有<b>真短信 + 不校验</b>这一种组合,而它恰好是「先把短信配上,
     * 验证码回头再说」这个最自然的接入顺序会产生的中间状态。
     * <p>
     * 所以让它<b>起不来</b>,而不是打一条警告 —— 警告会被划过去,账单不会。
     */
    @Bean
    public VendorPairingCheck checkVendorPairing(SmsSender sender, CaptchaVerifier captcha) {
        if (sender.isReal() && !captcha.isReal()) {
            throw new IllegalStateException("""
                    拒绝启动:短信已切到真实供应商,但行为验证仍是 disabled。
                    这是唯一一种会被无限刷短信费的组合 —— 纯计数频控挡不住换 IP 换号的分布式刷。
                    请配置 kaodian.auth.captcha.provider=tencent 及其四个配置项(docs/后端详设 §1.8)。""");
        }
        if (!sender.isReal()) {
            log.warn("鉴权链路处于开发模式:不发真短信、不校验滑块。仅限本机。");
        }
        return new VendorPairingCheck();
    }

    /** 只为了让上面那个检查在启动期一定被执行。 */
    public static final class VendorPairingCheck {
    }

    private static void requireConfigured(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少配置:" + key);
        }
    }
}
