package com.kaodian.server.auth;

/**
 * 账号状态。<b>只有两个取值,而且不打算有第三个。</b>
 *
 * <p>没有 {@code SUSPENDED}、没有 {@code PENDING}、没有 {@code LOCKED}:
 * 「待激活」在这个产品里不存在(docs/13 §1.7 注册即登录,不存在验证前的中间态),
 * 而封号需要一个运营团队,那是 01 §2.7「2-3 人独立业务、无销售团队」之外的东西。
 * <p>
 * <b>号码锁定不是账号状态</b> —— 它是验证码通道上的一个 30 分钟窗口,
 * 挂在手机号上而不是账号上(锁定发生时可能根本还没有账号)。见 {@link SmsCodeService}。
 */
public enum AccountStatus {
    ACTIVE,
    DEACTIVATED
}
