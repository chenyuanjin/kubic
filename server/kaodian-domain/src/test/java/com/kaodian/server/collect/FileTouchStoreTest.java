package com.kaodian.server.collect;

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

    private FileTouchStore store() {
        return new FileTouchStore(dataDir.resolve("touches.json"));
    }

    private Path file() {
        return dataDir.resolve("touches.json");
    }

    // ——————————————————— 种子 ———————————————————

    @Test
    @DisplayName("首次访问自动播种,并把文件落到配置的目录里")
    void seedsOnFirstAccess() {
        FileTouchStore store = store();
        assertFalse(Files.exists(file()), "构造 bean 不该有副作用 —— 播种推迟到第一次访问");

        assertEquals(8, store.count(), "种子:8 个考点有记录");
        assertTrue(Files.exists(file()), "播种后文件应当已经落盘");
        assertEquals(file().toAbsolutePath(), store.dataFile());
    }

    @Test
    @DisplayName("🔒 种子经 CoverageService 算出来就是 18/8/44%,五态 稳3·弱2·生疏2·仅接触1·空白10")
    void seedIsTheDesignContract() {
        List<Touch> touches = store().findAll();
        assertEquals(8, touches.size());

        Syllabus syllabus = SyllabusLoader.loadDefault();
        CoverageService service = new CoverageService();
        List<GroupCoverage> groups = service.compute(syllabus, touches, Instant.now());
        Summary s = service.summarize(groups);

        assertEquals(18, s.total(), "考点总数");
        assertEquals(8, s.covered(), "有记录");
        assertEquals(10, s.empty(), "空白");
        assertEquals(44, s.percent(), "覆盖率(设计稿上那个大字)");
        assertEquals(2, s.whollyEmptyGroups(), "整块空白的题型组数");

        assertEquals(3, s.distribution().get(NodeState.STABLE), "稳");
        assertEquals(2, s.distribution().get(NodeState.WEAK), "弱");
        assertEquals(2, s.distribution().get(NodeState.RUSTY), "生疏");
        assertEquals(1, s.distribution().get(NodeState.TOUCHED_ONLY), "仅接触");
        assertEquals(10, s.distribution().get(NodeState.EMPTY), "空白");

        // 「先补这几个」也一并锁住:状态对了,排序分才会对
        List<NodeCoverage> top = service.blindSpots(groups, 5);
        assertEquals(List.of("增长量计算", "平均数计算", "截位直除", "现期量计算", "倍数计算"),
                top.stream().map(NodeCoverage::name).toList());
        assertEquals(6.4, top.get(0).blindScore(), 1e-9);
    }

    @Test
    @DisplayName("种子的相对天数是相对【播种那一刻】的 —— 不写死日期,过几天状态才不会漂")
    void seedDaysAreRelativeToSeedingMoment() {
        Instant before = Instant.now();
        List<Touch> touches = store().findAll();

        Touch newest = touches.get(touches.size() - 1);
        assertFalse(newest.occurredAt().isBefore(before.minusSeconds(5)), "最新一条应当就是「今天」");

        Touch oldest = touches.get(0);
        long days = java.time.Duration.between(oldest.occurredAt(), Instant.now()).toDays();
        assertEquals(33, days, "最旧一条是 33 天前 —— 它必须落在 RUSTY_AFTER(30 天)之外");
    }

    @Test
    @DisplayName("🔴 种子里只有来源名与时间 —— 逐个键检查,不允许出现任何装内容的位置")
    void seedCarriesNoCourseContent() {
        Set<String> allowed = Set.of("id", "nodeCode", "sourceName", "kind", "daysAgo", "practiced", "correct");
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
        List<Touch> touches = store.findAll();
        assertEquals(1, touches.size());
        assertEquals("growth-rate", touches.get(0).nodeCode());

        // 追加一条触发全量重写:塞进去的内容不会被写回去,它到此为止
        store.append(new Touch("hand-2", "share-calc", "某机构", TouchKind.MANUAL, Instant.now(), null));
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

        assertEquals(0, store().count(), "文件已存在就照读,哪怕它是空的");
    }

    @Test
    @DisplayName("追加后换一个实例照样读得到 —— 文件是唯一事实来源")
    void appendSurvivesReopen() {
        FileTouchStore first = store();
        first.append(new Touch("t-new", "average-calc", "自己刷题",
                TouchKind.DRILL, Instant.now(), new Touch.Drill(5, 5)));

        FileTouchStore second = store();
        assertEquals(9, second.count());
        assertEquals(1, second.findByNode("average-calc").size());
        assertEquals(5, second.findByNode("average-calc").get(0).drill().correct());
    }

    @Test
    @DisplayName("写入是先临时文件再原子 rename —— 写完不留 .tmp 残骸")
    void appendLeavesNoTempFile() throws Exception {
        FileTouchStore store = store();
        store.append(new Touch("t-x", "yoy-mom", "某来源", TouchKind.MANUAL, Instant.now(), null));

        try (var entries = Files.list(dataDir)) {
            List<String> names = entries.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("touches.json"), names, "目录里只该有数据文件本身");
        }
    }

    @Test
    @DisplayName("findAll 按发生时间升序,findByNode 只给那个考点的")
    void ordersAndFilters() {
        FileTouchStore store = store();
        store.append(new Touch("t-later", "growth-rate", "自己刷题",
                TouchKind.DRILL, Instant.now().plusSeconds(60), new Touch.Drill(2, 2)));

        List<Touch> all = store.findAll();
        for (int i = 1; i < all.size(); i++) {
            assertFalse(all.get(i).occurredAt().isBefore(all.get(i - 1).occurredAt()), "必须升序");
        }
        assertEquals(all.get(all.size() - 1).id(), "t-later");
        assertEquals(2, store.findByNode("growth-rate").size());
        assertEquals(0, store.findByNode("mixed-growth").size());
    }

    // ——————————————————— 🔴 幂等:同一个 clientToken 只落一条 ———————————————————

    @Test
    @DisplayName("🔴 同一个 clientToken 追加两次 → 返回原来那条,库里只多一条")
    void sameClientTokenAppendsOnce() {
        FileTouchStore store = store();
        Touch first = store.append(new Touch("t-a", "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now(), null, "offline-001"));

        // 补传时客户端并不知道服务端给的 id,它重发的是【另一条】记录,只是去重键相同
        Touch again = store.append(new Touch("t-b", "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now().plusSeconds(3600), null, "offline-001"));

        assertEquals(first.id(), again.id(), "返回的必须是原来那条");
        assertEquals(9, store.count(), "多一条就等于覆盖度的分子被数了两次");
        // 🔴 不覆盖:补传那份带的是补传时刻的时间戳,拿它盖掉第一次的 occurredAt
        //    等于让一条记录凭空变年轻,而「多久前」是五态里唯一的时间依据
        assertEquals(first.occurredAt(), again.occurredAt());
    }

    @Test
    @DisplayName("🔴 clientToken 落盘 —— 进程重启之后那条补传仍然重复不了")
    void clientTokenSurvivesReopen() throws Exception {
        store().append(new Touch("t-a", "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now(), null, "offline-001"));

        assertTrue(Files.readString(file(), StandardCharsets.UTF_8).contains("offline-001"),
                "只留在内存里的话,进程一重启那批记录就能再补传一次");

        FileTouchStore reopened = store();
        assertNotNull(reopened.findByClientToken("offline-001"));
        reopened.append(new Touch("t-b", "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now(), null, "offline-001"));
        assertEquals(9, reopened.count());
    }

    @Test
    @DisplayName("🔴 没有 clientToken 的记录之间永不判重 —— 空的去重键不是一个能互相匹配的值")
    void recordsWithoutTokenNeverMatchEachOther() {
        FileTouchStore store = store();
        store.append(new Touch("t-a", "average-calc", "自己刷题", TouchKind.MANUAL, Instant.now(), null));
        store.append(new Touch("t-b", "average-calc", "自己刷题", TouchKind.MANUAL, Instant.now(), null));

        // 判重的失败方向只能是「多一条」:多一条用户看得见、删得掉;
        // 少一条是他记了却没记上,而他不会知道。种子 8 条里也都没有去重键。
        assertEquals(10, store.count());
        assertNull(store.findByClientToken(null));
        assertNull(store.findByClientToken("   "));
    }

    @Test
    @DisplayName("🔴 空白 clientToken 被归一成「没有」,不会让两条不相干的记录互相判重")
    void blankClientTokenIsNormalisedToAbsent() {
        FileTouchStore store = store();
        store.append(new Touch("t-a", "average-calc", "自己刷题", TouchKind.MANUAL, Instant.now(), null, "  "));
        store.append(new Touch("t-b", "yoy-mom", "自己刷题", TouchKind.MANUAL, Instant.now(), null, ""));

        assertEquals(10, store.count(), "两条都要在 —— 它们只是都没填去重键,不是同一条");
        assertFalse(Files.exists(file()) && store.findAll().stream()
                        .anyMatch(t -> "".equals(t.clientToken()) || "  ".equals(t.clientToken())),
                "空白串不该原样留在记录里");
    }

    @Test
    @DisplayName("去重键有长度上限 —— 它是个 id,不是放内容的地方(R-01)")
    void clientTokenIsLengthCapped() {
        assertThrows(IllegalArgumentException.class,
                () -> new Touch("t-a", "average-calc", "自己刷题", TouchKind.MANUAL,
                        Instant.now(), null, "题".repeat(Touch.MAX_CLIENT_TOKEN_LENGTH + 1)));
    }

    @Test
    @DisplayName("改挂记录时 clientToken 跟着搬 —— 丢了它那条记录就重新变得可以被补传一次")
    void reassignKeepsClientToken() {
        FileTouchStore store = store();
        store.append(new Touch("t-a", "average-calc", "地铁上",
                TouchKind.MANUAL, Instant.now(), null, "offline-001"));

        assertEquals(1, store.reassign("average-calc", "yoy-mom"));
        assertNotNull(store.findByClientToken("offline-001"));
        assertEquals("yoy-mom", store.findByClientToken("offline-001").nodeCode());
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
                    store.append(new Touch("t-race-" + id, "average-calc", "地铁上",
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

        assertEquals(9, store.count(), "8 个线程抢同一个去重键,只能落一条");
        assertEquals(9, store().count(), "磁盘上也只能有一条");
    }

    // ——————————————————— 删记录 ———————————————————

    @Test
    @DisplayName("delete 删掉那一条并落盘;换个实例读出来也少了那条")
    void deleteRemovesExactlyOneAndPersists() {
        FileTouchStore store = store();
        Touch gone = store.delete("seed-share-change");

        assertNotNull(gone);
        assertEquals("share-change", gone.nodeCode(), "返回被删的那条 —— 调用方要靠它知道哪个考点要重算");
        assertEquals(7, store.count());
        assertEquals(7, store().count(), "磁盘上也要少一条");
        assertEquals(0, store.findByNode("share-change").size());
    }

    @Test
    @DisplayName("删一条不存在的记录返回 null,不抛异常,也不写盘")
    void deletingAMissingRecordIsNotAFailure() throws Exception {
        FileTouchStore store = store();
        store.count();                                  // 先触发播种,让文件落地
        long before = Files.getLastModifiedTime(file()).toMillis();

        assertNull(store.delete("t-不存在"), "「删一条不存在的记录」是调用方要分辨的情况,不是服务端的故障");
        assertEquals(8, store.count());
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
                        store.append(new Touch("t-" + id + "-" + i, "growth-rate", "自己刷题",
                                TouchKind.MANUAL, Instant.now(), null));
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
        assertEquals(expected, store.count(), "内存里一条不少");
        assertEquals(expected, store().count(), "磁盘上也一条不少");

        Set<String> ids = new HashSet<>();
        store().findAll().forEach(t -> ids.add(t.id()));
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
        assertThrows(RuntimeException.class, () -> store().findAll());
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

        assertThrows(IllegalStateException.class, () -> store().findAll(),
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
                    { "id": "bad-1", "nodeCode": "growth-rate", "kind": "LIVESTREAM",
                      "occurredAt": "2026-08-20T10:00:00Z" }
                  ]
                }
                """, StandardCharsets.UTF_8);

        RuntimeException e = assertThrows(IllegalStateException.class, () -> store().findAll());
        assertFalse(e instanceof IllegalArgumentException,
                "IllegalArgumentException 会被接口层当成 400 并原样回显 —— "
                        + "前端会收到「No enum constant com.kaodian.server.collect.TouchKind.LIVESTREAM」");
        assertTrue(e.getMessage().contains("bad-1"), "报错要指出是哪一条,否则没法修文件");
    }

    @Test
    @DisplayName("🔒 播种走注入的 Clock —— 固定时钟回放时,种子和差集必须在同一条时间线上")
    void seedFollowsTheInjectedClock() {
        Instant fixed = Instant.parse("2026-07-01T00:00:00Z");
        FileTouchStore store = new FileTouchStore(file(), Clock.fixed(fixed, ZoneOffset.UTC));

        CoverageService service = new CoverageService();
        Summary s = service.summarize(
                service.compute(SyllabusLoader.loadDefault(), store.findAll(), fixed));

        // 用 Instant.now() 播种的话,这里会不报错地变成 稳5·弱2·生疏0 —— 契约被时间线错位吃掉了。
        assertEquals(3, s.distribution().get(NodeState.STABLE), "稳");
        assertEquals(2, s.distribution().get(NodeState.WEAK), "弱");
        assertEquals(2, s.distribution().get(NodeState.RUSTY), "生疏");
        assertEquals(1, s.distribution().get(NodeState.TOUCHED_ONLY), "仅接触");
        assertEquals(44, s.percent());
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
