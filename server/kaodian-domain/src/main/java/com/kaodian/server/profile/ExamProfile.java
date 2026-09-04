package com.kaodian.server.profile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/**
 * 备考档案的一行 —— 目标表 {@code user_exam_profile(user_id PK, exam_type, exam_date, updated_at)}
 * (`接口契约` §12.9.1 / `M3-骨架与覆盖度差集` §八)。
 *
 * <h2>🔴 主键是 {@code userId},一个人只有一行 —— 不留历史</h2>
 *
 * 与 {@code UserAssertion} 那句「去重靠的是主键的形状」同一条,但这里更硬:
 * 表上<b>没有一个能装下第二行的位置</b>。留了历史就长出「你的备考轨迹」,
 * 而那是学习分析,是 {@code R-05} 的正对面(§12.9.1「留不留历史 → 不留」)。
 * 所以 {@link ExamProfileStore#put} 是覆盖,不是追加 —— <b>覆盖不是实现偷懒,是契约</b>。
 *
 * <h2>🔴 两个字段各自独立,而且都可为空</h2>
 *
 * 只填日期不选场次是合法的,反过来也是(§八「两字段互不依赖」)。
 * <b>这里没有、也不许有一句「必须两个都填」的校验</b> —— 那一句会把
 * 「只想记个日子」的用户挡在门外,而产品在这一屏上连「你填错了」这一档都不存在
 * ({@code U3.8} §2.5 末行:用户说自己考哪一场,产品没有资格判它错)。
 * <p>
 * 两个都为空是<b>合法状态</b>,等于清空这一项。它与「从没设过」在契约上是同一件事:
 * 两种都让 {@code GET} 回一个空对象,而 §5.4 逐字写了「不分,本来就是同一件事」。
 *
 * <h2>🔴 这个 record 上没有任何派生出来的天数</h2>
 *
 * 只有 {@link #examDate} 这个绝对日期。{@code U3.8} §2.4 的两道防线里,这里是第一道:
 * 契约不返回天数。一个天数一旦上了屏,能和它搭配的只可能是复习提醒或紧迫感文案,
 * 两样都在能力边界之外。<b>加一个方法算它出来,红线就在领域层这一侧先破了。</b>
 *
 * @param userId    归属。{@code 0} 不是「暂时没有用户」,它根本不是一个合法用户(B0 §3.3)
 * @param examType  闭集:{@link #NATIONAL} 或省级行政区代码;{@code null} 表示没设 / 已清空
 * @param examDate  🔴 {@code LocalDate} 不是 {@code Instant} —— 契约写的是 {@code date} 不是
 *                  {@code datetime}(§八)。用时刻类型装它,出口就会带上一个没人定义过的
 *                  时区与时分秒,而「我要考的那一场」是哪一天,不是哪一秒
 * @param updatedAt 最后一次覆盖的时刻。<b>它不是历史</b>,只有一份,覆盖时一起被覆盖
 */
public record ExamProfile(long userId, String examType, LocalDate examDate, Instant updatedAt) {

    /** 国考。闭集里唯一一个不是行政区代码的取值。 */
    public static final String NATIONAL = "national";

    /** 往回看一年 —— 刚考完的人还留着上一场的日期,不该被当成非法输入。 */
    public static final int WINDOW_YEARS_BACK = 1;

    /** 往前看两年 —— 再远就不是「我要考的那一场」,是一个笔误。 */
    public static final int WINDOW_YEARS_AHEAD = 2;

    /**
     * 🔴 省级行政区代码全集(GB/T 2260 两位数字码),<b>34 个,一个不少</b>。
     *
     * <p>不筛掉「今天没有考情数据」的那些省:2026-09-03 产品裁定
     * (§12.9.4 / {@code U3.8} §2.5)—— {@code examType} 是<b>用户对自己的陈述</b>,
     * 不是我们算出来的量,所以照存不拒。契约里那个「这一场我们还没有考情数据」的拒绝码因此
     * <b>今天没有任何端点能抛出它</b>,已整条挪出现役码表,这个类里也就没有它的位置。
     * <p>
     * ⚠️ 这段话<b>刻意不写出那个码的名字</b> —— 与 {@code Tenant} 上那句同一条纪律:
     * 判据是一行 grep,而一句「我们没有用它」会让那行 grep 自己命中自己。
     * <p>
     * ⚠️ 存下来今天是惰性的:没有任何参数消费它(盲区榜暂无 {@code province})。
     * 这不是白存 —— 统计单位改对那天,「先补哪几个省」的答案已经在库里了。
     * <p>
     * 🔴 <b>只认两位码这一种写法</b>。同时收 {@code "11"} 与 {@code "110000"} 的话,
     * 北京在库里会有两个键,而将来那次按省份的连接会静默少算一半人 ——
     * 把六位悄悄折成两位更糟:静默转换正是 §12.9.3 逐字禁掉的那件事。
     */
    static final Set<String> PROVINCE_CODES = Set.of(
            "11", "12", "13", "14", "15",              // 京 津 冀 晋 蒙
            "21", "22", "23",                          // 辽 吉 黑
            "31", "32", "33", "34", "35", "36", "37",  // 沪 苏 浙 皖 闽 赣 鲁
            "41", "42", "43", "44", "45", "46",        // 豫 鄂 湘 粤 桂 琼
            "50", "51", "52", "53", "54",              // 渝 川 贵 云 藏
            "61", "62", "63", "64", "65",              // 陕 甘 青 宁 新
            "71", "81", "82");                         // 台 港 澳

    public ExamProfile {
        // 🔴 只校验形状,不查「这个用户存不存在」—— 查存在性会把 domain → auth 那条边建出来
        //    (B0-3 §4.3,与 collect 侧那一句同源)。
        if (userId <= 0) {
            throw new IllegalArgumentException(
                    "userId 必须是正数,拿到的是 " + userId
                            + " —— 0 不是「暂时没有用户」的意思,它根本不是一个合法用户(B0 §3.3)");
        }
        examType = blankToNull(examType);
        if (examType != null && !isExamType(examType)) {
            // 🔴 不回显用户送来的那个串:这条消息会原样出现在响应体里
            //    (ApiExceptionHandler 对 IllegalArgumentException 是直接透传 message),
            //    而 examType 上没有任何长度上限,回显等于给「往日志里写一整段题干」开了条路。
            throw new IllegalArgumentException(
                    "examType 不在闭集里 —— 只接受 national 或省级行政区代码(两位数字)。"
                            + "这一格界面是闭集选择器,走到这里就是端上的 bug");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt 不能为空 —— 覆盖这一行时它一起被覆盖");
        }
    }

    /** 闭集判定。{@code null} / 空白不是合法取值,「没设过」用 {@code null} 表达,不走这里。 */
    public static boolean isExamType(String value) {
        return NATIONAL.equals(value) || PROVINCE_CODES.contains(value);
    }

    /**
     * 考试日期是否落在允许的窗口里:{@code today −1 年 .. today +2 年}(闭区间,§八)。
     *
     * <p>🔴 <b>纯函数,不问「现在几点」</b> —— {@code today} 由调用方从注入的
     * {@code Clock} 取。窗口是这个模块里唯一一处与「今天」有关的算术,
     * 把它写成 {@code LocalDate.now()} 就再也没法在测试里回放边界那两天。
     *
     * <p>⚠️ 窗口宽三年,而这里比的是「哪一天」:调用方用哪个时区取 {@code today},
     * 最多让边界那一天差一天。它不值得为此多一个时区配置项 ——
     * 多一个开关意味着多一处将来会与别处分叉的地方。
     */
    public static boolean withinWindow(LocalDate today, LocalDate examDate) {
        return !examDate.isBefore(today.minusYears(WINDOW_YEARS_BACK))
                && !examDate.isAfter(today.plusYears(WINDOW_YEARS_AHEAD));
    }

    /** 两格都空 —— 等同于没设过(§5.4:不分,本来就是同一件事)。 */
    public boolean isEmpty() {
        return examType == null && examDate == null;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
