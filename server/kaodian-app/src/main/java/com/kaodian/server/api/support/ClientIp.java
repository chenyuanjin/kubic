package com.kaodian.server.api.support;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ClientIp.class);

    private static final String HEADER = "X-Forwarded-For";

    private final boolean trustForwardedFor;

    /** 只吼一次。每个请求都打一条的话,这条提示会淹没在自己的噪音里。 */
    private final java.util.concurrent.atomic.AtomicBoolean loopbackWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

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
                // 🔴 校验格式再用。反代追加的应当是一个真实地址,拿到别的东西说明反代配错了 ——
                // 而把「not-an-ip」这种串当成 IP 塞进频控,会在计数空间里造出一个谁也对不上的键:
                // 频控看起来在工作,实际上每个畸形值都独占一个桶,等于没有频控。
                if (isIpLike(last)) {
                    return last;
                }
                if (!last.isEmpty()) {
                    log.warn("X-Forwarded-For 最右一段不是 IP,已忽略并回退到 remoteAddr —— 检查反代配置");
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
            warnOnceAboutDisabledIpGate();
            return "";
        }
        return remote;
    }

    /**
     * 🔴 把「IP 频控此刻是关着的」这件事说出来 —— <b>一次,不刷屏</b>。
     *
     * <p>回环 → 空串这条路把「全站共享一个 20/日 的桶」这个更糟的形态修掉了,
     * 但它<b>没有把闸修回来</b>:闸仍然是关的,只是关得干净而不是坏得诡异。
     * <p>
     * 而这两种部署长得一模一样:
     * <ul>
     *   <li><b>本机开发</b> —— 闸关着完全正确(短信根本不真发,没有账单可刷)</li>
     *   <li><b>线上反代但忘了置 {@code trust-forwarded-for=true}</b> —— 闸关着是<b>事故</b></li>
     * </ul>
     * 服务端分不出这两者(它看到的都是 {@code 127.0.0.1})。分不出就别猜 ——
     * <b>把事实打出来,让看日志的人自己判断。</b> 这与「宁可说不知道也不要假装知道」是同一条。
     */
    private void warnOnceAboutDisabledIpGate() {
        if (loopbackWarned.compareAndSet(false, true)) {
            log.warn("请求来自回环地址而 kaodian.auth.trust-forwarded-for=false —— "
                    + "「单 IP 20/日」这道闸当前【不生效】。"
                    + "本机开发时这是对的;若前面有反代(线上是同机 Caddy),请置 true,"
                    + "否则短信频控只剩单号那一维。");
        }
    }

    /**
     * 粗校验:是不是像个 IP。
     *
     * <p>用 {@link java.net.InetAddress} 会做 DNS 解析 —— 那意味着<b>请求头能让服务端发起一次
     * 域名查询</b>,一个不该存在的外连。所以只做字面判断。
     */
    private static boolean isIpLike(String s) {
        if (s == null || s.isEmpty() || s.length() > 45) {
            return false;
        }
        boolean v4 = s.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = s.matches("[0-9A-Fa-f:]{2,45}") && s.contains(":");
        return v4 || v6;
    }

    private static boolean isLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)
                || ip.startsWith("127.");
    }
}
