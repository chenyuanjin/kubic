package com.kaodian.server.auth.vendor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 腾讯云 API 3.0 的 TC3-HMAC-SHA256 签名与调用 —— <b>手写,不引 SDK</b>。
 *
 * <h2>为什么不用官方 SDK</h2>
 *
 * {@code com.tencentcloudapi:tencentcloud-sdk-java-sms} 会通过
 * {@code tencentcloud-sdk-java-common} 传递进来 <b>okhttp3(带 Kotlin 运行时)、gson、
 * commons-configuration2、commons-logging</b> 共五个第三方库 —— 而 {@code server/} 到目前为止
 * 除 Spring Boot 之外<b>一个第三方依赖都没有</b>。
 * <p>
 * 换来的是什么:两个接口({@code SendSms} / {@code DescribeCaptchaResult})的一次 POST。
 * 签名算法本身是公开且稳定的,整段实现不到一百行,而且<b>短信与验证码两个产品共用同一段</b>。
 * <p>
 * 这与 docs/13 §三 拒绝 {@code spring-ai-alibaba} 里程碑版是同一类判断:
 * <b>为一次 HTTP 调用背一整棵依赖树,代价不在今天,在它哪天和别的东西冲突的那天。</b>
 *
 * <h2>🔴 Host 头不显式设置</h2>
 *
 * 它参与签名,但 JDK {@link HttpClient} 把 {@code Host} 列为受限头、不允许应用设置;
 * 它会<b>自己按 URI 填</b>。所以这里签的是我们已知的那个域名,发的是 HttpClient 填的同一个 ——
 * 显式 {@code .header("Host", ...)} 会直接抛 {@link IllegalArgumentException}。
 */
public final class TencentCloudApi {

    private static final String ALGORITHM = "TC3-HMAC-SHA256";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final DateTimeFormatter UTC_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String secretId;
    private final String secretKey;
    private final HttpClient http;

    public TencentCloudApi(String secretId, String secretKey, Duration timeout) {
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * 调一个接口,返回 {@code Response} 节点。
     *
     * @param host    如 {@code sms.tencentcloudapi.com}
     * @param service 如 {@code sms}。<b>必须与 host 的第一段一致</b>,否则签名算得出来但服务端拒
     * @param region  地域;验证码等全局服务传空串
     * @throws VendorCallException 网络失败,或腾讯云回了 {@code Error}
     */
    public JsonNode call(String host, String service, String action, String version,
                         String region, String jsonBody) throws VendorCallException {
        long timestamp = Instant.now().getEpochSecond();
        String date = UTC_DATE.format(Instant.ofEpochSecond(timestamp));
        String lowerAction = action.toLowerCase(java.util.Locale.ROOT);

        // ① 规范请求串
        String canonicalHeaders =
                "content-type:" + CONTENT_TYPE + "\n"
                        + "host:" + host + "\n"
                        + "x-tc-action:" + lowerAction + "\n";
        String signedHeaders = "content-type;host;x-tc-action";
        String canonicalRequest = String.join("\n",
                "POST",
                "/",
                "",                                  // 查询串:POST + JSON 时为空
                canonicalHeaders,
                signedHeaders,
                sha256Hex(jsonBody));

        // ② 待签字符串
        String credentialScope = date + "/" + service + "/tc3_request";
        String stringToSign = String.join("\n",
                ALGORITHM, String.valueOf(timestamp), credentialScope, sha256Hex(canonicalRequest));

        // ③ 派生签名密钥 —— 逐级 HMAC,每一级都把上一级的输出当密钥
        byte[] secretDate = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, service);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));

        String authorization = ALGORITHM
                + " Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://" + host + "/"))
                .header("Authorization", authorization)
                .header("Content-Type", CONTENT_TYPE)
                .header("X-TC-Action", action)
                .header("X-TC-Timestamp", String.valueOf(timestamp))
                .header("X-TC-Version", version)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (region != null && !region.isBlank()) {
            b.header("X-TC-Region", region);
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new VendorCallException("腾讯云调用失败:" + action, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VendorCallException("腾讯云调用被中断:" + action, e);
        }

        // 🔴 网关超时页 / 代理错误页是 HTML,不是 JSON。裸调 readTree 会抛
        // Jackson 的未受检异常,一路逃成 500 —— 而调用方(SmsCodeService)只认
        // SmsDeliveryException,于是既不退额度也判不出「到底发没发出去」。
        // 包成 VendorCallException 之后它落进「不确定」那一档,与超时同待遇。
        JsonNode root;
        try {
            root = MAPPER.readTree(resp.body()).path("Response");
        } catch (RuntimeException e) {
            throw new VendorCallException(
                    action + " 的响应不是合法 JSON(HTTP " + resp.statusCode() + ")", e);
        }
        JsonNode error = root.path("Error");
        if (!error.isMissingNode() && !error.isNull()) {
            // 🔴 只带错误码,不带 Message —— Message 里可能夹着我们发过去的手机号。
            throw new VendorCallException(action + " 被拒绝",
                    error.path("Code").asString("Unknown"),
                    root.path("RequestId").asString(""));
        }
        return root;
    }

    /**
     * 时间戳与服务端相差超过 5 分钟会得到签名过期错误。
     *
     * <p>写在这里是因为这个错误的表现是「昨天还好好的,今天全部 401」——
     * 而真正的原因往往是那台机器的 NTP 停了,和代码无关。
     */
    public static String hintForClockSkew() {
        return "若持续出现签名过期,先检查本机时间与 NTP —— 腾讯云允许的偏差是 5 分钟";
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static String json(String... kv) {
        var o = MAPPER.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            o.put(kv[i], kv[i + 1]);
        }
        return o.toString();
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 供应商侧的调用失败。<b>错误码进日志,不进响应体。</b> */
    public static class VendorCallException extends Exception {

        private final String code;
        private final String requestId;

        VendorCallException(String message, Throwable cause) {
            super(message, cause);
            this.code = null;
            this.requestId = null;
        }

        VendorCallException(String message, String code, String requestId) {
            super(message + " code=" + code + " requestId=" + requestId);
            this.code = code;
            this.requestId = requestId;
        }

        public String code() {
            return code;
        }

        public String requestId() {
            return requestId;
        }
    }
}
