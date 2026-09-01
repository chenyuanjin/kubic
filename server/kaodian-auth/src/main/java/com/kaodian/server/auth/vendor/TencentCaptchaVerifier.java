package com.kaodian.server.auth.vendor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * 腾讯云验证码 {@code DescribeCaptchaResult} —— 服务端核查票据。
 *
 * <h2>前端拿到的 ticket 必须回服务端核一次,不能只看前端说「过了」</h2>
 *
 * 前端那次「滑动成功」的回调是<b>在攻击者的机器上</b>发生的,他想让它成功就能让它成功。
 * 真正有意义的是拿 {@code ticket + randstr} 回腾讯云问一次。少了这一步,
 * 整个滑块就只是一张图片。
 *
 * <h2>{@code randstr} 不能省</h2>
 *
 * 它和 {@code ticket} 是一对,单给票据必然校验失败。这是接入时最常见的一个坑:
 * 前端回调里两个值都有,而后端 DTO 只接了一个,于是<b>校验永远不通过</b>,
 * 然后有人把这一步改成「失败也放行」——那就回到了没有滑块。
 *
 * <h2>小程序侧是另一个接口</h2>
 *
 * Web/App 用 {@code DescribeCaptchaResult};微信小程序插件用 {@code DescribeCaptchaMiniResult}。
 * 小程序在阶段 2 之后才回来(docs/technical/INDEX.md §一 的形态决定),届时这里加一个分支即可。
 */
public class TencentCaptchaVerifier implements CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(TencentCaptchaVerifier.class);

    private static final String HOST = "captcha.tencentcloudapi.com";
    private static final String SERVICE = "captcha";
    private static final String ACTION = "DescribeCaptchaResult";
    private static final String VERSION = "2019-07-22";

    /** 固定值 9 = 滑动拼图。腾讯云的枚举里目前只有这一个可用值。 */
    private static final int CAPTCHA_TYPE = 9;

    /** {@code CaptchaCode == 1} 才是通过。其余一律是不通过,不做「大概算通过」的解读。 */
    private static final int PASS_CODE = 1;

    private final TencentCloudApi api;
    private final long captchaAppId;
    private final String appSecretKey;

    public TencentCaptchaVerifier(String secretId, String secretKey,
                                  long captchaAppId, String appSecretKey) {
        this.api = new TencentCloudApi(secretId, secretKey, Duration.ofSeconds(3));
        this.captchaAppId = captchaAppId;
        this.appSecretKey = appSecretKey;
    }

    @Override
    public Verdict verify(String ticket, String randstr, String userIp) {
        if (ticket == null || ticket.isBlank() || randstr == null || randstr.isBlank()) {
            // 缺参数直接判不通过,不去调远端 —— 省一次调用,也让「前端漏传」表现为一个明确的失败。
            return Verdict.fail("缺少 ticket 或 randstr");
        }
        ObjectNode body = TencentCloudApi.mapper().createObjectNode();
        body.put("CaptchaType", CAPTCHA_TYPE);
        body.put("Ticket", ticket);
        body.put("UserIp", userIp == null ? "" : userIp);
        body.put("Randstr", randstr);
        body.put("CaptchaAppId", captchaAppId);
        body.put("AppSecretKey", appSecretKey);

        try {
            JsonNode resp = api.call(HOST, SERVICE, ACTION, VERSION, "", body.toString());
            int code = resp.path("CaptchaCode").asInt(-1);
            if (code == PASS_CODE) {
                return Verdict.pass();
            }
            return Verdict.fail("CaptchaCode=" + code + " " + resp.path("CaptchaMsg").asString(""));
        } catch (TencentCloudApi.VendorCallException e) {
            // 🔴 供应商不可用时【判不通过】,不是【放行】。
            // 放行的后果是:任何人只要把验证码服务打挂,就能开始刷短信。
            // 这与「记录动作永不失败」不冲突 —— 那条说的是【记录】,而这里挡的是【花钱】。
            log.warn("行为验证服务不可用,按不通过处理 code={}", e.code(), e);
            return Verdict.fail("验证服务暂时不可用");
        }
    }

    @Override
    public boolean isReal() {
        return true;
    }
}
