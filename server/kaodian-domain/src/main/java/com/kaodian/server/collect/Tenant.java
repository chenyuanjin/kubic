package com.kaodian.server.collect;

/**
 * 租户列的那一句校验 —— B0-3(`B0-平台底座与横切契约` §4.2 / §4.3)。
 *
 * <h2>🔴 领域层只校验 {@code > 0},不查「这个用户存不存在」</h2>
 *
 * §4.3 那张表把允许与不允许写死了:领域方法签名里出现一个用户标识参数是<b>允许</b>的;
 * 领域层自己去拿「当前用户」(那四个类型名写在 B0 §4.3 与 {@code 接口契约} §11.2 的禁词列里,
 * <b>这份源码里一处都不出现,包括注释</b> —— 判据 ② 是一行 grep,而一句「我们没有用它」
 * 会让那行 grep 自己命中自己),或者去查账号是不是真的存在,是<b>不允许</b>的 ——
 * 后者会把 {@code domain → auth} 那条边建出来,而它是四模块无环图里唯一还没被建出来的一条
 * ({@code kaodian-domain/pom.xml} 上的 enforcer 会在构建期把它拦下来)。
 *
 * <p>所以这里只有一句判断,而且判的是<b>形状</b>不是<b>存在性</b>:
 * {@code 0} 不是合法值(B0 §3.3,auth 侧从 10001 起号),负数更不是。
 * 「这个 id 背后有没有一个活着的账号」由鉴权那一侧回答 —— 请求走到领域层之前就已经答完了。
 */
final class Tenant {

    private Tenant() {
    }

    /**
     * @throws IllegalArgumentException {@code userId <= 0}
     */
    static long requireUserId(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException(
                    "userId 必须是正数,拿到的是 " + userId
                            + " —— 0 不是「暂时没有用户」的意思,它根本不是一个合法用户(B0 §3.3)");
        }
        return userId;
    }
}
