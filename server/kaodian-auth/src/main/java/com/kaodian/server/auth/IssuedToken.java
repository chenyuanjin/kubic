package com.kaodian.server.auth;

/**
 * 刚签发出来的令牌 —— <b>唯一一次能拿到明文的地方</b>。
 *
 * <p>它不进任何存储,只在 {@link TokenService#issue} 的返回值里活一趟,
 * 由接口层放进响应体,然后被 GC 掉。
 *
 * <p>🔴 <b>不要给这个 record 写 {@code toString()} 之外的日志用法,也不要 log 它。</b>
 * 下面那个 {@code toString()} 覆写就是为此存在的:某天有人写下
 * {@code log.debug("issued {}", token)},打出来的也只是掩码。
 */
public record IssuedToken(String plaintext, AccessToken stored) {

    /** 明文令牌只在响应体里出现一次。日志里永远只有这个形状。 */
    @Override
    public String toString() {
        return "IssuedToken[" + stored.scope().prefix() + "…, user=" + stored.userId() + "]";
    }
}
