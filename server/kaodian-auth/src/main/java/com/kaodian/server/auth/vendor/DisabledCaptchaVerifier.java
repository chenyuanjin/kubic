package com.kaodian.server.auth.vendor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认的行为验证实现 —— <b>一律放行</b>。
 *
 * <h2>🔴 它一放行,第①道闸就没了</h2>
 *
 * 而第①道闸是这条链路上<b>唯一真正的闸</b>:单号 1/60s 与单 IP 20/日 都是纯计数,
 * 换一批 IP、换一批号,两条都不触发,账单照涨(docs/后端详设 §1.8)。
 * <p>
 * 之所以敢让它做默认值,是因为它必须和 {@link LoggingSmsSender} <b>成对出现</b>:
 * 默认的发送器根本不发真短信,没有账单可刷。
 * <p>
 * 所以真正的红线是这一条 —— <b>短信切成真实供应商的那一刻,
 * 验证码也必须切成真实供应商</b>。这个配对由启动期自检强制,见 {@code AuthBeans}。
 */
public class DisabledCaptchaVerifier implements CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(DisabledCaptchaVerifier.class);

    @Override
    public Verdict verify(String ticket, String randstr, String userIp) {
        log.debug("行为验证未启用,直接放行 ip={}", userIp);
        return Verdict.pass();
    }

    @Override
    public boolean isReal() {
        return false;
    }
}
