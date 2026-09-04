package com.kaodian.server.api.support;

import com.kaodian.server.api.dto.common.ApiError;
import com.kaodian.server.api.dto.common.ErrorCode;
import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.kaodian.server.syllabus.SyllabusDataException;
import com.kaodian.server.syllabus.SyllabusEditException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 统一异常出口。所有端点的失败都从这里出去,形状是 {@link ApiError}。
 *
 * <h2>🔴 这里有一条与红线直接相关的纪律:不回声、不记录用户送来的原文</h2>
 *
 * docs/technical/INDEX.md §8.1 禁令 3 说的是「不把 base64 打进日志的任何级别」,但它背后的规则更宽:
 * <b>解析失败时最自然的写法是把 {@code ex.getMessage()} 原样返回或打进日志,而 Jackson 的
 * 解析错误消息里可能带着请求体片段</b>。请求体里本来不该有课程内容(DTO 里根本没有能装的字段),
 * 但「攻击者/误用者塞进来的东西会不会被我们落盘」这件事,不能靠对方守规矩。
 * <p>
 * 所以:解析类错误只回一句固定文案 + 字段名,{@code ex.getMessage()} 既不返回也不打日志。
 *
 * <h2>traceId 是用户和日志之间唯一的那根线</h2>
 *
 * 前端拿不到堆栈,也拿不到异常类名。出问题时用户能报的只有这一串,服务端凭它捞日志。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 回声字段名时的长度上限 —— 字段名是客户端送来的字符串,截断后再返回。 */
    private static final int MAX_ECHOED_FIELD_LENGTH = 40;

    /**
     * 接口层自己抛的,状态码与错误码都已想清楚。
     *
     * <p>🔴 <b>日志里只有 code,没有 message</b>。{@link ApiException} 的消息里嵌着用户送来的
     * 那个字符串(考点 code、模块名),而路径变量和查询参数没有任何长度上限 ——
     * 把 message 打进日志,等于给「往日志文件里写一整段题干」开了条最不起眼的路。
     * 消息该给的是<b>前端</b>(那里已经过 {@code ApiException.echo} 截断),不是磁盘。
     * 要定位具体是哪一次请求,靠 traceId。
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handle(ApiException ex) {
        String traceId = newTraceId();
        log.info("[{}] 请求被拒绝 code={}", traceId, ex.code());
        return ResponseEntity.status(ex.status()).body(new ApiError(ex.code(), ex.getMessage(), traceId));
    }

    /**
     * 骨架层编辑被拒绝(考点管理)。
     *
     * <p>转成 {@link ApiException} 再走上面那条出口,好处有两个:
     * 状态码与错误码的映射只写在 {@link ApiException#of} 一处;
     * 日志同样只有 {@code code},没有 message ——
     * 而这些 message 里嵌着<b>路径变量里的考点 code</b>,那是没有 {@code @Size} 管得着的用户输入。
     */
    @ExceptionHandler(SyllabusEditException.class)
    public ResponseEntity<ApiError> handle(SyllabusEditException ex) {
        return handle(ApiException.of(ex));
    }

    /** {@code @Valid} 的请求体校验失败。只回「字段名 + 规则文案」,不回被拒绝的值。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handle(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getAllErrors().stream()
                .map(e -> e instanceof FieldError fe
                        ? fe.getField() + ":" + fe.getDefaultMessage()
                        : String.valueOf(e.getDefaultMessage()))
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest("VALIDATION_FAILED", "请求参数不合法 —— " + detail);
    }

    /** 查询参数上的约束失败({@code @Min} / {@code @Max} 等)。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handle(HandlerMethodValidationException ex) {
        String detail = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> r.getMethodParameter().getParameterName() + ":" + e.getDefaultMessage()))
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest("VALIDATION_FAILED", "请求参数不合法 —— " + detail);
    }

    /**
     * 请求体读不出来。
     *
     * <h2>🔴 未定义字段一律拒绝,这是 R-07 在接口层的第二道锁</h2>
     *
     * 第一道锁是「DTO 里就没有 {@code name / label / tag} 这类字段」;
     * 但只有「没有」是不够的 —— 默认配置下 Jackson 会<b>安静地忽略</b>多余字段,
     * 于是前端传了 {@code {"tag":"自己想的考点"}} 也返回 200,双方都以为它生效了。
     * <b>接口上没有传入自由文本标签的通道,而且这件事会被明确告知调用方</b>(docs/technical/INDEX.md §6.3)。
     *
     * <p>这里认两种未定义字段的报错,因为这条线有两把独立的锁,谁先响都算数:
     * <ul>
     *   <li>{@link UnknownFieldException} —— DTO 上的 {@code @JsonAnySetter},<b>不依赖任何配置</b></li>
     *   <li>{@link UnrecognizedPropertyException} —— {@code FAIL_ON_UNKNOWN_PROPERTIES} 那行配置</li>
     * </ul>
     * 两支都留着,正是因为「一道锁失效不该导致整条线失守」这句话要能兑现。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handle(HttpMessageNotReadableException ex) {
        String unknownField = unknownFieldNameOf(ex);
        if (unknownField != null) {
            return badRequest("UNKNOWN_FIELD",
                    "请求体不接受未定义字段:" + truncate(unknownField)
                            + "。挂载只接受骨架树里已有的 nodeCode,不接受任何自由文本标签(R-07)。");
        }
        // 🔴 故意不带 ex.getMessage():Jackson 的解析错误消息里可能夹着请求体片段。
        return badRequest("MALFORMED_BODY", "请求体不是合法 JSON,或字段类型不对。");
    }

    /**
     * 沿着 cause 链找「是哪个未定义字段」。
     *
     * <p>要走链而不是只看 {@code getCause()},是因为 {@code @JsonAnySetter} 抛出的异常会被
     * Jackson 再包一层 {@code ValueInstantiationException} —— 只看一层就漏掉了那道锁,
     * 于是被 R-07 拦下的请求会退化成一句含糊的 {@code MALFORMED_BODY}。
     *
     * @return 字段名;不是「未定义字段」这类错误时返回 {@code null}
     */
    private static String unknownFieldNameOf(Throwable ex) {
        for (Throwable t = ex; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof UnknownFieldException ufe) {
                return ufe.fieldName();
            }
            if (t instanceof UnrecognizedPropertyException upe) {
                return upe.getPropertyName();
            }
        }
        return null;
    }

    /**
     * 领域层的参数校验 —— {@code Touch} / {@code Touch.Drill} 的构造器。
     *
     * <p>这些消息是我们自己写的中文(「对的题数不能多于练的题数」),原样回给前端是安全的,
     * 而且比接口层再抄一遍规则更好:<b>规则只写在领域对象上一处</b>。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handle(IllegalArgumentException ex) {
        return badRequest("INVALID_ARGUMENT", ex.getMessage());
    }

    /**
     * 兜底。
     *
     * <p>Spring MVC 自己抛的那批异常(404 找不到路由、405 方法不对、415 类型不对)都实现了
     * {@link ErrorResponse},带着正确的状态码。这里顺着它取状态码,而不是逐个 {@code @ExceptionHandler}
     * 列一遍 —— 列一遍的写法会随 Spring 版本漏掉新的异常类型,然后把一个 404 变成 500。
     */
    /**
     * 骨架数据文件本身不合法 —— 仍然是 5xx,但<b>说得出原因</b>。
     *
     * <p>兜底段之所以不回显原始消息,是怕泄漏类名、路径、堆栈。
     * {@link SyllabusDataException} 的消息里只有用户自己敲进去的 code 与名字,加一句中文修改建议,
     * 没有那个风险。而这句话正是用户唯一能照着动手的东西 ——
     * 本机单用户工具,让它退化成「请把 traceId 报给我们」等于没有提示。
     */
    @ExceptionHandler(SyllabusDataException.class)
    public ResponseEntity<ApiError> handle(SyllabusDataException ex) {
        String traceId = newTraceId();
        log.error("[{}] 骨架数据文件不合法", traceId, ex);          // 堆栈仍然只进日志
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("SYLLABUS_DATA_BROKEN", ex.getMessage(), traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handle(Exception ex) {
        String traceId = newTraceId();
        HttpStatusCode status = ex instanceof ErrorResponse er
                ? er.getStatusCode()
                : HttpStatus.INTERNAL_SERVER_ERROR;

        if (status.is5xxServerError()) {
            log.error("[{}] 未预期的服务端错误", traceId, ex);      // 堆栈只进日志
            return ResponseEntity.status(status)
                    // 🔴 契约 §10.2 统一叫 SERVER_ERROR。这是一次改名不是一次新增 ——
                    // 老名字 INTERNAL_ERROR 不在 ErrorCode 里,留着会让 §十 的双向比对判红。
                    .body(new ApiError(ErrorCode.SERVER_ERROR.name(), "服务器内部错误,请把 traceId 报给我们。", traceId));
        }
        log.info("[{}] 请求被 Spring MVC 拒绝 status={} type={}", traceId, status.value(), ex.getClass().getSimpleName());
        return ResponseEntity.status(status)
                .body(new ApiError("REQUEST_REJECTED", "请求无法处理:" + status.value(), traceId));
    }

    private ResponseEntity<ApiError> badRequest(String code, String message) {
        String traceId = newTraceId();
        log.info("[{}] 请求被拒绝 code={}", traceId, code);
        return ResponseEntity.badRequest().body(new ApiError(code, message, traceId));
    }

    private static String truncate(String s) {
        if (s == null) {
            return "(未知)";
        }
        return s.length() <= MAX_ECHOED_FIELD_LENGTH ? s : s.substring(0, MAX_ECHOED_FIELD_LENGTH) + "…";
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
