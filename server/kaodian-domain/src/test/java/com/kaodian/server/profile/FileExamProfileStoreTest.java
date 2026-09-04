package com.kaodian.server.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 备考档案落盘的测试 —— 形态照抄 {@code FileAssertionStoreTest},<b>只验不一样的那几处</b>。
 *
 * <p>原子写、临时文件、并发写锁那几条与行为层是同一份实现,不在这里重验一遍:
 * <b>两份互相抄的断言会一起腐烂</b>。这里管四件事:
 * 覆盖是<b>就地覆盖</b>(库里永远只有一行,没有历史)、两格各自独立可空、
 * 两个用户互不串行、以及闭集与日期窗口这两处算术。
 */
class FileExamProfileStoreTest {

    private static final Instant EARLIER = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-25T12:00:00Z");

    private static final LocalDate EXAM_DAY = LocalDate.parse("2027-11-28");

    /** 测试用户 —— 与其它几张表同一个 id(B0 §3.3:auth 侧从 10001 起号)。 */
    private static final long USER = 10001L;

    /** 另一个真实存在的用户 —— 用来证明「按用户查」不是摆设。 */
    private static final long OTHER_USER = 10002L;

    @TempDir
    Path dataDir;

    private FileExamProfileStore store() {
        return new FileExamProfileStore(dataDir.resolve("exam-profiles.json"));
    }

    // ———————————————————— 一、没有种子 ————————————————————

    @Test
    @DisplayName("🔴 没有种子,而且第一次访问不写盘 —— 没有人「默认要考国考」")
    void anAbsentFileIsAnEmptyTableAndStaysAbsent() {
        FileExamProfileStore store = store();

        assertNull(store.find(USER), "从没设过必须是 null —— 端靠「响应体是空对象」判该不该出档案屏");
        assertFalse(Files.exists(store.dataFile()), "只是读了一下,不该在用户目录里造出文件");
    }

    @Test
    @DisplayName("userId 必须是正数 —— 0 不是「暂时没有用户」")
    void userIdMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> store().find(0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ExamProfile(0L, ExamProfile.NATIONAL, EXAM_DAY, EARLIER));
    }

    // ———————————————————— 二、往返 ————————————————————

    @Test
    @DisplayName("写进去、换个实例读出来,四个字段一个不少")
    void aProfileSurvivesAReopen() {
        store().put(new ExamProfile(USER, ExamProfile.NATIONAL, EXAM_DAY, EARLIER));

        ExamProfile read = store().find(USER);

        assertNotNull(read);
        assertEquals(USER, read.userId());
        assertEquals(ExamProfile.NATIONAL, read.examType());
        assertEquals(EXAM_DAY, read.examDate());
        assertEquals(EARLIER, read.updatedAt());
    }

    @Test
    @DisplayName("🔴 examDate 是一个日期,不是一个时刻 —— 落盘写的是 YYYY-MM-DD,没有时分秒")
    void theDateIsStoredAsADateNotATimestamp() throws IOException {
        FileExamProfileStore store = store();
        store.put(new ExamProfile(USER, "32", EXAM_DAY, EARLIER));

        String raw = Files.readString(store.dataFile(), StandardCharsets.UTF_8);

        assertTrue(raw.contains("2027-11-28"), "日期没落进文件里:" + raw);
        assertFalse(raw.contains("2027-11-28T"),
                "🔴 日期后面跟上了时分秒 —— 契约写的是 date 不是 datetime(§八):" + raw);
    }

    // ———————————————————— 三、覆盖就地发生,没有历史 ————————————————————

    /**
     * 🔴 这一条如果被删掉或改松,文件里就会攒出一条时间轴 —— 那就是「你的备考轨迹」。
     *
     * <p>断言的是<b>文件里只有一行</b>,而不是「读出来的是新值」:
     * 追加一行再按时间取最新的实现,后者照样绿,前者当场红。
     * 留了历史之后,有人只需要写一个读取方法就能把它端上屏(接口契约 §12.9.1:不留历史)。
     */
    @Test
    @DisplayName("🔴 改一次覆盖一次:库里永远只有一行,不留历史")
    void rewritingOverwritesInPlaceAndKeepsNoHistory() throws IOException {
        FileExamProfileStore store = store();
        store.put(new ExamProfile(USER, ExamProfile.NATIONAL, EXAM_DAY, EARLIER));
        store.put(new ExamProfile(USER, "32", LocalDate.parse("2027-03-14"), LATER));

        ExamProfile read = store().find(USER);
        assertEquals("32", read.examType());
        assertEquals(LocalDate.parse("2027-03-14"), read.examDate());
        assertEquals(LATER, read.updatedAt(), "覆盖时 updatedAt 跟着动 —— 它不是历史,只有一份");

        String raw = Files.readString(store.dataFile(), StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(raw, "\"userId\""),
                "🔴 文件里出现了第二行 —— 攒出来的就是「你的备考轨迹」,那是学习分析(§12.9.1)");
        assertFalse(raw.contains("national"),
                "🔴 上一次选的场次还留在文件里 —— 覆盖必须是就地替换,不是追加");
    }

    // ———————————————————— 四、两格互不依赖 ————————————————————

    /**
     * 🔴 只填日期不选场次是合法的,反过来也是(§八:两字段互不依赖)。
     *
     * <p>逐个方向验,而不是只验「两个都填」那一条:「必须两个都填」的校验一旦被谁加进来,
     * 只有这两条会红。
     */
    @Test
    @DisplayName("🔴 两格互不依赖:只有日期、只有场次,都能存住")
    void eitherFieldAloneIsLegal() {
        FileExamProfileStore store = store();

        store.put(new ExamProfile(USER, null, EXAM_DAY, EARLIER));
        assertNull(store().find(USER).examType(), "只填日期不选场次是合法的");
        assertEquals(EXAM_DAY, store().find(USER).examDate());

        store.put(new ExamProfile(USER, "44", null, LATER));
        assertEquals("44", store().find(USER).examType(), "只选场次不填日期同样合法");
        assertNull(store().find(USER).examDate());
    }

    @Test
    @DisplayName("🔴 单独清空一格,另一格原样留着 —— 全量覆盖不是「全清」")
    void clearingOneFieldLeavesTheOther() {
        FileExamProfileStore store = store();
        store.put(new ExamProfile(USER, ExamProfile.NATIONAL, EXAM_DAY, EARLIER));

        store.put(new ExamProfile(USER, null, EXAM_DAY, LATER));

        ExamProfile read = store().find(USER);
        assertNull(read.examType(), "场次该被清掉了");
        assertEquals(EXAM_DAY, read.examDate(), "日期是这次请求带着的,不该跟着一起没了");
    }

    @Test
    @DisplayName("两格都空照样落一行,而且读出来仍然是「空」——「清空」是一次真实的写入")
    void clearingBothStillWritesARow() {
        FileExamProfileStore store = store();
        store.put(new ExamProfile(USER, ExamProfile.NATIONAL, EXAM_DAY, EARLIER));

        store.put(new ExamProfile(USER, null, null, LATER));

        ExamProfile read = store().find(USER);
        assertNotNull(read, "清空不是删行");
        assertTrue(read.isEmpty());
        assertEquals(LATER, read.updatedAt());
    }

    @Test
    @DisplayName("空白串等同于没填 —— 不会在库里留下一个空字符串场次")
    void blankIsTreatedAsAbsent() {
        assertNull(new ExamProfile(USER, "   ", null, EARLIER).examType());
    }

    // ———————————————————— 五、两个用户互不串 ————————————————————

    @Test
    @DisplayName("🔴 两个人的档案互不干扰:改一个不动另一个")
    void twoUsersAreIsolated() {
        FileExamProfileStore store = store();
        store.put(new ExamProfile(USER, ExamProfile.NATIONAL, EXAM_DAY, EARLIER));
        store.put(new ExamProfile(OTHER_USER, "32", LocalDate.parse("2027-03-14"), EARLIER));

        store.put(new ExamProfile(USER, "44", null, LATER));

        assertEquals("44", store().find(USER).examType());
        assertEquals("32", store().find(OTHER_USER).examType(),
                "🔴 覆盖按 userId 判 —— 按别的判法会让一个人的选择盖掉另一个人的");
        assertEquals(LocalDate.parse("2027-03-14"), store().find(OTHER_USER).examDate());
    }

    // ———————————————————— 六、闭集与窗口这两处算术 ————————————————————

    @Test
    @DisplayName("🔴 examType 是闭集:national + 34 个省级行政区代码,自造的一律拒")
    void examTypeIsAClosedSet() {
        assertTrue(ExamProfile.isExamType(ExamProfile.NATIONAL));
        assertTrue(ExamProfile.isExamType("32"));
        assertEquals(34, ExamProfile.PROVINCE_CODES.size(),
                "省级行政区一共 34 个(GB/T 2260 两位码)—— 少一个就是有个省的用户存不进来");

        for (String bad : new String[]{"jiangsu", "江苏", "320000", "3", "99", "NATIONAL", "国考"}) {
            assertFalse(ExamProfile.isExamType(bad), "不该认的取值被认了:" + bad);
            assertThrows(IllegalArgumentException.class,
                    () -> new ExamProfile(USER, bad, null, EARLIER),
                    "闭集校验必须在领域对象上,不是只在接口层:" + bad);
        }
    }

    /**
     * 🔴 「今天没有考情数据的省」<b>照存,不拒</b>(§12.9.4 / U3.8 §2.5)。
     *
     * <p>{@code examType} 是<b>用户对自己的陈述</b>,不是我们算出来的量 ——
     * 产品没有资格判它错。这一条守的是:将来谁想加一句「这个省我们还没数据,先别存」时,
     * 这里会红。
     */
    @Test
    @DisplayName("🔴 今天没有考情数据的省份:照存不拒,库里读得回来")
    void aProvinceWithoutStatsDataIsStoredNotRejected() {
        store().put(new ExamProfile(USER, "54", EXAM_DAY, EARLIER));   // 西藏
        assertEquals("54", store().find(USER).examType());
    }

    @Test
    @DisplayName("日期窗口:今天 −1 年 .. 今天 +2 年,闭区间;边界内外各验一天")
    void theDateWindowIsOneYearBackAndTwoYearsAhead() {
        LocalDate today = LocalDate.parse("2026-09-04");

        assertTrue(ExamProfile.withinWindow(today, today));
        assertTrue(ExamProfile.withinWindow(today, LocalDate.parse("2025-09-04")), "整一年前:闭区间,在窗口内");
        assertTrue(ExamProfile.withinWindow(today, LocalDate.parse("2028-09-04")), "整两年后:闭区间,在窗口内");

        assertFalse(ExamProfile.withinWindow(today, LocalDate.parse("2025-09-03")), "早一天就出窗口了");
        assertFalse(ExamProfile.withinWindow(today, LocalDate.parse("2028-09-05")), "晚一天就出窗口了");
        assertFalse(ExamProfile.withinWindow(today, LocalDate.parse("2029-09-04")), "三年后:年份多半敲错了一位");
    }

    // ———————————————————— 七、红线:没有任何派生天数 ————————————————————

    /**
     * 🔴 {@code U3.8} §2.4 的第一道防线在领域层这一侧的形状:
     * <b>{@link ExamProfile} 上不许有一个算天数的方法</b>。
     *
     * <p>契约层那道(响应体里没有这个字段)在 {@code ExamProfileApiTest} 里。
     * 两处独立断言,少一处就会在重构时悄悄失守 —— 领域对象上先长出
     * {@code daysUntilExam()},下一个人把它加进 DTO 时只会觉得「现成的,顺手」。
     */
    @Test
    @DisplayName("🔴 ExamProfile 上没有任何算天数的方法 —— 只给绝对日期")
    void theDomainObjectExposesNoDayCount() {
        for (Method m : ExamProfile.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            assertFalse(name.contains("day") || name.contains("remain") || name.contains("countdown"),
                    "🔴 长出了一个派生天数:" + m.getName()
                            + " —— 天数一旦上了屏,能和它搭配的只可能是复习提醒或紧迫感文案(U3.8 §2.4)");
        }
    }

    // ———————————————————— 八、坏文件不静默变空 ————————————————————

    @Test
    @DisplayName("🔴 认不出来就吵着失败,绝不当成 0 行 —— 否则下一次写入会盖掉真实档案")
    void aBrokenFileIsNotSilentlyTreatedAsEmpty() throws IOException {
        FileExamProfileStore store = store();
        Files.writeString(store.dataFile(), "{\"records\":[]}", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> store.find(USER));
    }

    @Test
    @DisplayName("⚠️「可以没有」与「可以是垃圾」是两件事:日期写坏了照样抛")
    void anUnparseableDateIsStillABrokenFile() throws IOException {
        FileExamProfileStore store = store();
        Files.writeString(store.dataFile(),
                "{\"profiles\":[{\"userId\":10001,\"examDate\":\"2027-13-45\","
                        + "\"updatedAt\":\"2026-08-20T09:00:00Z\"}]}",
                StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> store.find(USER));
    }

    @Test
    @DisplayName("两个业务字段缺席不是坏文件 —— 那是「已清空」这个合法状态")
    void absentBusinessFieldsAreNotABrokenFile() throws IOException {
        FileExamProfileStore store = store();
        Files.writeString(store.dataFile(),
                "{\"profiles\":[{\"userId\":10001,\"updatedAt\":\"2026-08-20T09:00:00Z\"}]}",
                StandardCharsets.UTF_8);

        ExamProfile read = store.find(USER);
        assertNotNull(read);
        assertTrue(read.isEmpty());
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
