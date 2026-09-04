package com.kaodian.server.api.support;

import com.kaodian.server.api.dto.common.ErrorCode;
import com.kaodian.server.syllabus.SyllabusEditException;
import org.springframework.http.HttpStatus;

/**
 * 接口层自己抛的、状态码与错误码都已经想清楚的异常。
 *
 * <p>与直接抛 {@link IllegalArgumentException} 的区别是:这里的每一次抛出都<b>指定了对外的
 * {@code code}</b>,也就是承认「这是一个前端需要分支处理的情况」。领域层抛出的
 * {@code IllegalArgumentException}(比如 {@code Touch.Drill} 的「对的题数不能多于练的题数」)
 * 由 {@link ApiExceptionHandler} 兜底成 400,不需要在这里重复一遍。
 *
 * <h2>🔴 回声一律经 {@link #echo} 截断</h2>
 *
 * 这些消息里唯一的变量就是<b>用户送来的那个字符串</b>。它会进两个地方:响应体,和服务端日志。
 * 后者才是问题 —— {@code nodeCode} 在请求体上有 {@code @Size} 兜着,但
 * <b>路径变量与查询参数没有任何长度上限</b>,能塞满一整个请求行(Tomcat 默认约 8KB)。
 * 于是「查一个不存在的考点」这条最不起眼的路径,就成了把一整段题干写进日志文件的通道 ——
 * 而不往磁盘上落用户送来的原文,正是 {@link ApiExceptionHandler} 开头那条纪律。
 * <p>
 * 所以每一个工厂方法都必须把用户输入过一遍 {@link #echo}:<b>回声要留(不然报错没法定位),
 * 但长度由我们说了算</b>。
 */
public class ApiException extends RuntimeException {

    /**
     * 回声长度上限。
     *
     * <p>取 64 是跟着 {@code CreateRecordRequest.nodeCode} 的 {@code @Size(max = 64)} 走的 ——
     * 一个合法的考点 code 永远短于它,所以正常报错一个字都不会被截;
     * 会被截的都是本来就不该出现在这里的东西。
     */
    private static final int MAX_ECHOED_VALUE_LENGTH = 64;

    private final HttpStatus status;
    private final String code;

    /**
     * 🔴 <b>新代码用这一个</b> —— 状态码从 {@link ErrorCode} 取,不在这里重新决定一次。
     *
     * <p>状态散在各个工厂方法里的写法,下一个新码就会随手挑一个,而
     * {@code 接口契约} §1.3 把状态收窄到一张小表 —— <b>收窄只有在一处决定时才守得住</b>。
     *
     * @throws IllegalStateException 码是 {@code REQUEST_REJECTED} / {@code MISSING_CLIENT_TOKEN}
     *                               这两个「无固定状态码」的成员时。它们本来就不该从这里出去,
     *                               理由见 {@link ErrorCode} 类注释
     */
    public ApiException(ErrorCode code, String message) {
        super(message);
        this.status = code.httpStatus();
        this.code = code.name();
    }

    /**
     * @deprecated 用 {@link #ApiException(ErrorCode, String)}。留着只是为了不必一次改完所有调用点 ——
     *             这个构造器让状态码与错误码在<b>每一个调用点</b>各决定一次,
     *             正是 {@link ErrorCode} 要收掉的那件事。
     */
    @Deprecated
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 考点 code 不在骨架树里。<b>R-07 在接口层的拒绝点</b> —— 挂不上就是挂不上,不猜最接近的。 */
    public static ApiException nodeNotInSyllabus(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, "NODE_NOT_IN_SYLLABUS",
                "考点 code 不在骨架树里:" + echo(code) + "。只能挂到树里已有的考点上,不能新建。");
    }

    /** 查一个不存在的考点。与上面的区别只是语义:查是 404,写是 400。 */
    public static ApiException nodeNotFound(String code) {
        return new ApiException(HttpStatus.NOT_FOUND, "NODE_NOT_FOUND",
                "找不到这个考点:" + echo(code));
    }

    /**
     * 请求的模块不是当前载入的那个。
     *
     * <p>做成工厂方法而不是在控制器里拼字符串,是因为<b>拼字符串的那一版会忘了截断</b> ——
     * {@code subject} 是查询参数,没有 {@code @Size} 管得着它。
     *
     * @param requested 用户要的,<b>用户输入,必须过 {@link #echo}</b>
     * @param current   当前载入的模块 code,服务端自己的值
     */
    public static ApiException subjectNotLoaded(String requested, String current) {
        return new ApiException(HttpStatus.NOT_FOUND, "SUBJECT_NOT_LOADED",
                "当前只载入了一个模块:" + current + "。请求的 " + echo(requested) + " 不存在。");
    }

    /**
     * 骨架层编辑被拒 → HTTP。
     *
     * <h2>🔴 状态码必须分得开,不能一律 400</h2>
     *
     * 这四类拒绝在界面上要说的下一步完全不同:
     * <ul>
     *   <li><b>404</b> 找不到那个 code —— 刷新一下,树可能已经变了</li>
     *   <li><b>409 {@code NODE_HAS_RECORDS}</b> —— 上面挂着 N 条记录。
     *       <b>这是删除守则的出口</b>,界面要在这里给出「搬记录」和「归档」两个按钮,
     *       而不是一句「删除失败」</li>
     *   <li><b>409 {@code GROUP_NOT_EMPTY}</b> —— 先把考点处理掉</li>
     *   <li><b>409 {@code NODE_ARCHIVED}</b> —— 目标考点<b>在</b>树里,只是归档了。
     *       它必须与 404 分开:404 那句「刷新一下,树可能已经变了」在这里是错的指路,
     *       而且它与 {@code GET /api/v1/syllabus/archived} 刚列出过这个考点直接矛盾</li>
     *   <li><b>409 {@code NAME_TAKEN}</b> —— 名字被树上另一个考点/题型占着。
     *       与 400 {@code INVALID_NAME} 分开:那是「这个名字本身不能用,改一个」,
     *       这是「名字没问题,只是已经有人叫了」—— 界面要说的是<b>被谁占着</b>,
     *       而占名字的那个还可能是<b>已归档、用户在树上看不见</b>的考点</li>
     *   <li><b>400</b> 名字/频次/顺序不合法 —— 改了再来</li>
     * </ul>
     * 合并成一个 400,前端就只能显示服务端那句中文,再也做不了分支 ——
     * 而「有记录不让删」这条如果只落成一句提示,用户下一步就是去别处找个更硬的删法。
     *
     * <p>消息直接用 {@code ex.getMessage()}:{@link SyllabusEditException} 的每个工厂方法
     * 都已经把用户输入过了一遍截断,这里不必也不该再拼一次。
     */
    public static ApiException of(SyllabusEditException ex) {
        HttpStatus status = switch (ex.reason()) {
            case NODE_NOT_FOUND, GROUP_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NODE_HAS_RECORDS, GROUP_NOT_EMPTY, NAME_TAKEN,
                 NODE_ALREADY_ARCHIVED, NODE_NOT_ARCHIVED, NODE_ARCHIVED -> HttpStatus.CONFLICT;
            case INVALID_NAME, INVALID_FREQUENCY,
                 ORDER_NOT_A_PERMUTATION, SAME_NODE -> HttpStatus.BAD_REQUEST;
        };
        return new ApiException(status, ex.reason().name(), ex.getMessage());
    }

    /**
     * 把用户送来的字符串截到可以放心回声、放心落日志的长度。
     *
     * <p>不是转义、不是过滤 —— 只管长度。响应体是 JSON(Jackson 负责转义),
     * 日志里也只有这一串;这里要挡的是<b>体量</b>,不是字符。
     */
    /**
     * 「你传的这个取值我不认识」—— <b>回显必须过 {@link #echo} 截断</b>。
     *
     * <h2>为什么不能直接把 {@code e.getMessage()} 甩回去</h2>
     *
     * 那些 {@code ofWireName} 抛的消息里<b>带着用户原样输入的那个串</b>,而它没有长度上限。
     * {@code {"purpose":"<10KB 文本>"}} 会让那 10KB 整段回到响应体里 ——
     * 而这个仓库早就有一条针对它的纪律({@link #echo} + {@code
     * SyllabusAdminApiTest#rejectionMessagesDoNotEchoUnboundedInput}),
     * 只是鉴权这一侧当初没有接上。
     * <p>
     * 🔴 更要紧的是<b>这个产品的输入里可能是一整段题干</b>(决策记录 §2.2 不碰内容)——
     * 原样回显等于让它出现在响应体和访问日志里。
     */
    public static ApiException unknownValue(String code, String what, String userInput) {
        return new ApiException(HttpStatus.BAD_REQUEST, code,
                "不认识的" + what + ":" + echo(userInput));
    }

    /**
     * {@code orderBy} 不在四个取值里 —— 🔴 <b>422,不是静默按默认返回</b>
     * ({@code M3-骨架与覆盖度差集} §9.3)。
     *
     * <p>它与 {@link #invalidArgument} 分档的理由只有一条:{@code orderBy}
     * <b>有</b>服务端默认值,于是「翻不动就用默认」是一个随时会被写出来的实现 ——
     * 而静默之后端永远不知道自己传错了,屏上那句口径说明会一直在撒谎。
     * 给它一个自己的码,就是让那条路必须显式地被绕过才走得通。
     */
    public static ApiException unknownOrderBy(String userInput) {
        return new ApiException(ErrorCode.UNKNOWN_ORDER_BY,
                "不认识的排序口径:" + echo(userInput) + "。四个取值:recent5y_count / last_touch_at "
                        + "/ touch_count / syllabus_order。");
    }

    /**
     * 参数值不合法 —— <b>端上 bug 那一档</b>。
     *
     * <p>🔴 <b>不给它专码</b>({@code M3} §十)。这些位置的界面都是闭集选择器或段控,
     * 用户<b>选不出</b>非法值 —— 走到这里就是端上的 bug,而<b>「bug」不是一档界面状态</b>。
     * 新起一个码的判据是「界面需要后端能区分 N 档」,bug 不满足它。
     */
    public static ApiException invalidArgument(String what, String userInput) {
        return new ApiException(ErrorCode.INVALID_ARGUMENT,
                what + " 不合法:" + echo(userInput));
    }

    /**
     * 该科目的骨架还没建好 —— 🔴 <b>与 {@code 5xx} 必须两档</b>({@code U4.4}:缺骨架 ≠ 请求失败),
     * 也与「数过了,是空的」两档。
     *
     * <p>返回 {@code 200 {"nodeTotal": 0, ...}} 在语法上完全合法,界面也拿得到一个数 ——
     * 而那个「0 个考点」是一句<b>假话</b>({@code U3.1} §2.4)。所以它走状态码不走字段:
     * 整屏进空态,一个数都不显示,连 {@code total = 0} 都不写。
     */
    public static ApiException syllabusEmpty() {
        return new ApiException(ErrorCode.SYLLABUS_EMPTY,
                "这个科目的考点树还没建好。");
    }

    /** 节点已归档,标不了 —— 🔴 与「找不到」必须两档,合成一档用户会以为记录被删了。 */
    public static ApiException nodeArchived(String code) {
        return new ApiException(ErrorCode.NODE_ARCHIVED,
                "这个考点已经归档了:" + echo(code));
    }

    private static String echo(String userInput) {
        if (userInput == null) {
            return "(空)";
        }
        return userInput.length() <= MAX_ECHOED_VALUE_LENGTH
                ? userInput
                : userInput.substring(0, MAX_ECHOED_VALUE_LENGTH) + "…(已截断)";
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
