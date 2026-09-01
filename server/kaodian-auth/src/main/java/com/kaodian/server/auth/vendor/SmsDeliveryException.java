package com.kaodian.server.auth.vendor;

/**
 * 短信没发出去。
 *
 * <h2>{@link #definitelyNotCharged} 决定要不要把用户的日额度还回去</h2>
 *
 * 两种失败的处置完全不同:
 *
 * <table border="1">
 *   <caption>失败的两类</caption>
 *   <tr><th></th><th>例子</th><th>处置</th></tr>
 *   <tr><td><b>确定没发、确定没扣费</b></td>
 *       <td>签名未报备、模板未审核、余额不足、号码格式被运营商拒</td>
 *       <td>把日额度还回去 —— <b>我们自己的配置错误不该吃掉用户 10 条/日 里的一条</b></td></tr>
 *   <tr><td><b>不确定</b></td>
 *       <td>请求超时、连接被重置、供应商回了个没见过的码</td>
 *       <td>额度<b>不还</b> —— 短信可能已经在路上了,还回去等于允许再发一条</td></tr>
 * </table>
 *
 * <b>「不确定」一律按「已发生」处理</b>,与 docs/technical/后端系统设计与组件接入.md §1.10 订单那条「确认中不能就近归到失败」
 * 是同一条推理:两个方向的错误代价不对称,就朝代价小的那边倒。
 */
public class SmsDeliveryException extends Exception {

    private final boolean definitelyNotCharged;
    private final String vendorCode;

    public SmsDeliveryException(String message, String vendorCode, boolean definitelyNotCharged) {
        super(message);
        this.vendorCode = vendorCode;
        this.definitelyNotCharged = definitelyNotCharged;
    }

    public SmsDeliveryException(String message, Throwable cause) {
        super(message, cause);
        this.vendorCode = null;
        // 抛异常的传输层失败:短信可能已经发出去了。默认按「已发生」算。
        this.definitelyNotCharged = false;
    }

    public boolean definitelyNotCharged() {
        return definitelyNotCharged;
    }

    /** 供应商返回的错误码,用于运维定位。<b>不回给客户端</b> —— 那是我们和供应商之间的事。 */
    public String vendorCode() {
        return vendorCode;
    }
}
