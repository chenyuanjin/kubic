package com.kaodian.server.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 调用方 IP —— 短信频控第③道闸的输入。
 *
 * <h2>🔴 默认<b>不</b>信任 {@code X-Forwarded-For}</h2>
 *
 * 这个头是客户端可以随便写的。信任它而前面又没有反代,等于把「单 IP 20/日」
 * 变成一行注释:刷子每次请求换一个假 IP,这道闸永远不触发。
 * <p>
 * 所以要显式打开({@code kaodian.auth.trust-forwarded-for=true}),
 * 而<b>打开的前提是前面确实有一个会重写这个头的反代</b>(线上是同机 Caddy)。
 *
 * <h2>打开之后取的是<b>最右边</b>那一个</h2>
 *
 * {@code X-Forwarded-For} 是逐跳追加的。客户端伪造的值排在左边,
 * 我们自己的反代追加的真实地址排在<b>最右</b>。
 * 取最左边(很多示例代码都这么写)恰好取到的是攻击者写的那个 ——
 * <b>这是这个头最经典的一个用反。</b>
 *
 * <p>取不到时返回空串:{@code FileSmsRateLimiter} 会把空串当成「这道闸没有意义」而放行。
 * 登录不能因为拿不到 IP 就失败。
 */
@Component
public class ClientIp {

    private static final String HEADER = "X-Forwarded-For";

    private final boolean trustForwardedFor;

    public ClientIp(@Value("${kaodian.auth.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public String of(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        if (trustForwardedFor) {
            String xff = request.getHeader(HEADER);
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                String last = parts[parts.length - 1].trim();
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return "";
        }
        // 🔴 回环地址一律当成「取不到」。
        //
        // 线上是【同机 Caddy 反代】(见 application.properties 的 server.address 那一段),
        // 所以 getRemoteAddr() 恒为 127.0.0.1。如果把它当成一个真实 IP 计进频控,
        // 「单 IP 20/日」就塌缩成【全体用户共享一个 20 条/日的桶】——
        // 那不是防线失效,是我们自己把自己 DoS 了:每天第 21 个用户就再也收不到验证码。
        //
        // 而 FileSmsRateLimiter 对空串的处理是「这道闸没有意义,放行」——
        // 那才是这种部署下的正确语义:IP 维度此刻不携带任何信息,
        // 拿不到就别假装拿到了。真要恢复这道闸,把 trust-forwarded-for 打开。
        if (isLoopback(remote)) {
            return "";
        }
        return remote;
    }

    private static boolean isLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)
                || ip.startsWith("127.");
    }
}
