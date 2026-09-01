package com.kaodian.server.auth.vendor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Set;

/**
 * 腾讯云短信 {@code SendSms}。
 *
 * <h2>为什么是腾讯云</h2>
 *
 * docs/data/识别链路选型.md 已经为 ASR 选了腾讯云(免费额度覆盖到阶段 3)。短信跟着走同一家,
 * 省掉的是<b>第二次实名、第二次充值、第二份发票、第二个控制台</b> ——
 * 对一个 2-3 人的独立业务,这些的成本高于单价那几厘钱的差异。
 * <p>
 * 这不是一条不可逆的决定:{@link SmsSender} 就是那个切换点(docs/data/识别链路选型.md 坑三)。
 *
 * <h2>🔴 上线前置:签名与模板必须已报备</h2>
 *
 * 国内签名与模板审核各需 <b>1-3 个工作日</b>,且<b>需主体资质</b>
 * (2025-09-18 起个人自用签名已不再开放)。docs/technical/INDEX.md §7.3 与 {@code R-34} 说的就是这件事:
 * <b>签名没批,第 9 周的登录写不出来,阶段 2 直接停在起点。</b>
 * <p>
 * 代码在今天写完不会让那件事提前一天完成 —— 报备是行政流程,不是工程任务。
 * 这个类存在的意义只是让「签名批下来的那天」<b>不必再动代码,只需要填配置</b>。
 * <p>
 * ⚠ 那一天要填的是 <b>11 项</b>,不是坊间说的四项:本类这一侧 5 项
 * (secret-id / secret-key / sdk-app-id / sign-name / template-id),
 * 加上配对红线强制的滑块 4 项,再加两个 {@code provider} 开关。
 * 而拿到滑块那 4 项之前,还要先在控制台<b>单独开通验证码产品并建一个验证码应用</b>。
 * 完整清单见 {@code application.properties} 的「签名批下来那天」模板段。
 */
public class TencentCloudSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(TencentCloudSmsSender.class);

    private static final String HOST = "sms.tencentcloudapi.com";
    private static final String SERVICE = "sms";
    private static final String ACTION = "SendSms";
    private static final String VERSION = "2021-01-11";

    /**
     * 这些错误码意味着<b>短信确定没发出去、也确定没扣费</b> —— 日额度要还给用户。
     *
     * <p>它们的共同点是:失败原因在<b>我们这一侧</b>(配置没做对、余额没充),
     * 而不是在用户那一侧。用我们自己的配置错误去吃掉用户 10 条/日 里的一条,
     * 会让「重试」这个动作在用户看来越试越少。
     */
    private static final Set<String> DEFINITELY_NOT_CHARGED = Set.of(
            "FailedOperation.SignatureIncorrectOrUnapproved",
            "FailedOperation.TemplateIncorrectOrUnapproved",
            "FailedOperation.TemplateParamSetNotMatchApprovedTemplate",
            "FailedOperation.InsufficientBalanceInSmsPackage",
            "FailedOperation.SmsSdkAppIdVerifyFail",
            "UnauthorizedOperation.SmsSdkAppIdVerifyFail",
            "InvalidParameterValue.IncorrectPhoneNumber",
            "LimitExceeded.PhoneNumberThirtySecondLimit",
            "LimitExceeded.PhoneNumberOneHourLimit",
            "LimitExceeded.PhoneNumberDailyLimit");

    private final TencentCloudApi api;
    private final String sdkAppId;
    private final String signName;
    private final String templateId;
    private final String region;
    private final int templateParamCount;
    private final int codeTtlMinutes;

    public TencentCloudSmsSender(String secretId, String secretKey, String sdkAppId, String signName,
                                 String templateId, String region, int templateParamCount,
                                 int codeTtlMinutes) {
        this.api = new TencentCloudApi(secretId, secretKey, Duration.ofSeconds(5));
        this.sdkAppId = sdkAppId;
        this.signName = signName;
        this.templateId = templateId;
        this.region = region;
        this.templateParamCount = templateParamCount;
        this.codeTtlMinutes = codeTtlMinutes;
    }

    @Override
    public void sendVerificationCode(String e164Phone, String code) throws SmsDeliveryException {
        ObjectNode body = TencentCloudApi.mapper().createObjectNode();
        ArrayNode phones = body.putArray("PhoneNumberSet");
        phones.add(e164Phone);
        body.put("SmsSdkAppId", sdkAppId);
        body.put("SignName", signName);
        body.put("TemplateId", templateId);

        // 模板变量顺序必须与已审核通过的模板逐字对应。
        // 常见模板是「您的验证码是{1},{2}分钟内有效」→ 两个参数;
        // 只有验证码一个占位符的模板 → 一个参数。数量不符会得到
        // TemplateParamSetNotMatchApprovedTemplate,而那是配置问题不是代码问题。
        ArrayNode params = body.putArray("TemplateParamSet");
        params.add(code);
        if (templateParamCount >= 2) {
            params.add(String.valueOf(codeTtlMinutes));
        }

        JsonNode resp;
        try {
            resp = api.call(HOST, SERVICE, ACTION, VERSION, region, body.toString());
        } catch (TencentCloudApi.VendorCallException e) {
            String code0 = e.code();
            if (code0 == null) {
                // 传输层失败(超时、连接重置)。短信可能已经在路上 —— 按「已发生」算。
                throw new SmsDeliveryException("短信发送失败(网络)。" + TencentCloudApi.hintForClockSkew(), e);
            }
            throw new SmsDeliveryException("短信发送被拒绝", code0, DEFINITELY_NOT_CHARGED.contains(code0));
        }

        // 请求级成功不等于这个号发成功:逐号状态在 SendStatusSet 里。
        JsonNode status = resp.path("SendStatusSet").path(0);
        String statusCode = status.path("Code").asString("");
        if (!"Ok".equals(statusCode)) {
            throw new SmsDeliveryException("短信发送未成功", statusCode,
                    DEFINITELY_NOT_CHARGED.contains(statusCode));
        }
        // 🔴 日志里不打手机号,也不打验证码。SerialNo 足以向供应商追一条。
        log.info("短信已发送 serialNo={} fee={}",
                status.path("SerialNo").asString(""), status.path("Fee").asInt(0));
    }

    @Override
    public boolean isReal() {
        return true;
    }
}
