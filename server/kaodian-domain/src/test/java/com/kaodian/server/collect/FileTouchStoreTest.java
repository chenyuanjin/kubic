package com.kaodian.server.collect;

import com.kaodian.server.coverage.BlindspotFilter;
import com.kaodian.server.coverage.BlindspotOrder;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.coverage.CoverageService.GroupCoverage;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.coverage.CoverageService.Summary;
import com.kaodian.server.coverage.NodeState;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件存储 + 行为层种子。
 *
 * <p>这个类里最重要的一个测试是 {@link #seedIsTheDesignContract} ——
 * 它是<b>种子文件与设计契约之间的锁</b>:设计稿 49 屏全按 18/8/44% 画的,
 * 种子改一个数、少一条记录,这里立刻红。
 */
class FileTouchStoreTest {

    @TempDir
    Path dataDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 种子那 8 条全归这个 id(B0 §3.3:auth 侧从 10001 起号),所以按用户查得出来的就是它们。 */
    private static final long USER = 10001L;

    /** 另一个真实存在的用户 —— 用来证明「按用户查」不是摆设。 */
    private static final long OTHER_USER = 10002L;

    private FileTouchStore store() {
        return new FileTouchStore(dataDir.resolve("touches.json"));
    }

    private Path file() {
        return dataDir.resolve("touches.json");
    }

    /**
     * {@code findByNode} 随 B0-3 拆成了「按用户查全部」与「跨用户计数」两个方法,
     * 所以这里自己过滤 —— 这些断言问的一直是「<b>这个用户</b>在那个考点上有几条」。
     */
    private List<Touch> onNode(FileTouchStore store, String nodeCode) {
        return store.findAll(USER).stream().filter(t -> t.nodeCode().equals(nodeCode)).toList();
    }

    // ——————————————————— 种子 ———————————————————

    @Test
    @DisplayName("首次访问自动播种,并把文件落到配置的目录里")
    void seedsOnFirstAccess() {
        FileTouchStore store = store();
        assertFalse(Files.exists(file()), "构造 bean 不该有副作用 —— 播种推迟到第一次访问");

        assertEquals(8, store.count(USER), "种子:8 个考点有记录");
        assertTrue(Files.exists(file()), "播种后文件应当已经落盘");
        assertEquals(file().toAbsolutePath(), store.dataFile());
    }

    /**
     * 🔒 <b>种子文件与数据契约之间的锁</b> —— 落盘的那 8 条,原样喂进差集必须还是那几个数。
     *
     * <p>这个测试的对象<b>不是</b> {@code CoverageService}(那边由 {@code CoverageServiceTest} 钉),
     * 而是<b>这条链路</b>:种子 → 落盘 → 读回 → 派生标签 → 差集。中间任何一步吃掉一条记录,
     * 覆盖度都会安静地少一格,而没有任何一处报错。
     *
     * <p>⚠️ 上一版这里还断言过 {@code 44%} 与「稳 3 · 弱 2 · 生疏 2」。两组都已不存在:
     * 前者是浮点(§7.2 这一域没有百分比),后者从「练了几道 / 对了几道」推出来,
     * 回答的是「答得怎么样」(§7.4 已整个移出这一域)。
     */
    @Test
    @DisplayName("🔒 种子经 CoverageService 算出来就是 18 个考点 / 8 个碰过 / 10 个没碰过 / 2 组整块空白")
    void seedIsTheDesignContract() {
        List<Touch> touches = store().findAll(USER);
        assertEquals(8, touches.size());

        Syllabus syllabus = SyllabusLoader.loadDefault();
        CoverageService service = new CoverageService();
        // 走 effectiveTagsOf 派生主标签 —— 与 CoverageReader.read 同一条路。
        // 直接把空标签表递进去的话,这 8 条记录一条都不计覆盖度,而差集会安静地变成 18/0/18。
        List<GroupCoverage> groups = service.compute(syllabus, touches,
                RecordTag.effectiveTagsOf(touches, List.of()), List.of(), Instant.now());
        Summary s = service.summarize(groups);

        assertEquals(18, s.nodeTotal(), "考点总数");
        assertEquals(8, s.nodeTouched(), "碰过");
        assertEquals(10, s.nodeUntouched(), "没碰过 —— 数出来的,不是 18−8 减出来的");
        assertEquals(0, s.archivedCount());
        assertEquals(0, s.assertedCount());
        assertEquals(2, groups.stream().filter(GroupCoverage::whollyEmpty).count(), "整块空白的题型组数");

        // 那条 VOICE 记录(听过课、一道题没练)必须是「碰过」——
        // 它与「完全没碰过」分开,正是这份种子存在的理由,差集没有这条线就没有意义。
        assertEquals(NodeState.TOUCHED, node(groups, "share-change").state());
        assertEquals(NodeState.UNTOUCHED, node(groups, "average-calc").state());

        // 「先补这几个」也一并锁住:默认档 = 没碰过 + 按近五年出现次数
        List<NodeCoverage> top = service.blindSpots(
                groups, BlindspotOrder.RECENT5Y_COUNT, BlindspotFilter.UNTOUCHED, false, 5);
        assertEquals(List.of("平均数计算", "现期量计算", "倍数计算", "年均增长率", "同比与环比"),
                top.stream().map(NodeCoverage::name).toList());
        assertEquals(6, top.get(0).recent5yCount(), "榜首的出现次数");
    }

    @Test
    @DisplayName("种子的相对天数是相对【播种那一刻】的 —— 不写死日期,过几天状态才不会漂")
    void seedDaysAreRelativeToSeedingMoment() {
        Instant before = Instant.now();
        List<Touch> touches = store().findAll(USER);

        Touch newest = touches.get(touches.size() - 1);
        assertFalse(newest.occurredAt().isBefore(before.minusSeconds(5)), "最新一条应当就是「今天」");

        Touch oldest = touches.get(0);
        long days = java.time.Duration.between(oldest.occurredAt(), Instant.now()).toDays();
        // 「多久前」是这一域的三件事之一,而服务端只给绝对时刻(lastTouchAt),天数由端算。
        // 种子写死日期的话,这个跨度会随时间漂,端上那句「33 天前」跟着变成任意一个数。
        assertEquals(33, days, "最旧一条必须正好是 33 天前 —— 相对天数是相对播种那一刻算的");
    }

    @Test
    @DisplayName("🔴 种子里只有来源名与时间 —— 逐个键检查,不允许出现任何装内容的位置")
    void seedCarriesNoCourseContent() {
        // userId 是 B0-3 的租户列 —— 它是归属,不是内容,而且没有它这 8 条会被当成无归属数据丢弃
        Set<String> allowed = Set.of("id", "userId", "nodeCode", "sourceName", "kind",
                "daysAgo", "practiced", "correct");
        Set<String> forbidden = Set.of("content", "text", "body", "question", "transcript", "answer",
                "explanation", "note", "imageUrl", "image", "fileId", "url", "audioUrl");

        JsonNode root = readSeedResource();
        int count = 0;
        for (JsonNode t : root.path("touches")) {
            count++;
            for (String key : t.propertyNames()) {
                assertTrue(allowed.contains(key),
                        "种子里出现了契约之外的键(决策记录 §2.2 不碰内容):" + key);
                assertFalse(forbidden.contains(key), "禁止的键:" + key);
            }
        }
        assertEquals(8, count, "种子必须正好 8 条 —— 覆盖率 8/18 = 44% 靠它");
    }

    @Test
    @DisplayName("🔴 有人手工往数据文件里塞内容也没用 —— 读取只认那几个键,进不了内存也回不到文件")
    void handEditedContentIsNeverRead() throws Exception {
        Files.createDirectories(dataDir);
        Files.writeString(file(), """
                {
                  "touches": [
                    {
                      "id": "hand-1",
                      "userId": 10001,
                      "nodeCode": "growth-rate",
                      "sourceName": "某机构",
                      "kind": "DRILL",
                      "occurredAt": "2026-08-20T10:00:00Z",
                      "practiced": 4,
                      "correct": 3,
                      "question": "2023 年 GDP 同比增长率是多少",
                      "transcript": "老师讲了半小时"
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        FileTouchStore store = store();
        List<Touch> touches = store.findAll(USER);
        assertEquals(1, touches.size());
        assertEquals("growth-rate", touches.get(0).nodeCode());

        // 追加一条触发全量重写:塞进去的内容不会被写回去,它到此为止
        store.append(new Touch("hand-2", USER, "share-calc", "某机构", TouchKind.MANUAL, Instant.now(), null, null));
        String written = Files.readString(file(), StandardCharsets.UTF_8);
        assertFalse(written.contains("question"), "重写后文件里不该再有内容字段");
        assertFalse(written.contains("老师讲了半小时"));
    }

    // ——————————————————— 读写 ———————————————————

    @Test
    @DisplayName("已经存在的数据文件不会被种子覆盖 —— 播种只发生一次")
    void existingFileIsNeverReseeded() throws Exception {
        Files.createDirectories(dataDir);
        Files.writeString(file(), "{\"touches\":[]}", StandardCharsets.UTF_8);

        assertEquals(0, store().count(USER), "文件已存在就照读,哪怕它是空的");
    }

    @Test
    @DisplayName("追加后换一个实例照样读得到 —— 文件是唯一事实来源")
    void appendSurvivesReopen() {
        FileTouchStore first = store();
        first.append(new Touch("t-new", USER, "average-calc", "自己刷题",
                TouchKind.DRILL, Instant.now(), new Touch.Drill(5, 5), null));

        FileTouchStore second = store();
        assertEquals(9, second.count(USER));
        assertEquals(1, onNode(second, "average-calc").size());
        assertEquals(5, onNode(second, "average-calc").get(0).drill().correct());
    }

    @Test
    @DisplayName("写入是先临时文件再原子 rename —— 写完不留 .tmp 残骸")
    void appendLeavesNoTempFile() throws Exception {
        FileTouchStore store = store();
        store.append(new Touch("t-x", USER, "yoy-mom", "某来源", TouchKind.MANUAL, Instant.now(), null, null));

        try (var entries = Files.list(dataDir)) {
            List<String> names = entries.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("touches.json"), names, "目录里只该有数据文件本身");
        }
    }

    @Test
    @DisplayName("findAll 按发生时间升序,findByNode 只给那个考点的")
    void ordersAndFilters() {
        FileTouchStore store = store();
        store.append(new Touch("t-later", USER, "growth-rate", "自己刷题",
                TouchKind.DRILL, Instant.now().plusSeconds(60), new Touch.Drill(2, 2), null));

        List<Touch> all = store.findAll(USER);
        for (int i = 1; i < all.size(); i++) {
            assertFalse(all.get(i).occurredAt().isBefore(all.get(i - 1).occurredAt()), "必须升序");
        }
        assertEquals(all.get(all.size() - 1).id(), "t-later");
        assertEquals(2, onNode(store, "growth-rate").size());
        assertEquals(0, onNode(store, "mixed-growth").size());
    }

    // ——————————————————— 🔴 B0-3 租户列 ———————————————————

    @Test
    @DisplayName("🔴 userId 不是正数就构造不出来 —— 0 不是「暂时没有用户」,它根本不是一个合法 id")
    void aTouchWithoutAPositiveUserIdCannotExist() {
        // 拦在构造器上,而不是拦在某个 service 的入口:一条没有归属的记录一旦存在,
        // 它要么被算进别人的覆盖度,要么在读取时被静默丢弃(B0 §4.4)——两条都不该由默认值决定。
        for (long bad : new long[]{0L, -1L, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Touch("t-a", bad, "average-calc", "自己刷题",
                            TouchKind.MANUAL, Instant.now(), null, null),
                    "userId=" + bad);
        }
    }

    @Test
    @DisplayName("🔴 别人的记录读不到、也删不掉 —— 归属查错方向是「看见了不属于自己的东西」")
    void oneUserNeverSeesOrDeletesAnotherUsersRecords() {
        // 覆盖度是按人算的,所以「查漏一条」只是数字小一点,「查多一条」是把别人学过的东西
        // 记到这个人头上 —— 而两者在界面上都只是一个不一样的百分比,没有任何一处报错。
        FileTouchStore store = store();
        store.append(new Touch("t-other", OTHER_USER, "average-calc", "自己刷题",
                TouchKind.MANUAL, Instant.now(), null, null));

        assertEquals(8, store.count(USER), "种子那 8 条归 10001,另一个人那条不该混进来");
        assertEquals(1, store.count(OTHER_USER));
        assertEquals(0, onNode(store, "average-calc").size());

        assertNull(store.delete(USER, "t-other"), "拿着别人的记录 id 也删不动它");
        assertEquals(1, store.count(OTHER_USER), "而且那条一个字都没被动");

        // 跨用户那条路仍然数得到两个人 —— 它是给「删考点前还有没有记录」用的,不按人过滤
        assertEquals(1, store.countByNodeAcrossUsers("average-calc"));
    }

    @Test
    @DisplayName("🔴 两个人用同一个 clientToken 不会互相判重 —— 去重键由客户端生成,不保证跨用户唯一")
    void theSameClientTokenFromTwoUsersLandsTwice() {
        // 离线队列的去重键是客户端自己造的(时间戳、自增号都可能撞)。
        // 只按 token 判重的话,后到的那个人会收到「你已经记过了」并拿回别人的那条记录 ——
        // 这既丢了他的记录,又把别人的记录泄露给了他。
        FileTouchStore store = store();
        Touch mine = store.append(new Touch("t-mine", USER, "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now(), null, "offline-001"));
        Touch theirs = store.append(new Touch("t-theirs", OTHER_USER, "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now(), null, "offline-001"));

        assertNotEquals(mine.id(), theirs.id(), "两条都得落地,不能被当成同一次补传");
        assertEquals(9, store.count(USER));
        assertEquals(1, store.count(OTHER_USER));
        assertEquals("t-mine", store.findByClientToken(USER, "offline-001").id());
        assertEquals("t-theirs", store.findByClientToken(OTHER_USER, "offline-001").id());
    }

    // ——————————————————— 🔴 幂等:同一个 clientToken 只落一条 ———————————————————

    @Test
    @DisplayName("🔴 同一个 clientToken 追加两次 → 返回原来那条,库里只多一条")
    void sameClientTokenAppendsOnce() {
        FileTouchStore store = store();
        Touch first = store.append(new Touch("t-a", USER, "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now(), null, "offline-001"));

        // 补传时客户端并不知道服务端给的 id,它重发的是【另一条】记录,只是去重键相同
        Touch again = store.append(new Touch("t-b", USER, "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now().plusSeconds(3600), null, "offline-001"));

        assertEquals(first.id(), again.id(), "返回的必须是原来那条");
        assertEquals(9, store.count(USER), "多一条就等于覆盖度的分子被数了两次");
        // 🔴 不覆盖:补传那份带的是补传时刻的时间戳,拿它盖掉第一次的 occurredAt
        //    等于让一条记录凭空变年轻,而「多久前」是五态里唯一的时间依据
        assertEquals(first.occurredAt(), again.occurredAt());
    }

    @Test
    @DisplayName("🔴 clientToken 落盘 —— 进程重启之后那条补传仍然重复不了")
    void clientTokenSurvivesReopen() throws Exception {
        store().append(new Touch("t-a", USER, "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now(), null, "offline-001"));

        assertTrue(Files.readString(file(), StandardCharsets.UTF_8).contains("offline-001"),
                "只留在内存里的话,进程一重启那批记录就能再补传一次");

        FileTouchStore reopened = store();
        assertNotNull(reopened.findByClientToken(USER, "offline-001"));
        reopened.append(new Touch("t-b", USER, "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now(), null, "offline-001"));
        assertEquals(9, reopened.count(USER));
    }

    @Test
    @DisplayName("🔴 没有 clientToken 的记录之间永不判重 —— 空的去重键不是一个能互相匹配的值")
    void recordsWithoutTokenNeverMatchEachOther() {
        FileTouchStore store = store();
        store.append(new Touch("t-a", USER, "average-calc", "自己刷题", TouchKind.MANUAL, Instant.now(), null, null));
        store.append(new Touch("t-b", USER, "average-calc", "自己刷题", TouchKind.MANUAL, Instant.now(), null, null));

        // 判重的失败方向只能是「多一条」:多一条用户看得见、删得掉;
        // 少一条是他记了却没记上,而他不会知道。种子 8 条里也都没有去重键。
        assertEquals(10, store.count(USER));
        assertNull(store.findByClientToken(USER, null));
        assertNull(store.findByClientToken(USER, "   "));
    }

    @Test
    @DisplayName("🔴 空白 clientToken 被归一成「没有」,不会让两条不相干的记录互相判重")
    void blankClientTokenIsNormalisedToAbsent() {
        FileTouchStore store = store();
        store.append(new Touch("t-a", USER, "average-calc", "自己刷题", TouchKind.MANUAL, Instant.now(), null, "  "));
        store.append(new Touch("t-b", USER, "yoy-mom", "自己刷题", TouchKind.MANUAL, Instant.now(), null, ""));

        assertEquals(10, store.count(USER), "两条都要在 —— 它们只是都没填去重键,不是同一条");
        assertFalse(Files.exists(file()) && store.findAll(USER).stream()
                        .anyMatch(t -> "".equals(t.clientToken()) || "  ".equals(t.clientToken())),
                "空白串不该原样留在记录里");
    }

    @Test
    @DisplayName("去重键有长度上限 —— 它是个 id,不是放内容的地方(R-01)")
    void clientTokenIsLengthCapped() {
        assertThrows(IllegalArgumentException.class,
                () -> new Touch("t-a", USER, "average-calc", "自己刷题", TouchKind.MANUAL,
                        Instant.now(), null, "题".repeat(Touch.MAX_CLIENT_TOKEN_LENGTH + 1)));
    }

    @Test
    @DisplayName("改挂记录时 clientToken 跟着搬 —— 丢了它那条记录就重新变得可以被补传一次")
    void reassignKeepsClientToken() {
        FileTouchStore store = store();
        store.append(new Touch("t-a", USER, "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now(), null, "offline-001"));

        assertEquals(1, store.reassign("average-calc", "yoy-mom"));
        assertNotNull(store.findByClientToken(USER, "offline-001"));
        assertEquals("yoy-mom", store.findByClientToken(USER, "offline-001").nodeCode());
    }

    /**
     * 🔴 这一条是「判重为什么必须在 {@code append} 里」的全部理由。
     *
     * <p>把它挪到调用方去做「先查再写」,查和写之间就有一个窗口。而离线队列补传<b>本身就是重发</b>:
     * 发一半断了、客户端不确定服务端收没收到、于是整批再发一次 —— 两次请求完全可以叠在一起。
     * 那时两个线程各自查到「没有」,然后各自写一条,用户看到的是记录变成了双份。
     */
    @Test
    @DisplayName("🔴 并发用同一个 clientToken 追加 —— 只能落一条")
    void concurrentAppendsWithTheSameTokenLandOnce() throws Exception {
        FileTouchStore store = store();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int id = t;
            new Thread(() -> {
                try {
                    start.await();
                    store.append(new Touch("t-race-" + id, USER, "average-calc", "地铁上",
                            TouchKind.MANUAL, Instant.now(), null, "offline-001"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发追加超时");

        assertEquals(9, store.count(USER), "8 个线程抢同一个去重键,只能落一条");
        assertEquals(9, store().count(USER), "磁盘上也只能有一条");
    }

    // ——————————————————— 删记录 ———————————————————

    @Test
    @DisplayName("delete 删掉那一条并落盘;换个实例读出来也少了那条")
    void deleteRemovesExactlyOneAndPersists() {
        FileTouchStore store = store();
        Touch gone = store.delete(USER, "seed-share-change");

        assertNotNull(gone);
        assertEquals("share-change", gone.nodeCode(), "返回被删的那条 —— 调用方要靠它知道哪个考点要重算");
        assertEquals(7, store.count(USER));
        assertEquals(7, store().count(USER), "磁盘上也要少一条");
        assertEquals(0, onNode(store, "share-change").size());
    }

    @Test
    @DisplayName("删一条不存在的记录返回 null,不抛异常,也不写盘")
    void deletingAMissingRecordIsNotAFailure() throws Exception {
        FileTouchStore store = store();
        store.count(USER);                                  // 先触发播种,让文件落地
        long before = Files.getLastModifiedTime(file()).toMillis();

        assertNull(store.delete(USER, "t-不存在"), "「删一条不存在的记录」是调用方要分辨的情况,不是服务端的故障");
        assertEquals(8, store.count(USER));
        assertEquals(before, Files.getLastModifiedTime(file()).toMillis(), "什么都没变就不该写盘");
    }

    @Test
    @DisplayName("并发追加一条都不能丢 —— 记录是这个产品的全部资产")
    void concurrentAppendsLoseNothing() throws Exception {
        FileTouchStore store = store();
        int threads = 6;
        int perThread = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.append(new Touch("t-" + id + "-" + i, USER, "growth-rate", "自己刷题",
                                TouchKind.MANUAL, Instant.now(), null, null));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发追加超时");
        for (Thread w : workers) {
            w.join();
        }

        int expected = 8 + threads * perThread;
        assertEquals(expected, store.count(USER), "内存里一条不少");
        assertEquals(expected, store().count(USER), "磁盘上也一条不少");

        Set<String> ids = new HashSet<>();
        store().findAll(USER).forEach(t -> ids.add(t.id()));
        assertEquals(expected, ids.size(), "id 不该有覆盖");
    }

    @Test
    @DisplayName("默认数据目录是 ~/.kaodian,且可配置 —— 「我的数据存在哪」必须有确定答案")
    void dataDirIsResolvableAndConfigurable() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(Clock.class, Clock::systemUTC);
            ctx.register(PropertySourcesPlaceholderConfigurer.class, FileTouchStore.class);
            ctx.refresh();
            assertEquals(Path.of(System.getProperty("user.home"), ".kaodian", "touches.json"),
                    ctx.getBean(FileTouchStore.class).dataFile(),
                    "占位符解析不了的话,第一次真跑起来才会炸 —— 那太晚了");
        }

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("kaodian.data.dir", dataDir.toString())));
            ctx.registerBean(Clock.class, Clock::systemUTC);
            ctx.register(PropertySourcesPlaceholderConfigurer.class, FileTouchStore.class);
            ctx.refresh();
            FileTouchStore bean = ctx.getBean(FileTouchStore.class);
            assertEquals(file().toAbsolutePath(), bean.dataFile());
            assertFalse(Files.exists(file()), "只是造了个 bean,还没访问过 —— 不该写任何文件");
        }
    }

    @Test
    @DisplayName("坏文件要吵着失败,不能悄悄当成空数据 —— 那等于静默丢光记录")
    void corruptFileFailsLoudly() throws Exception {
        Files.createDirectories(dataDir);
        Files.writeString(file(), "{ 这不是 JSON", StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> store().findAll(USER));
    }

    @Test
    @DisplayName("🔴 认不出来的文件也要吵着失败 —— touches 键没了不能当成 0 条,否则下一次追加会盖掉真实记录")
    void unrecognisableFileNeverDegradesToEmpty() throws Exception {
        Files.createDirectories(dataDir);
        // 一份合法 JSON,但结构不认识(手工改坏、或换了个键名)。
        // 旧写法在这里「解析成功、0 条记录」,再 append 一次就把这条真实记录全量重写没了。
        Files.writeString(file(), """
                {
                  "records": [
                    { "id": "real-1", "nodeCode": "growth-rate", "kind": "MANUAL",
                      "occurredAt": "2026-08-20T10:00:00Z" }
                  ]
                }
                """, StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> store().findAll(USER),
                "缺 touches 数组必须炸,不能静默当成空数据");
        assertTrue(Files.readString(file(), StandardCharsets.UTF_8).contains("real-1"),
                "失败之后原文件必须一个字都没被动过");
    }

    @Test
    @DisplayName("坏记录报的是「数据文件坏了」,不是把领域校验消息当成前端的锅")
    void badRecordFailsAsServerSideDataProblem() throws Exception {
        Files.createDirectories(dataDir);
        Files.writeString(file(), """
                {
                  "touches": [
                    { "id": "bad-1", "userId": 10001, "nodeCode": "growth-rate", "kind": "LIVESTREAM",
                      "occurredAt": "2026-08-20T10:00:00Z" }
                  ]
                }
                """, StandardCharsets.UTF_8);

        RuntimeException e = assertThrows(IllegalStateException.class, () -> store().findAll(USER));
        assertFalse(e instanceof IllegalArgumentException,
                "IllegalArgumentException 会被接口层当成 400 并原样回显 —— "
                        + "前端会收到「No enum constant com.kaodian.server.collect.TouchKind.LIVESTREAM」");
        assertTrue(e.getMessage().contains("bad-1"), "报错要指出是哪一条,否则没法修文件");
    }

    /**
     * 🔒 播种必须走<b>注入的</b> {@link Clock},不是 {@code Instant.now()}。
     *
     * <p>「多久前」是这一域的三件事之一,而它唯一的依据是 {@code lastTouchAt} 这个绝对时刻。
     * 播种时偷偷用一次 {@code Instant.now()},固定时钟回放出来的记录就落在<b>另一条时间线</b>上 ——
     * 那一格显示的「多久前」全错,而覆盖度那三个数照样是 18/8/10,<b>没有任何一处会红</b>。
     * 所以这条断言必须钉在时刻上,不能钉在计数上。
     */
    @Test
    @DisplayName("🔒 播种走注入的 Clock —— 固定时钟回放时,种子和差集必须在同一条时间线上")
    void seedFollowsTheInjectedClock() {
        Instant fixed = Instant.parse("2026-07-01T00:00:00Z");
        FileTouchStore store = new FileTouchStore(file(), Clock.fixed(fixed, ZoneOffset.UTC));

        CoverageService service = new CoverageService();
        List<Touch> touches = store.findAll(USER);
        List<GroupCoverage> groups = service.compute(SyllabusLoader.loadDefault(), touches,
                RecordTag.effectiveTagsOf(touches, List.of()), List.of(), fixed);

        // 种子里 daysAgo=0 的那条落在【播种那一刻】,daysAgo=33 的那条落在它之前 33 天。
        assertEquals(fixed, node(groups, "growth-rate").lastTouchAt(), "最新一条就是播种时刻本身");
        assertEquals(fixed.minus(java.time.Duration.ofDays(33)),
                node(groups, "interval-growth").lastTouchAt(), "最旧一条是播种时刻前 33 天");
        assertNull(node(groups, "average-calc").lastTouchAt(), "没碰过的仍然是 null,不是一个假时刻");

        // 时间线对上了,那三个数也得对上 —— 两者分开断言,一个错另一个不会替它遮住
        Summary s = service.summarize(groups);
        assertEquals(18, s.nodeTotal());
        assertEquals(8, s.nodeTouched());
        assertEquals(10, s.nodeUntouched());
    }

    /** 按 code 取那一格的覆盖视图。取不到就让测试当场失败 —— 夹具错了比断言错了更难查。 */
    private static NodeCoverage node(List<GroupCoverage> groups, String code) {
        return groups.stream().flatMap(g -> g.nodes().stream())
                .filter(n -> n.code().equals(code)).findFirst().orElseThrow();
    }

    private static JsonNode readSeedResource() {
        try (InputStream in = FileTouchStore.class.getResourceAsStream("/seed/touches-demo.json")) {
            assertNotNull(in, "找不到行为层种子文件");
            return MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
