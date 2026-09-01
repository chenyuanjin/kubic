package com.kaodian.server.auth.vendor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认的短信发送器 —— <b>把验证码打进日志,不发真短信,不花一分钱</b>。
 *
 * <h2>它不是「先凑合」,它是阶段纪律</h2>
 *
 * docs/technical/INDEX.md §七:手机号通道最早落地在<b>阶段 2</b>,而短信签名与模板报备各需 1-3 个工作日、
 * 要主体资质。在签名批下来之前,这个实现是唯一能跑的那个。
 * <p>
 * 更要紧的是它默认开着:思考模式 §盲区二记着这个项目自己的失败模式 ——
 * <b>注意力流向能做的部分,不是最不确定的部分。</b> 开通短信服务、充值、报备签名
 * 全都是「能做」且能产出可量化进展的事,<b>而它们一个都不需要面对真实用户</b>(总路线图 §六)。
 * 默认不花钱,是让这条链路在阶段到来之前保持零成本。
 *
 * <h2>🔴 它把验证码打进了日志</h2>
 *
 * 这是<b>本机开发专用</b>的行为,而且是故意的:没有它,本机就没法走完登录。
 * 但它意味着<b>任何能读到日志的人都能登录任何账号</b>。
 * 所以真实部署必须切到 {@link TencentCloudSmsSender} ——
 * 切换点是配置 {@code kaodian.auth.sms.provider},不是代码。
 */
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void sendVerificationCode(String e164Phone, String code) {
        log.warn("【开发模式·未发送真实短信】{} 的验证码是 {} —— 生产环境必须配置真实供应商", e164Phone, code);
    }

    @Override
    public boolean isReal() {
        return false;
    }
}
