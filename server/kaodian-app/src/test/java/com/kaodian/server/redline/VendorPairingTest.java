package com.kaodian.server.redline;

import com.kaodian.server.api.support.AuthBeans;
import com.kaodian.server.auth.vendor.CaptchaVerifier;
import com.kaodian.server.auth.vendor.DisabledCaptchaVerifier;
import com.kaodian.server.auth.vendor.LoggingSmsSender;
import com.kaodian.server.auth.vendor.SmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>真短信 + 不校验滑块 = 无限账单。这条组合必须让服务起不来。</b>
 *
 * <h2>为什么这条断言值得单独存在</h2>
 *
 * {@code AuthBeans#checkVendorPairing} 是整条鉴权链路上<b>唯一一处防的是「钱」而不是「登录」</b>的检查。
 * 它此前只有实现,没有断言 —— 而它保护的东西(账单)恰恰是出事之后<b>最不可回滚</b>的那一类:
 * 代码可以改回来,已经发出去的几十万条短信不能。
 *
 * <h2>它挡的是一个中间状态,不是一次失误</h2>
 *
 * 「真短信 + 不校验」不是谁写错了,是<b>「先把短信配上,验证码回头再说」这个最自然的接入顺序
 * 必然经过的那一步</b>。而在这一步上:
 * <ul>
 *   <li>单号 1/60s、单 IP 20/日 都是<b>纯计数</b> —— 换一批号、换一批 IP 两条都不触发</li>
 *   <li>滑块是这条链路上<b>唯一真正的闸</b>(见 {@link CaptchaVerifier} 类注释)</li>
 * </ul>
 * 所以它必须表现为一次<b>起不来的启动</b>,而不是一条 WARN —— 警告会被划过去,账单不会。
 *
 * <h2>🔴 它红过</h2>
 *
 * 把 {@code checkVendorPairing} 里的 {@code sender.isReal() && !captcha.isReal()} 改成
 * {@code false},本类第一个用例当场失败:
 * {@code expected java.lang.IllegalStateException to be thrown, but nothing was thrown}。
 * 改回即恢复绿色。
 */
class VendorPairingTest {

    private final AuthBeans beans = new AuthBeans();

    /** 会真的产生账单的发送器。只需要 {@code isReal()} 为真 —— 检查看的就是这一位。 */
    private static SmsSender realSender() {
        return new SmsSender() {
            @Override
            public void sendVerificationCode(String e164Phone, String code) {
                throw new AssertionError("这个测试不该真的发短信");
            }

            @Override
            public boolean isReal() {
                return true;
            }
        };
    }

    private static CaptchaVerifier realCaptcha() {
        return new CaptchaVerifier() {
            @Override
            public Verdict verify(String ticket, String randstr, String userIp) {
                return Verdict.pass();
            }

            @Override
            public boolean isReal() {
                return true;
            }
        };
    }

    @Test
    @DisplayName("🔴 真短信 + 滑块 disabled → 拒绝启动,而且消息里说清楚该配哪个键")
    void realSmsWithoutCaptchaRefusesToStart() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> beans.checkVendorPairing(realSender(), new DisabledCaptchaVerifier()));

        // 只说「配置错误」等于让人去翻源码。消息必须点名那个键 ——
        // 这条异常出现的时刻,人多半正在上线,没有时间读代码。
        assertTrue(e.getMessage().contains("kaodian.auth.captcha.provider=tencent"),
                "拒绝启动的消息必须点名要配的键,实际是:" + e.getMessage());
    }

    @Test
    @DisplayName("真短信 + 真滑块 → 放行(这是生产该有的样子)")
    void realSmsWithRealCaptchaIsFine() {
        assertDoesNotThrow(() -> beans.checkVendorPairing(realSender(), realCaptcha()));
    }

    @Test
    @DisplayName("假短信 + 滑块 disabled → 放行。默认组合是安全的:没有账单可刷")
    void devDefaultsAreFine() {
        assertDoesNotThrow(
                () -> beans.checkVendorPairing(new LoggingSmsSender(), new DisabledCaptchaVerifier()));
    }

    @Test
    @DisplayName("假短信 + 真滑块 → 放行。多一道闸不花钱,不该拦")
    void realCaptchaWithoutRealSmsIsFine() {
        assertDoesNotThrow(() -> beans.checkVendorPairing(new LoggingSmsSender(), realCaptcha()));
    }
}
