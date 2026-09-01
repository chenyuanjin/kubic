package com.kaodian.server.auth.vendor;

/**
 * 把六位数字送到那个号上 —— <b>这是整个后端唯一会产生「按条计费的外部账单」的动作</b>。
 *
 * <h2>为什么它是一个接口</h2>
 *
 * 与 {@code recognize} 包的 {@code AsrClient} 完全同构,理由也一样(docs/data/识别链路选型.md 坑三):
 * <b>供应商是会变的,切换点必须只有一个。</b>
 * 短信这一侧尤其如此 —— 签名与模板报备各需 1-3 个工作日、要主体资质(docs/technical/INDEX.md §7.3),
 * 一旦某家审不过,能换的前提是当初只有一处调用。
 *
 * <h2>🔴 阶段 0/1 的默认实现不发短信</h2>
 *
 * {@link LoggingSmsSender} 把验证码打进日志。这不是「先凑合」——
 * docs/technical/INDEX.md §七 写明手机号通道最早落地在<b>阶段 2</b>,而在那之前每发一条真短信
 * 都是在为一个还没有用户的产品付费。默认不花钱是这条纪律在代码里的样子。
 */
public interface SmsSender {

    /**
     * 发一条验证码短信。
     *
     * @param e164Phone E.164 形态,如 {@code +8613800138000}
     * @param code      六位数字明文。<b>只在这一层出现</b> —— 库里存的是 HMAC
     * @throws SmsDeliveryException 没发出去
     */
    void sendVerificationCode(String e164Phone, String code) throws SmsDeliveryException;

    /** 这个实现会不会真的产生账单。接口层据此决定要不要在响应里带上开发用的提示。 */
    boolean isReal();
}
