package com.kaodian.server.auth.vendor;

/**
 * 三条入口各自的 appid/secret。
 *
 * <p>做成一个显式的三元组,而不是三对散配置项,是为了让
 * <b>「这条入口没配」变成一个能在启动期检查出来的事实</b> ——
 * 而不是等到用户点了登录、拿到 {@code errcode 40125} 才发现。
 *
 * @param miniProgram     小程序
 * @param officialAccount 公众号(服务号)
 * @param website         开放平台网站应用
 */
public record WeChatCredentials(App miniProgram, App officialAccount, App website) {

    /**
     * @param appId  应用 id
     * @param secret 应用密钥。🔴 <b>只走环境变量,不进代码库</b>
     */
    public record App(String appId, String secret) {

        public boolean isConfigured() {
            return appId != null && !appId.isBlank() && secret != null && !secret.isBlank();
        }
    }

    public App of(WeChatEntry entry) {
        App app = switch (entry) {
            case MINI_PROGRAM -> miniProgram;
            case OFFICIAL_ACCOUNT_H5 -> officialAccount;
            case WEBSITE_QR -> website;
        };
        if (app == null || !app.isConfigured()) {
            // 说清楚缺的是哪一条 —— 三条入口是三个应用,这是接入时最容易混的一点。
            throw new IllegalStateException("微信入口未配置:" + entry.wireName()
                    + " —— 三条入口是三个不同的应用,各有各的 appid/secret");
        }
        return app;
    }
}
