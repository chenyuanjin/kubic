package com.kaodian.server.auth.vendor;

/**
 * 微信侧的失败。
 *
 * <p>{@code errcode} 留着是为了排查 —— 微信的错误码非常具体,而且<b>它们的含义
 * 常常和字面不一样</b>:
 *
 * <table border="1">
 *   <caption>接入时最常撞上的几个</caption>
 *   <tr><th>errcode</th><th>字面</th><th>实际多半是</th></tr>
 *   <tr><td>40029</td><td>invalid code</td>
 *       <td><b>用错了 appid</b> —— 公众号的 appid 换不出网站应用的 code</td></tr>
 *   <tr><td>40163</td><td>code been used</td>
 *       <td>回跳页面被刷新了一次;code 单次消费</td></tr>
 *   <tr><td>40125</td><td>invalid appsecret</td>
 *       <td>secret 被重置过,或者配置里带了空格</td></tr>
 *   <tr><td>41001</td><td>access_token missing</td>
 *       <td>拿的是 {@code /cgi-bin/token} 的那一条,被别的服务顶掉了 ——
 *           所以本实现用 {@code stable_token}</td></tr>
 * </table>
 *
 * 🔴 这些都<b>只进日志</b>。回给客户端的永远只是「微信授权失败,请重试」。
 */
public class WeChatException extends Exception {

    private final int errcode;

    public WeChatException(String message, int errcode) {
        super(message + " errcode=" + errcode);
        this.errcode = errcode;
    }

    public WeChatException(String message, Throwable cause) {
        super(message, cause);
        this.errcode = -1;
    }

    public int errcode() {
        return errcode;
    }
}
