package com.kaodian.server.auth.vendor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * {@link WeChatClient} 的真实实现 —— 纯 JDK {@link HttpClient},没有微信 SDK。
 *
 * <p>微信的这几个接口都是明文 HTTP GET/POST + JSON,没有签名、没有加密
 * (小程序的 {@code session_key} 解密那一套<b>本产品用不到</b> —— 见下)。
 * 引一个 SDK 换来的只是几个 URL 拼接。
 *
 * <h2>🔴 {@code session_key} 拿到了也不存</h2>
 *
 * {@code code2Session} 会返回 {@code session_key},它是解密用户加密数据的密钥。
 * 官方文档写着「开发者服务器不应该把会话密钥下发到小程序,也不应该对外提供」。
 * <b>本实现更进一步:根本不留它。</b>
 * <p>
 * 因为要它只有一个用途 —— 解开 {@code encryptedData} 拿昵称头像手机号,
 * 而昵称头像我们不要({@link WeChatIdentity}),手机号走的是新版
 * {@code phonenumber.getPhoneNumber}(不需要 session_key)。
 * <b>不存的东西不会泄露</b>,与「不建能装题干的列」是同一条。
 *
 * <h2>access_token 用 stable_token,不用 cgi-bin/token</h2>
 *
 * 传统的 {@code /cgi-bin/token} 全局只有一条,<b>谁后调谁把前一条顶掉</b> ——
 * 于是本机调试一次就能把线上踢下线,表现为随机的 {@code errcode 40001}。
 * {@code /cgi-bin/stable_token} 与它完全隔离,普通模式下重复调用不刷新,
 * 是多实例部署下唯一正确的那个。
 */
public class HttpWeChatClient implements WeChatClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWeChatClient.class);

    private static final String API = "https://api.weixin.qq.com";
    private static final String OPEN = "https://open.weixin.qq.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 官方给的有效期是 7200 秒。提前 5 分钟换,避开「正好在过期那一秒调用」。 */
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofMinutes(5);

    private final WeChatCredentials credentials;

    /** 🔴 拿不到 unionid 时失败而不是降级 —— 见 {@link UnionIdMissingException}。 */
    private final boolean requireUnionId;
    private final HttpClient http;

    private final Object tokenLock = new Object();
    private String cachedToken;

    /** 「该去换新的了」的时刻 = 真实过期时刻 − 安全余量。 */
    private Instant cachedTokenRefreshAt;

    /**
     * 微信那边<b>真正</b>失效的时刻。
     *
     * <p>它和 {@link #cachedTokenRefreshAt} 差一个安全余量,而那段差值正是这里存在的理由:
     * 在那 5 分钟里我们已经开始尝试换新的,但<b>手里这条其实还能用</b>。
     * 换新的失败时回退到它,比直接把整条微信链路判死好得多。
     */
    private Instant cachedTokenExpiresAt;

    /**
     * @param requireUnionId 拿不到 unionid 时是否<b>直接失败</b>而不是降级用 openid。
     *                       已经有开放平台账号时应当为 {@code true} —— 绑定做对了 unionid 必然存在,
     *                       拿不到就是配置坏了。见 {@link UnionIdMissingException}
     */
    public HttpWeChatClient(boolean requireUnionId, WeChatCredentials credentials) {
        this.credentials = credentials;
        this.requireUnionId = requireUnionId;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String buildAuthorizeUrl(WeChatEntry entry, String redirectUri, String state) {
        WeChatCredentials.App app = credentials.of(entry);
        String encoded = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
        return switch (entry) {
            case MINI_PROGRAM -> throw new IllegalArgumentException(
                    "小程序没有授权 URL —— 它走 wx.login 直接拿 code");
            // 🔴 结尾那个 #wechat_redirect 不能省。少了它,在微信内置浏览器里
            // 用户看到的是一个空白页,而不是授权页 —— 而且没有任何错误提示。
            case OFFICIAL_ACCOUNT_H5 -> OPEN + "/connect/oauth2/authorize"
                    + "?appid=" + app.appId()
                    + "&redirect_uri=" + encoded
                    + "&response_type=code"
                    // snsapi_base 是静默授权但【拿不到 unionid】,而 unionid 才是跨端锚点
                    + "&scope=snsapi_userinfo"
                    + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
                    + "#wechat_redirect";
            case WEBSITE_QR -> OPEN + "/connect/qrconnect"
                    + "?appid=" + app.appId()
                    + "&redirect_uri=" + encoded
                    + "&response_type=code"
                    + "&scope=snsapi_login"
                    + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
                    + "#wechat_redirect";
        };
    }

    @Override
    public WeChatIdentity exchangeMiniProgramCode(String jsCode) throws WeChatException {
        WeChatCredentials.App app = credentials.of(WeChatEntry.MINI_PROGRAM);
        JsonNode n = getJson(API + "/sns/jscode2session"
                + "?appid=" + app.appId()
                + "&secret=" + app.secret()
                + "&js_code=" + enc(jsCode)
                + "&grant_type=authorization_code");
        // session_key 就在 n 里,这里【看都不看】—— 见类注释。
        return checked(identityOf(n), WeChatEntry.MINI_PROGRAM);
    }

    @Override
    public WeChatIdentity exchangeOAuthCode(WeChatEntry entry, String code) throws WeChatException {
        if (entry == WeChatEntry.MINI_PROGRAM) {
            throw new IllegalArgumentException("小程序走 exchangeMiniProgramCode");
        }
        WeChatCredentials.App app = credentials.of(entry);
        JsonNode n = getJson(API + "/sns/oauth2/access_token"
                + "?appid=" + app.appId()
                + "&secret=" + app.secret()
                + "&code=" + enc(code)
                + "&grant_type=authorization_code");

        String openid = n.path("openid").asString("");
        if (openid.isEmpty()) {
            // WeChatIdentity 的构造器会对空 openid 抛 IllegalArgumentException —— 那是个
            // 未受检异常,会逃成 500。这里先转成受控的 WeChatException(上层回 502)。
            throw new WeChatException("微信没有返回 openid", n.path("errcode").asInt(-1));
        }
        String unionid = n.path("unionid").asString("");
        if (!unionid.isEmpty()) {
            return new WeChatIdentity(openid, unionid);      // 有 unionid,无需再判
        }

        // 这一步只为了补 unionid。拿回来的昵称头像一概丢弃(见 WeChatIdentity)。
        // 补不到就带着空 unionid 回去 —— 由上层决定怎么处理,不在这里静默降级。
        String userToken = n.path("access_token").asString("");
        if (!userToken.isEmpty() && !openid.isEmpty()) {
            try {
                JsonNode info = getJson(API + "/sns/userinfo"
                        + "?access_token=" + enc(userToken)
                        + "&openid=" + enc(openid)
                        + "&lang=zh_CN");
                unionid = info.path("unionid").asString("");
            } catch (WeChatException e) {
                log.warn("补取 unionid 失败,继续用 openid errcode={}", e.errcode());
            }
        }
        return checked(new WeChatIdentity(openid, unionid), entry);
    }

    @Override
    public String exchangePhoneCode(String phoneCode) throws WeChatException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("code", phoneCode);
        JsonNode n = postJson(API + "/wxa/business/getuserphonenumber?access_token=" + enc(stableToken()),
                body.toString());
        String pure = n.path("phone_info").path("purePhoneNumber").asString("");
        if (pure.isEmpty()) {
            throw new WeChatException("微信没有返回手机号", n.path("errcode").asInt(-1));
        }
        return pure;
    }

    @Override
    public boolean isReal() {
        return true;
    }

    /**
     * 🔴 没有 unionid 时按配置决定:直接失败,还是降级用 openid。
     *
     * <p>降级不是「更宽容」,是<b>把一个配置错误变成一批将来要修的脏数据</b> ——
     * 那批账号会走 {@code R-63} 的自愈路径,能修,但本来不该发生。
     */
    private WeChatIdentity checked(WeChatIdentity id, WeChatEntry entry) throws WeChatException {
        if (id.hasUnionId()) {
            return id;
        }
        if (requireUnionId) {
            throw new UnionIdMissingException(entry);
        }
        log.warn("这条微信身份没有 unionid(入口={}) —— 该应用未绑定到微信开放平台账号。"
                + "后果是同一个人从不同入口进会得到两个账号(R-33 / R-63)", entry.wireName());
        return id;
    }

    // —— access_token ——

    /**
     * 稳定版 access_token,带进程内缓存。
     *
     * <p>多实例部署时这个缓存各算各的,但那正是 {@code stable_token} 的设计意图:
     * 普通模式下重复调用<b>不刷新、不互相顶掉</b>,所以各实例各持一份是安全的。
     * 换成 {@code /cgi-bin/token} 的话,这段代码就必须换成一个共享缓存。
     */
    private String stableToken() throws WeChatException {
        synchronized (tokenLock) {
            Instant now = Instant.now();
            if (cachedToken != null && cachedTokenRefreshAt != null && now.isBefore(cachedTokenRefreshAt)) {
                return cachedToken;
            }
            WeChatCredentials.App app = credentials.of(WeChatEntry.MINI_PROGRAM);
            ObjectNode body = MAPPER.createObjectNode();
            body.put("grant_type", "client_credential");
            body.put("appid", app.appId());
            body.put("secret", app.secret());
            body.put("force_refresh", false);
            JsonNode n;
            try {
                n = postJson(API + "/cgi-bin/stable_token", body.toString());
            } catch (WeChatException e) {
                // 🔴 换新的失败了 —— 但如果手里那条还没真正过期,就继续用它。
                // 直接抛的话:提前 5 分钟开始换,而这 5 分钟里网络抖一下,
                // 整条微信链路就在【令牌其实还有效】的情况下被判死。
                if (cachedToken != null && cachedTokenExpiresAt != null
                        && now.isBefore(cachedTokenExpiresAt)) {
                    log.warn("stable_token 刷新失败,回退到尚未真正过期的缓存(还剩 {} 秒) errcode={}",
                            java.time.Duration.between(now, cachedTokenExpiresAt).toSeconds(), e.errcode());
                    return cachedToken;
                }
                throw e;
            }
            String token = n.path("access_token").asString("");
            if (token.isEmpty()) {
                throw new WeChatException("拿不到 access_token", n.path("errcode").asInt(-1));
            }
            cachedToken = token;
            cachedTokenExpiresAt = now.plusSeconds(n.path("expires_in").asInt(7200));
            cachedTokenRefreshAt = cachedTokenExpiresAt.minus(TOKEN_SAFETY_MARGIN);
            return token;
        }
    }

    // —— HTTP ——

    private WeChatIdentity identityOf(JsonNode n) throws WeChatException {
        String openid = n.path("openid").asString("");
        if (openid.isEmpty()) {
            throw new WeChatException("微信没有返回 openid", n.path("errcode").asInt(-1));
        }
        return new WeChatIdentity(openid, n.path("unionid").asString(""));
    }

    private JsonNode getJson(String url) throws WeChatException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    private JsonNode postJson(String url, String body) throws WeChatException {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    private JsonNode send(HttpRequest.Builder b) throws WeChatException {
        HttpResponse<String> resp;
        try {
            resp = http.send(b.timeout(Duration.ofSeconds(8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new WeChatException("微信接口调用失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeChatException("微信接口调用被中断", e);
        }
        // 🔴 同上:微信侧也可能返回非 JSON(网关页)。裸抛会逃成 500。
        JsonNode n;
        try {
            n = MAPPER.readTree(resp.body());
        } catch (RuntimeException e) {
            throw new WeChatException("微信响应不是合法 JSON(HTTP " + resp.statusCode() + ")", e);
        }
        // 🔴 微信的失败一律是 HTTP 200 + body 里的 errcode。只看状态码 = 把每一次失败都当成功。
        int errcode = n.path("errcode").asInt(0);
        if (errcode != 0) {
            throw new WeChatException("微信接口返回错误", errcode);
        }
        return n;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
