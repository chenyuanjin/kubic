package com.kaodian.server.redline;

import com.kaodian.server.collect.Touch;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 R-01 的防回归断言 —— <b>线上库不存在能装下题干的字段</b>(docs/08 §四 R-01,docs/07 §二)。
 *
 * <h2>缺的从来不是约束</h2>
 *
 * 约束早就写透了:{@code CreateRecordRequest} 的注释把三道锁一条条讲清楚了
 * (没有自由文本字段 / {@code FAIL_ON_UNKNOWN_PROPERTIES} / {@code rejectUnknownField}),
 * {@code Touch} 那句「不是暂时不填,是结构上没有这个位置」也白纸黑字写着。
 * <b>缺的是让这些话在被拆掉的那一刻发出声音的东西。</b>
 * <p>
 * 今天没有人能违反 R-01。明天有人给 {@code Touch} 加一个 {@code String note},
 * 三道锁一道都拦不住 —— 它们锁的是「没定义的字段」,而 {@code note} 是定义好的。
 * 现有的测试一条都不会红,评审时它看上去只是个「备注」,而下一个版本它会被拿去存粘贴的原文。
 *
 * <h2>钉形状,不钉行为</h2>
 *
 * 这里不发请求、不起 Spring,只用反射看类型的形状。因为 R-01 说的就是形状 ——
 * 「连预留位都不留」。一个字段哪怕今天没人往里写,只要它存在,
 * 迟早有人问「既然有这个字段为什么不填」,而那时候删它的成本已经不是一行代码了。
 *
 * <h2>两条断言,一条有白名单一条没有</h2>
 *
 * 第一条(字段名黑名单)<b>没有白名单</b>。{@code stem / content / transcript / 题干} 这些词
 * 在这个仓库里不该出现在任何字段名上,一处都不行 —— 留个口子就等于把 R-01
 * 从「结构上没有这个位置」降级成「命名规范」。
 * <p>
 * 第二条(自由文本长度)有白名单,因为 id、code、token 这些确实不该有长度上限。
 * 但白名单是<b>一行一个字段</b>写死的,每一行必须挑一个 {@link Reason}。
 * 不写成一个宽松的正则是有意的:正则一次放行一大片,而白名单逼着加字段的人停下来回答
 * 「凭什么」。<b>挑不出理由,说明这个字段不该存在。</b>
 *
 * <h2>⚪ 白名单里还剩一行 {@link Reason#KNOWN_GAP}</h2>
 *
 * 那一行不是理由,是缺口。2026-08-27 收掉了七行({@code deviceLabel} × 4、{@code referrer} × 3,
 * docs/08 §四 R-73):三个登录请求体加了 {@code @Size},{@code SessionDto} 那一行改判为
 * {@link Reason#BOUNDED_UPSTREAM} —— 它是响应,加注解不校验任何东西,收口点在写入口。
 * <p>
 * 剩下的 {@code AccountDto#nickname} <b>没有跟着收</b>,理由写在那一行上。
 * 按 01 §5 的规矩,没解决的事摆在明面上,不拿一句漂亮话盖过去 ——
 * 也不为了让表短一行就编一个上限出来。
 */
class NoStemFieldTest {

    /**
     * 扫整包,不挑类。
     *
     * <p>挑类的那一刻这个测试就开始依赖「有人记得把新类加进来」,
     * 而那正是它要防的那种遗忘。
     */
    private static final List<String> SCANNED_PACKAGES = List.of(
            "com.kaodian.server.api.dto",
            "com.kaodian.server.collect",
            "com.kaodian.server.syllabus");

    /**
     * 自由文本的长度天花板。
     *
     * <p>200 不是随手挑的:库里最宽的那个是「来源名」60(「粉笔 · 资料分析系统班 L12」二十来个字),
     * 考点 code 是 64。200 给了三倍余量,同时<b>装不下任何一道真题的题干</b> ——
     * 资料分析一道题的材料就上千字。这个数字的作用不是精确,是<b>把「放个名字」和「放段内容」
     * 分在两边</b>。
     */
    private static final int MAX_FREE_TEXT_LENGTH = 200;

    /**
     * 只看主产物({@code target/classes})里的类。
     *
     * <p>{@code target/test-classes} 也在类路径上,而测试里的桩类
     * ({@code CaptureServiceTest.FakeTagger} 之类)确实会出现 {@code answer}、{@code IMAGE}
     * 这样的名字 —— <b>那是好事,不是违规</b>:测试本来就该拿这些词去构造反例。
     * 红线管的是<b>线上库的形状</b>,所以边界划在产物上,而不是在类名里认 {@code Test} 后缀。
     */
    private static final String MAIN_ARTIFACT =
            Touch.class.getProtectionDomain().getCodeSource().getLocation().toString();

    /**
     * 🔴 命中即失败,没有白名单。
     *
     * <p>这些词描述的都是<b>题目本身</b>,不是「碰过它」这件事。行为层只记
     * 「有没有、几次、多久前」(01 §2.2 能力边界),这些词一个都用不上;
     * 它们出现在字段名里,只有一个解释 —— 有人打算往库里存内容了。
     * <p>
     * 匹配的是小写化之后的<b>子串</b>,所以 {@code rawText / RAW_TEXT / questionStem} 一起覆盖。
     */
    private static final List<String> BANNED_WORDS = List.of(
            "stem", "content", "body", "text", "question", "answer",
            "analysis", "explanation", "transcript", "transcription",
            "image", "img", "raw", "ocr", "audio", "screenshot",
            "attachment", "snippet", "excerpt", "passage", "paragraph");

    /**
     * 中文字段名是合法的 Java 标识符,所以这一列必须存在 —— 否则把字段叫 {@code 题干} 就绕过去了。
     */
    private static final List<String> BANNED_CJK = List.of(
            "题干", "原文", "解析", "内容", "正文", "转写", "录音", "截图",
            "答案", "讲义", "真题", "试题");

    /** 白名单里每一行都要挑一个理由。挑不出来,就别加这一行。 */
    private enum Reason {

        /**
         * 服务端生成的标识:id / code / token / hash / traceId / state。
         *
         * <p>取值域由生成算法定死(UUID、{@code n-} + 随机、SHA-256 hex、六位数字),
         * 用户送进来的同名字段只是<b>查表用的键</b> —— 查不到就 404 / 401,
         * 它装得下什么根本不重要,因为没有任何一条路会把它存起来。
         * 给它加 {@code @Size} 是给一个不存在的入口上锁。
         */
        SERVER_ISSUED_ID,

        /**
         * 值是我们自己写的字面量:枚举 label、错误码、提示语,以及骨架种子文件里的学科元信息。
         *
         * <p>长度在编译期(或者在我们自己维护的 {@code seed/*.json} 里)就定死了。
         * 给它加校验注解,校验的是我们自己敲的那行字。
         */
        SERVER_CONSTANT,

        /**
         * 值只可能来自一个已经收口的写入口,这里是它的下游。
         *
         * <p>来源名 {@code @Size(max = 60)}、考点/题型名 {@code FileSyllabusStore.MAX_NAME_LENGTH = 40}。
         * <b>校验属于边界。</b>在下游再写一遍上限,会出现两个数,而两个数迟早对不上 ——
         * 到那时候真正生效的是小的那个,没人说得清是哪个。
         */
        BOUNDED_UPSTREAM,

        /**
         * 约束这个字段的不是长度,是格式,而格式比长度严得多。
         *
         * <p>手机号 {@code PhoneCipher.CN_MOBILE = ^1[3-9]\d{9}$}、验证码纯数字、
         * {@code purpose} 必须解析成 {@code SmsPurpose} 枚举、微信 code 由微信下发。
         * 一段题干过不了这些格式里的任何一个。<b>但格式那把锁必须一直在</b> ——
         * 它一旦松成「非空即可」,这一行就该改成 {@link #KNOWN_GAP}。
         */
        BOUND_BY_FORMAT,

        /**
         * 值可能来自用户,但它在离开进程之前一定会被截断。
         *
         * <p>{@code ApiExceptionHandler#truncate} 砍到 40 字,{@code ApiException#echo} 同理。
         * 这条路 {@code ApiContractTest#rejectionMessagesDoNotEchoUnboundedUserInput} 已经在守。
         */
        TRUNCATED_AT_THE_EXIT,

        /**
         * ⚪ 这不是理由,是缺口。
         *
         * <p>写在这里的字段今天确实没有上限。摆出来是为了让它可数、可查
         * (01 §5:没解决的事不拿话盖过去),不是为了让下一个字段有个地方可以挂。
         * <b>这一档不接受新增</b> —— 新字段要么有 {@code @Size},要么挑得出上面五个理由之一。
         *
         * <p>也不接受<b>为了让表短一行而编一个上限</b>:上限该由写入口的形状定,
         * 没有写入口就定不出数,那时候诚实的做法是留在这一档。
         */
        KNOWN_GAP
    }

    /**
     * 白名单 —— key 是 {@code 类名#字段名},value 是它凭什么可以没有长度上限。
     *
     * <p>顺序按包、按类排,方便和源码对着看。<b>加一行之前先问:这个字段真的装不下一段题干吗?</b>
     */
    private static final Map<String, Reason> ALLOWED = Map.ofEntries(

            // ———————————————————————— api.dto:账号 ————————————————————————
            entry("AccountDto#userId", Reason.SERVER_ISSUED_ID),
            // ⚪ R-73 收口时唯一没收的一行。今天恒为 null:AppUser#create 传的就是 null,
            //    全仓库没有任何接口能写它 —— 它装不下题干,靠的是「没有入口」,不是「有上限」。
            //    没给它编一个数是有意的:上限由那个接口的形状定(昵称是个称呼还是一句签名,
            //    20 和 60 是两个答案),现在写一个进来,只是让这张表短一行,而那个数没有任何输入验证过它。
            //    ⚠️ 这一档真正的风险在这一行上:「没有写入口」不是这个测试守得住的性质 ——
            //    加写入口的那次提交不会红。所以它留在这里,而不是被当成已经解决。
            entry("AccountDto#nickname", Reason.KNOWN_GAP),
            entry("AccountDto#maskedPhone", Reason.BOUND_BY_FORMAT),
            entry("AccountDto#identities", Reason.SERVER_CONSTANT),
            entry("DeactivateResponse#exportHint", Reason.SERVER_CONSTANT),
            entry("SessionDto#tokenHash", Reason.SERVER_ISSUED_ID),
            // 响应字段,@Size 挂上去不校验任何东西。上限写在写入口的三个登录请求体上
            // (LoginFieldLimits.MAX_DEVICE_LABEL = 40),见 SessionDto 类注释。
            entry("SessionDto#deviceLabel", Reason.BOUNDED_UPSTREAM),
            entry("RevokeSessionRequest#tokenHash", Reason.SERVER_ISSUED_ID),

            // ———————————————————————— api.dto:登录与绑定 ————————————————————————
            entry("BindPhoneRequest#phone", Reason.BOUND_BY_FORMAT),
            entry("BindPhoneRequest#code", Reason.BOUND_BY_FORMAT),
            entry("BindResponse#mergeToken", Reason.SERVER_ISSUED_ID),
            entry("BindWeChatRequest#entry", Reason.BOUND_BY_FORMAT),
            entry("BindWeChatRequest#code", Reason.BOUND_BY_FORMAT),
            entry("BindWeChatRequest#state", Reason.SERVER_ISSUED_ID),
            entry("LoginResponse#token", Reason.SERVER_ISSUED_ID),
            entry("LoginResponse#userId", Reason.SERVER_ISSUED_ID),
            entry("LoginResponse#maskedPhone", Reason.BOUND_BY_FORMAT),
            entry("LoginResponse#splitMergeToken", Reason.SERVER_ISSUED_ID),
            entry("MergePreviewResponse#fromLabel", Reason.BOUND_BY_FORMAT),
            entry("MergePreviewResponse#toLabel", Reason.BOUND_BY_FORMAT),
            entry("MergePreviewResponse#notice", Reason.SERVER_CONSTANT),
            entry("MergeRequest#mergeToken", Reason.SERVER_ISSUED_ID),
            entry("SmsSendRequest#phone", Reason.BOUND_BY_FORMAT),
            entry("SmsSendRequest#purpose", Reason.BOUND_BY_FORMAT),
            entry("SmsSendRequest#captchaTicket", Reason.BOUND_BY_FORMAT),
            entry("SmsSendRequest#captchaRandstr", Reason.BOUND_BY_FORMAT),
            entry("SmsSendResponse#devCode", Reason.SERVER_ISSUED_ID),
            entry("SmsVerifyRequest#phone", Reason.BOUND_BY_FORMAT),
            entry("SmsVerifyRequest#code", Reason.BOUND_BY_FORMAT),
            entry("SmsVerifyRequest#purpose", Reason.BOUND_BY_FORMAT),
            entry("WeChatAuthorizeUrlResponse#url", Reason.SERVER_ISSUED_ID),
            entry("WeChatAuthorizeUrlResponse#state", Reason.SERVER_ISSUED_ID),
            entry("WeChatLoginRequest#entry", Reason.BOUND_BY_FORMAT),
            entry("WeChatLoginRequest#code", Reason.BOUND_BY_FORMAT),
            entry("WeChatLoginRequest#state", Reason.SERVER_ISSUED_ID),
            entry("WeChatPhoneLoginRequest#loginCode", Reason.BOUND_BY_FORMAT),
            entry("WeChatPhoneLoginRequest#phoneCode", Reason.BOUND_BY_FORMAT),

            // ———————————————————————— api.dto:错误 ————————————————————————
            entry("ApiError#code", Reason.SERVER_CONSTANT),
            entry("ApiError#message", Reason.TRUNCATED_AT_THE_EXIT),
            entry("ApiError#traceId", Reason.SERVER_ISSUED_ID),
            entry("UnknownFieldException#fieldName", Reason.TRUNCATED_AT_THE_EXIT),

            // ———————————————————————— api.dto:覆盖率与骨架的只读投影 ————————————————————————
            entry("BlindSpotDto#code", Reason.SERVER_ISSUED_ID),
            entry("BlindSpotDto#groupCode", Reason.SERVER_ISSUED_ID),
            entry("BlindSpotDto#name", Reason.BOUNDED_UPSTREAM),
            entry("BlindSpotDto#groupName", Reason.BOUNDED_UPSTREAM),
            entry("BlindSpotDto#state", Reason.SERVER_CONSTANT),
            entry("BlindSpotDto#stateLabel", Reason.SERVER_CONSTANT),
            entry("DeletedResponse#code", Reason.SERVER_ISSUED_ID),
            entry("GroupDto#code", Reason.SERVER_ISSUED_ID),
            entry("GroupDto#name", Reason.BOUNDED_UPSTREAM),
            entry("NodeDto#code", Reason.SERVER_ISSUED_ID),
            entry("NodeDto#name", Reason.BOUNDED_UPSTREAM),
            entry("NodeDto#state", Reason.SERVER_CONSTANT),
            entry("NodeDto#stateLabel", Reason.SERVER_CONSTANT),
            entry("NodeDetailDto#code", Reason.SERVER_ISSUED_ID),
            entry("NodeDetailDto#groupCode", Reason.SERVER_ISSUED_ID),
            entry("NodeDetailDto#name", Reason.BOUNDED_UPSTREAM),
            entry("NodeDetailDto#groupName", Reason.BOUNDED_UPSTREAM),
            entry("NodeDetailDto#state", Reason.SERVER_CONSTANT),
            entry("NodeDetailDto#stateLabel", Reason.SERVER_CONSTANT),
            // sources 的元素就是 Touch#sourceName,写入口 @Size(max = 60)
            entry("NodeDetailDto#sources", Reason.BOUNDED_UPSTREAM),
            entry("RecordsMovedResponse#fromNodeCode", Reason.SERVER_ISSUED_ID),
            entry("RecordsMovedResponse#toNodeCode", Reason.SERVER_ISSUED_ID),
            entry("StateCountDto#state", Reason.SERVER_CONSTANT),
            entry("StateCountDto#label", Reason.SERVER_CONSTANT),
            entry("SubjectDto#code", Reason.SERVER_CONSTANT),
            entry("SubjectDto#region", Reason.SERVER_CONSTANT),
            entry("SubjectDto#exam", Reason.SERVER_CONSTANT),
            entry("SubjectDto#module", Reason.SERVER_CONSTANT),
            entry("SubjectDto#recent5yWindow", Reason.SERVER_CONSTANT),
            entry("SubjectDto#display", Reason.SERVER_CONSTANT),
            entry("SyllabusGroupDto#code", Reason.SERVER_ISSUED_ID),
            entry("SyllabusGroupDto#name", Reason.BOUNDED_UPSTREAM),
            entry("SyllabusNodeDto#code", Reason.SERVER_ISSUED_ID),
            entry("SyllabusNodeDto#groupCode", Reason.SERVER_ISSUED_ID),
            entry("SyllabusNodeDto#name", Reason.BOUNDED_UPSTREAM),
            entry("SyllabusNodeDto#groupName", Reason.BOUNDED_UPSTREAM),
            entry("SyllabusExportResponse.ExportGroupDto#code", Reason.SERVER_ISSUED_ID),
            entry("SyllabusExportResponse.ExportGroupDto#name", Reason.BOUNDED_UPSTREAM),
            entry("SyllabusExportResponse.ExportNodeDto#code", Reason.SERVER_ISSUED_ID),
            entry("SyllabusExportResponse.ExportNodeDto#name", Reason.BOUNDED_UPSTREAM),

            // ———————————————————————— api.dto:时间线 ————————————————————————
            entry("TimelineItemDto#id", Reason.SERVER_ISSUED_ID),
            entry("TimelineItemDto#nodeCode", Reason.SERVER_ISSUED_ID),
            entry("TimelineItemDto#groupCode", Reason.SERVER_ISSUED_ID),
            entry("TimelineItemDto#kind", Reason.SERVER_CONSTANT),
            entry("TimelineItemDto#kindLabel", Reason.SERVER_CONSTANT),
            entry("TimelineItemDto#sourceName", Reason.BOUNDED_UPSTREAM),
            entry("TimelineItemDto#nodeName", Reason.BOUNDED_UPSTREAM),
            entry("TimelineItemDto#groupName", Reason.BOUNDED_UPSTREAM),

            // ———————————————————————— collect:行为层 ————————————————————————
            // 🔴 Touch 是真正落盘的那个形状。这三行是整张表里最该被盯住的三行 ——
            //    它们能进白名单,靠的是「上游有 @Size」,不是「看着还行」。
            entry("Touch#id", Reason.SERVER_ISSUED_ID),
            entry("Touch#nodeCode", Reason.SERVER_ISSUED_ID),
            entry("Touch#sourceName", Reason.BOUNDED_UPSTREAM),
            // 「我已掌握」那一行的全部内容就是这个 code 加一个时刻。写入口是
            // AssertionRequest#nodeCode(@Size(max = 64)),与 Touch#nodeCode 同一个理由:
            // 它是查表用的键,查不到就 400,没有任何一条路会把它当成文本存起来。
            entry("UserAssertion#nodeCode", Reason.SERVER_ISSUED_ID),
            entry("TouchKind#label", Reason.SERVER_CONSTANT),
            entry("CaptureService.CaptureRequest#nodeCode", Reason.SERVER_ISSUED_ID),
            entry("CaptureService.CaptureRequest#sourceName", Reason.BOUNDED_UPSTREAM),
            entry("CaptureService.Mounting#label", Reason.SERVER_CONSTANT),
            entry("CaptureService.Rejection#label", Reason.SERVER_CONSTANT),

            // ———————————————————————— syllabus:骨架层 ————————————————————————
            entry("Syllabus.Subject#code", Reason.SERVER_CONSTANT),
            entry("Syllabus.Subject#region", Reason.SERVER_CONSTANT),
            entry("Syllabus.Subject#exam", Reason.SERVER_CONSTANT),
            entry("Syllabus.Subject#module", Reason.SERVER_CONSTANT),
            entry("Syllabus.Subject#recent5yWindow", Reason.SERVER_CONSTANT),
            entry("Syllabus.Group#code", Reason.SERVER_ISSUED_ID),
            entry("Syllabus.Group#name", Reason.BOUNDED_UPSTREAM),
            entry("Syllabus.Node#code", Reason.SERVER_ISSUED_ID),
            entry("Syllabus.Node#name", Reason.BOUNDED_UPSTREAM));

    // ================================================================ 断言

    @Test
    @DisplayName("🔴 R-01 之一:没有任何字段的名字在说「题目本身」")
    void noFieldNameCanHoldAQuestion() {
        List<String> violations = new ArrayList<>();
        for (Member m : scan()) {
            String hit = bannedHit(m.name());
            if (hit != null) {
                violations.add("  ✗ " + m.qualified() + "\n"
                        + "      违反第 1 条(字段名黑名单):名字里命中「" + hit + "」");
            }
        }
        assertTrue(violations.isEmpty(), () -> """
                🔴 R-01 被破坏 —— 线上库出现了名字在说「题目本身」的字段(docs/08 §四 R-01)。

                %s

                这一条没有白名单,改名字也不算数:R-01 说的是「连预留位都不留」,
                把 stem 改叫 detail 只是把红线降级成命名规范,库的形状一点没变。
                真要加这个字段,先回 docs/08 §四把 R-01 改掉,再来改这个测试 —— 顺序不能反。
                """.formatted(String.join("\n", violations)));
    }

    @Test
    @DisplayName("🔴 R-01 之二:自由文本要么有 @Size(max ≤ 200),要么在白名单里写明为什么")
    void everyFreeTextFieldHasACeiling() {
        List<String> violations = new ArrayList<>();
        for (Member m : scan()) {
            if (!m.instanceState() || !m.holdsText()) {
                continue;
            }
            if (ALLOWED.containsKey(m.key())) {
                continue;
            }
            Size size = m.textSize();
            if (size == null) {
                violations.add("  ✗ " + m.qualified() + "  (" + m.shape() + ",没有 @Size)\n"
                        + "      违反第 2 条(自由文本长度):没有上限,装得下一整道题");
            } else if (size.max() > MAX_FREE_TEXT_LENGTH) {
                violations.add("  ✗ " + m.qualified() + "  (" + m.shape()
                        + ",@Size(max = " + size.max() + "))\n"
                        + "      违反第 2 条(自由文本长度):上限 " + size.max()
                        + " 超过 " + MAX_FREE_TEXT_LENGTH + ",够放一段材料了");
            }
        }
        assertTrue(violations.isEmpty(), () -> """
                🔴 R-01 被破坏 —— 出现了没有上限的自由文本(docs/08 §四 R-01,01 §2.2 不碰内容)。

                %s

                两条路,二选一:
                  1. 加 @Size(max = …),上限不超过 %d。「粉笔 · 资料分析系统班 L12」二十来个字,
                     来源名给到 60 就已经宽裕 —— 上限不是给合法输入留余量,是把「放个名字」
                     和「放段内容」分在两边。
                  2. 确实定不出上限,就到 NoStemFieldTest.ALLOWED 里加一行,并挑一个 Reason。
                     ⚪ KNOWN_GAP 不接受新增 —— 它记的是已经欠下的账,不是新账的去处。

                挑不出理由,说明这个字段不该存在。
                """.formatted(String.join("\n", violations), MAX_FREE_TEXT_LENGTH));
    }

    @Test
    @DisplayName("白名单不许留死行 —— 字段没了,那一行也必须跟着没")
    void whitelistHasNoStaleEntries() {
        Set<String> live = new HashSet<>();
        for (Member m : scan()) {
            if (m.instanceState() && m.holdsText()) {
                live.add(m.key());
            }
        }
        Set<String> stale = new TreeSet<>(ALLOWED.keySet());
        stale.removeAll(live);
        assertTrue(stale.isEmpty(), () -> """
                白名单里有对不上任何字段的行:%s

                死行是白名单最危险的一种腐烂:它让表越来越长、越来越没人读,
                而下一个人会以为「这里本来就很宽松」。字段删了,行也删掉。
                """.formatted(stale));
    }

    @Test
    @DisplayName("扫描本身没有落空 —— 一个扫不到东西的断言等于没有断言")
    void theScanActuallyFindsSomething() {
        List<Member> members = scan();
        long types = members.stream().map(Member::owner).distinct().count();
        long texts = members.stream().filter(m -> m.instanceState() && m.holdsText()).count();

        // 包名写错、类路径没打进去、扫描器把 record 过滤掉了 —— 这几种情况下上面两条断言
        // 会安安静静地通过,而且永远通过。所以这里给一个下限。
        // 2026-08 实测:类 69 / 成员 253 / 自由文本 122。下限压在这之下一点,
        // 是留给删字段,不是留给「扫不到了」。
        assertTrue(types >= 60 && members.size() >= 220 && texts >= 110, () -> """
                扫到的东西太少,这个测试可能已经空转了:类 %d 个 / 成员 %d 个 / 自由文本 %d 个。
                先确认 SCANNED_PACKAGES 里的包名和 target/classes 对得上,再谈别的。
                """.formatted(types, members.size(), texts));
    }

    // ================================================================ 扫描

    /**
     * 一个「能装东西的位置」—— record 分量,或者实例字段。
     *
     * @param instanceState 是不是实例状态。静态字段是编译期常量,不是库的形状,
     *                      所以它只受黑名单管,不受长度那条管
     */
    private record Member(Class<?> owner, String name, Class<?> type,
                          AnnotatedType annotatedType, boolean instanceState, Size declaredSize) {

        String key() {
            return label(owner) + "#" + name;
        }

        String qualified() {
            return owner.getPackageName() + "." + key();
        }

        /** 这个位置能不能装下一段文字 —— String 本身,或者一筐 String。 */
        boolean holdsText() {
            return type == String.class || elementType() == String.class;
        }

        String shape() {
            return type == String.class ? "String" : type.getSimpleName() + "<String>";
        }

        /**
         * 管着<b>文字长度</b>的那个 {@code @Size}。
         *
         * <p>对 {@code List<String>} 必须取<b>元素上</b>的那个:字段上的 {@code @Size} 管的是
         * 元素个数,拿它当文字上限会得出「200 个字符」这种完全错误的结论 ——
         * 实际是「200 条,每条随便多长」。
         */
        Size textSize() {
            if (type == String.class) {
                return declaredSize;
            }
            if (annotatedType instanceof AnnotatedParameterizedType apt) {
                AnnotatedType[] args = apt.getAnnotatedActualTypeArguments();
                if (args.length == 1 && args[0].getType() == String.class) {
                    return args[0].getAnnotation(Size.class);
                }
            }
            return null;
        }

        private Class<?> elementType() {
            if (!Collection.class.isAssignableFrom(type)) {
                return null;
            }
            if (annotatedType instanceof AnnotatedParameterizedType apt) {
                AnnotatedType[] args = apt.getAnnotatedActualTypeArguments();
                if (args.length == 1 && args[0].getType() instanceof Class<?> c) {
                    return c;
                }
            }
            return null;
        }
    }

    private static List<Member> scan() {
        List<Member> members = new ArrayList<>();
        for (Class<?> type : scannedTypes()) {
            members.addAll(membersOf(type));
        }
        return members;
    }

    private static List<Class<?>> scannedTypes() {
        // 默认的 isCandidateComponent 只放行「独立且具体」的类型,接口和抽象类会被安静地丢掉。
        // 那正好是个藏字段的地方,所以这里全放行,自己筛。
        var provider = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true;
            }
        };
        provider.addIncludeFilter(new AssignableTypeFilter(Object.class));

        List<Class<?>> types = new ArrayList<>();
        for (String pkg : SCANNED_PACKAGES) {
            for (BeanDefinition bd : provider.findCandidateComponents(pkg)) {
                try {
                    Class<?> c = Class.forName(bd.getBeanClassName());
                    if (!c.isSynthetic() && !c.isAnonymousClass() && isMainArtifact(c)) {
                        types.add(c);
                    }
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("扫到了类名却加载不了:" + bd.getBeanClassName(), e);
                }
            }
        }
        return types;
    }

    private static boolean isMainArtifact(Class<?> c) {
        var source = c.getProtectionDomain().getCodeSource();
        return source != null && MAIN_ARTIFACT.equals(source.getLocation().toString());
    }

    private static List<Member> membersOf(Class<?> c) {
        List<Member> out = new ArrayList<>();
        Set<String> components = new HashSet<>();

        if (c.isRecord()) {
            for (RecordComponent rc : c.getRecordComponents()) {
                components.add(rc.getName());
                out.add(new Member(c, rc.getName(), rc.getType(), rc.getAnnotatedType(),
                        true, sizeOf(c, rc)));
            }
        }
        for (Field f : c.getDeclaredFields()) {
            // record 的后备字段和分量是同一个位置,别数两遍;
            // 枚举常量是类型名不是数据位,合成字段($VALUES / this$0)是编译器加的。
            if (f.isSynthetic() || f.isEnumConstant() || components.contains(f.getName())) {
                continue;
            }
            out.add(new Member(c, f.getName(), f.getType(), f.getAnnotatedType(),
                    !Modifier.isStatic(f.getModifiers()), f.getAnnotation(Size.class)));
        }
        return out;
    }

    /**
     * record 分量上的 {@code @Size} 会按注解自身的 {@code @Target} 落到分量、后备字段、访问器
     * 里的某几处 —— 三处都看一遍,别因为落点不同就当成「没写」。
     */
    private static Size sizeOf(Class<?> record, RecordComponent rc) {
        Size onComponent = rc.getAnnotation(Size.class);
        if (onComponent != null) {
            return onComponent;
        }
        Size onAccessor = rc.getAccessor().getAnnotation(Size.class);
        if (onAccessor != null) {
            return onAccessor;
        }
        try {
            return record.getDeclaredField(rc.getName()).getAnnotation(Size.class);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    // ================================================================ 黑名单匹配

    /** 命中的那个词;没命中返回 null。 */
    private static String bannedHit(String fieldName) {
        String flat = flatten(fieldName);
        for (String word : BANNED_WORDS) {
            if (flat.contains(word)) {
                return word;
            }
        }
        for (String word : BANNED_CJK) {
            if (fieldName.contains(word)) {
                return word;
            }
        }
        return null;
    }

    /**
     * 小写化,并把 {@code system} 抹掉。
     *
     * <p>后半句是为了 {@code systemClock} 这种名字 —— 里面藏着 {@code stem},
     * 而它和题干没有半点关系。这是整张黑名单上唯一一处英文巧合,
     * 单独处理掉,好过把 {@code stem} 从表里拿走。
     */
    private static String flatten(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT).replace("system", "");
    }

    /** 嵌套类写成 {@code Outer.Inner},比 {@code Outer$Inner} 好对着源码看。 */
    private static String label(Class<?> c) {
        Class<?> enclosing = c.getEnclosingClass();
        return enclosing == null ? c.getSimpleName() : label(enclosing) + "." + c.getSimpleName();
    }
}
