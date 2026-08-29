package com.kaodian.server.api.dto;

/**
 * 让前端跳过去的授权地址,以及配套的一次性 {@code state}。
 *
 * <p>URL 由<b>服务端</b>拼,不是前端拼。理由只有一个但足够:{@code state} 必须由服务端生成、
 * 服务端记住、服务端校验 —— 前端自己生成的 state 服务端无从验证,那就等于没有 state。
 */
public record WeChatAuthorizeUrlResponse(String url, String state) {
}
