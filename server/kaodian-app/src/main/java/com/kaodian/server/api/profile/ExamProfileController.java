package com.kaodian.server.api.profile;

import com.kaodian.server.api.dto.common.ErrorCode;
import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.profile.ExamProfile;
import com.kaodian.server.profile.ExamProfileStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 备考档案 —— {@code GET / PUT /api/v1/profile/exam}
 * (`M3-骨架与覆盖度差集` §八 / `接口契约` §12.9.1)。
 *
 * <h2>🔴 {@code GET} 上那句 {@code requireWrite()} 不是笔误</h2>
 *
 * 方法名读着别扭(一个 {@code GET} 要求写权限),但契约逐字如此:
 * <b>「只读令牌命中 {@code GET} → {@code 403 READONLY_TOKEN}」</b>(§八 鉴权那两行)。
 * 理由是<b>备考档案是用户数据,不属于 MCP 五个 tool 的只读面</b> ——
 * 只读令牌是发给 MCP / CLI 的,它能读的是差集与骨架,不是「这个人打算考哪一场」。
 * <p>
 * ⚠️ 为什么不是在 {@code ApiAuthFilter} 上加一条 {@code /profile/} 前缀:
 * 那张黑名单挡的是<b>整段一律拒绝</b>的五条前缀,加进去等于宣布 {@code /profile/**}
 * 将来的每一个端点都对只读令牌关闭 —— 那是一次替还没写的端点做的决定。
 * 这里是第三道锁({@link CurrentSession#requireWrite()} 的类注释:三道是冗余的,
 * 冗余是有意的),用它表达「这一个端点」正合适。
 *
 * <h2>🔴 {@code PUT} 是全量覆盖,而且没有「跳过」这条路</h2>
 *
 * 两个字段互不依赖,<b>都可为空</b>(§八)—— 只填日期不选场次是合法的,反过来也是。
 * 这里<b>没有、也不许有</b>一句「必须两个都填」的表单校验。
 * <p>
 * 至于「跳过」:它<b>根本不调这个端点</b>,不是 {@code PUT} 一个空体(§5.4)。
 * 服务端因此没有、也不会有任何一个「已跳过」/「已提示过」标记位 ——
 * 加了它就长出「催他填」的落点,而产品裁定跳过后不留任何催促痕迹。
 * ⚠️ 这里<b>刻意不写出那几个字段名</b>:判据是一行 grep(§5.4 末),
 * 而一句「我们没有用它」会让那行 grep 自己命中自己(与 {@code Tenant} 同一条纪律)。
 * ⚠️ 已登记的代价:换一个端会再问一次。<b>登记,不补。</b>
 *
 * <h2>🔴 响应里不会出现任何派生天数</h2>
 *
 * 只有绝对日期。{@code U3.8} §2.4 的两道防线里这是第一道,第二道在 {@code web/}。
 * 服务端这一侧的形状由 {@link ExamProfileDto} 的两个字段限死。
 *
 * <h2>没有幂等键、没有分页</h2>
 *
 * 全量覆盖天然幂等:同一个请求发两次,库里那一行与响应体逐字相同。
 * 🔴 <b>不要 {@code Idempotency-Key}</b>(§八)—— 给一个本来就幂等的写操作配去重键,
 * 是让调用方为一个不存在的问题多记一件事。
 */
@RestController
@RequestMapping("/api/v1/profile/exam")
public class ExamProfileController {

    private final ExamProfileStore store;

    /**
     * 「今天」的来源。🔴 <b>不许出现 {@code LocalDate.now()} 的无参形式</b> ——
     * 日期窗口那条断言只有在时钟可替换时才写得出边界用例。
     */
    private final Clock clock;

    public ExamProfileController(ExamProfileStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * 读。没设过时返回 <code>{}</code> —— <b>两个 key 都不出现</b>,不是 {@code null}(§八)。
     *
     * <p>端上判「该不该出档案屏」的判定式逐字是「响应体是空对象」(§5.4),
     * 所以这里的空值形状是<b>行为契约</b>,不是序列化风格。执行装置在 {@link ExamProfileDto}。
     */
    @GetMapping
    public ExamProfileDto get(CurrentSession session) {
        // 🔴 见类注释:方法名读着别扭,但契约是「只读令牌命中 GET → 403 READONLY_TOKEN」。
        session.requireWrite();
        return ExamProfileDto.of(store.find(session.userId()));
    }

    /**
     * 写。<b>全量覆盖</b>,两格都可为空(等于清空这一项)。
     *
     * <p>把写完之后的状态原样返回,而不是 {@code 204}:调用方拿到的是<b>库里那一行</b>,
     * 于是「我发的空串被当成了清空」这类理解偏差在同一次交互里就能看见,
     * 不必再发一次 {@code GET} 去确认。形状与 {@code GET} 逐字相同。
     *
     * @throws ApiException {@code examDate} 不是 {@code YYYY-MM-DD} → {@code VALIDATION_FAILED};
     *                      超出 {@code 今天 −1 年 .. 今天 +2 年} → {@code EXAM_DATE_OUT_OF_RANGE}
     */
    @PutMapping
    public ExamProfileDto put(CurrentSession session, @RequestBody ExamProfileDto request) {
        session.requireWrite();

        LocalDate examDate = parseExamDate(request.examDate());
        if (examDate != null && !ExamProfile.withinWindow(LocalDate.now(clock), examDate)) {
            throw new ApiException(ErrorCode.EXAM_DATE_OUT_OF_RANGE,
                    "考试日期要落在【今天往前一年、往后两年】之内。"
                            + "超出这个范围的多半是年份敲错了一位。");
        }

        // 🔴 examType 的闭集校验在领域对象的构造器里,抛 IllegalArgumentException
        //    → ApiExceptionHandler 翻成 400 INVALID_ARGUMENT。【不新起码】(§八):
        //    这一格界面是闭集选择器,走到这里就是端上的 bug,而 bug 不是一档。
        // 🔴 也【没有】一句「这个省今天没有考情数据 → 拒」:照存不拒(§12.9.4)。
        ExamProfile profile = new ExamProfile(
                session.userId(), request.examType(), examDate, clock.instant());
        store.put(profile);

        return ExamProfileDto.of(profile);
    }

    /**
     * {@code YYYY-MM-DD} → {@link LocalDate};空白等于「没填」,不是错误。
     *
     * <p>🔴 <b>报错里不回显用户送来的那个串</b>(与 {@code ApiExceptionHandler} 开头那条纪律同源):
     * {@code examDate} 上没有任何长度上限,回显等于给「往日志里写一整段题干」开一条路。
     * 只说规则,不说他写了什么 —— 而规则本身已经足够照着改。
     */
    private static LocalDate parseExamDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;                       // 空 = 清空这一格,合法(§八:两个字段都可为空)
        }
        try {
            // ISO_LOCAL_DATE:严格四位年 + 两位月 + 两位日,顺带挡掉「日期」里夹时分秒的写法。
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "examDate 必须是 YYYY-MM-DD —— 它是一个日期,不是时刻,不带时分秒也不带时区。");
        }
    }
}
