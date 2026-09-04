package com.kaodian.server.api.dto.common;

import org.springframework.http.HttpStatus;

/**
 * {@code ApiError.code} 的取值全集 —— {@code 接口契约-签名与错误码全集} §十 的代码投影。
 *
 * <h2>为什么是枚举而不是散落的 String 常量</h2>
 *
 * 「端上不许出现 §十 之外的码」这句话,只有在码是一个<b>封闭类型</b>时才测得了:
 * 散落的字符串常量 grep 得出「有哪些」,grep 不出<b>「少了哪一个」</b>。
 * 双向比对的那条判据(契约有代码无 → 红;代码有契约无 → 红)写在
 * {@code ErrorCodeContractTest} 里,文档是真源,这份枚举跟着。
 *
 * <h2>HTTP 状态挂在这里,不挂在 handler 里</h2>
 *
 * {@code 接口契约} §1.3 把状态收窄到一张小表,而<b>收窄只有在一处决定时才守得住</b>。
 * 状态散在各个 {@code ApiException} 工厂方法里的写法,下一个新码就会随手挑一个。
 *
 * <h2>🔴 两个没有固定状态码的成员</h2>
 *
 * {@link #REQUEST_REJECTED} 的 HTTP 列是「透传」——容器/Spring MVC 自己定的状态原样出去;
 * {@link #MISSING_CLIENT_TOKEN} 的 HTTP 列是「—」——它只出现在 {@code POST /records/batch}
 * 的<b>条目级</b> {@code error} 里,整批恒 {@code 200},它从不作为响应状态。
 * 这两个的 {@link #status} 是 {@code 0},{@link #httpStatus()} <b>抛异常而不是回一个 500</b>:
 * 给它们编一个状态码,就是把「这里本来不该问状态」这件事悄悄抹掉,
 * 而抹掉之后 {@code REQUEST_REJECTED} 会把一个 404 变成 500。
 *
 * <p>🔴 <b>不要在任何地方写死「一共几个码」</b>。写死总数它就会和 §十 分叉 ——
 * §12.10 写「8 个」而 §10.8 只列了 7 个,就是这么来的。比对测试每次自己数。
 */
public enum ErrorCode {

    // ——————————————————— §10.2 通用 ———————————————————
    UNAUTHORIZED(401),
    TOKEN_EXPIRED(401),
    ACCOUNT_DEACTIVATED(401),
    READONLY_TOKEN(403),
    VALIDATION_FAILED(400),
    UNKNOWN_FIELD(400),
    MALFORMED_BODY(400),
    INVALID_ARGUMENT(400),
    INVALID_CURSOR(400),
    INVALID_LIMIT(400),
    IDEMPOTENCY_KEY_REQUIRED(400),
    IN_PROGRESS(409),
    SERVER_ERROR(500),
    SYLLABUS_DATA_BROKEN(500),
    /** 「透传」—— 状态由容器决定,见类注释。 */
    REQUEST_REJECTED(0),

    // ——————————————————— §10.3 骨架与查询 ———————————————————
    NODE_NOT_FOUND(404),
    NODE_ARCHIVED(409),
    NODE_NOT_IN_SYLLABUS(400),
    SUBJECT_NOT_LOADED(404),
    SYLLABUS_EMPTY(422),
    UNKNOWN_ORDER_BY(422),

    // ——————————————————— §10.4 记录与打标 ———————————————————
    RECORD_NOT_FOUND(404),
    TAG_NOT_FOUND(404),
    /** 「—」—— 只在批量补传的条目级 error 里,从不作为响应状态,见类注释。 */
    MISSING_CLIENT_TOKEN(0),
    MISSING_NODE_CODE(400),
    NO_MATCH_AND_NO_USER_NODE(422),
    RECOGNIZER_UNAVAILABLE(503),
    UNSUPPORTED_IMAGE_FORMAT(400),
    MISSING_AUDIO(400),
    AUDIO_TOO_LARGE(413),
    AUDIO_TOO_LONG(413),
    UNSUPPORTED_AUDIO_FORMAT(415),

    // ——————————————————— §10.5 鉴权与账号 ———————————————————
    BAD_PHONE(400),
    WRONG_PURPOSE(400),
    BAD_PURPOSE(400),
    CAPTCHA_FAILED(400),
    CAPTCHA_REQUIRED(403),
    SMS_TOO_FREQUENT(429),
    SMS_PHONE_DAILY_LIMIT(429),
    SMS_IP_DAILY_LIMIT(429),
    PHONE_LOCKED(429),
    SMS_SEND_FAILED(502),
    CODE_WRONG(400),
    CODE_EXPIRED(400),
    CODE_SUPERSEDED(400),
    CODE_NONE(400),
    BIND_REFUSED(409),
    MERGE_TOKEN_INVALID(409),
    ACCOUNT_NOT_FOUND(404),
    NOT_YOUR_SESSION(403),
    AGREEMENT_VERSION_STALE(409),
    WECHAT_ENTRY_INVALID(400),
    WECHAT_STATE_INVALID(400),
    WECHAT_AUTHORIZE_BUSY(429),
    WECHAT_UNIONID_MISSING(503),
    WECHAT_EXCHANGE_FAILED(502),
    WECHAT_NOT_ENABLED(503),
    WECHAT_PHONE_TOO_FREQUENT(429),
    WECHAT_PHONE_DAILY_LIMIT(429),
    WECHAT_PHONE_IP_LIMIT(429),
    WECHAT_PHONE_FAILED(502),

    // ——————————————————— §10.6 导出、AI 与令牌 ———————————————————
    UNKNOWN_EXPORT_FORMAT(400),
    UNKNOWN_GRANULARITY(400),
    AI_TEXT_TOO_LONG(413),
    SESSION_EXPIRED(409),
    SESSION_NOT_FOUND(404),
    TITLE_REQUIRED(400),
    TITLE_TOO_LONG(400),
    REVOKE_ALL_FAILED(500),
    MESSAGE_REQUIRED(400),
    MESSAGE_TOO_LONG(400),
    IMAGE_TOO_MANY(400),
    UNKNOWN_CONTEXT_SOURCE(422),
    CONTEXT_ALREADY_BOUND(409),
    EXAM_DATE_OUT_OF_RANGE(400),
    SESSION_TURN_LIMIT(409),
    EXPORT_JOB_NOT_FOUND(404),
    TOKEN_LIMIT_REACHED(409),

    // ——————————————————— §10.7 商业化 ———————————————————
    QUOTA_EXHAUSTED(403),
    ORDER_NOT_FOUND(404),
    ORDER_ALREADY_PAID(409),
    RECEIPT_INVALID(422),
    ORDER_NOT_CLOSEABLE(409),
    PLAN_NOT_PURCHASABLE(422),
    CHANNEL_UNAVAILABLE(422),
    ;

    /**
     * 「无固定状态码」的哨兵。见类注释:{@code 透传} 与 {@code —} 两档。
     *
     * <p>上面两个成员的实参写的是字面量 {@code 0} 而不是这个名字 —— 枚举常量的实参里
     * 引用本类后声明的字段是<b>非法前向引用</b>,编译期直接报错(实测)。
     */
    public static final int NO_FIXED_STATUS = 0;

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    /** 契约 §十 第二列的原值;{@code 0} 表示「无固定状态码」。比对测试直接读它。 */
    public int status() {
        return status;
    }

    /**
     * @throws IllegalStateException 这个码在契约里没有固定状态码({@code 透传} / {@code —}),
     *                               问它要一个状态本身就是用错了地方 —— 见类注释
     */
    public HttpStatus httpStatus() {
        if (status == NO_FIXED_STATUS) {
            throw new IllegalStateException(name()
                    + " 在契约 §十 里没有固定状态码:REQUEST_REJECTED 透传容器给的状态,"
                    + "MISSING_CLIENT_TOKEN 只出现在 POST /records/batch 的条目级 error 里(整批恒 200)。"
                    + "调用方应当自己给出状态,而不是从这里取一个。");
        }
        return HttpStatus.valueOf(status);
    }
}
