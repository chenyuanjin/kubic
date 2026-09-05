package com.kaodian.server.collect;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 行为层 JSON 的解析器,以及 classpath 上那份种子 —— <b>两个存储实现共用的同一份</b>。
 *
 * <h2>为什么它从 {@link FileTouchStore} 里抽出来</h2>
 *
 * 播种不是「文件实现的私事」。种子决定第一次跑起来看到的那 44%,而
 * {@code FileTouchStoreTest} 用「稳3·弱2·生疏2·仅接触1·空白10」这组数把它锁死了。
 * KUBI-112 之后同一份代码有两种存储({@code kaodian.data.store} = {@code file} / {@code jdbc}),
 * <b>两边必须从同一份种子出发</b> —— 否则换一个存储后端,覆盖度就换一个数,
 * 而「吐出去的还是不是同一个数」正是这次落库唯一要守住的东西。
 * <p>
 * 复制一份到 JDBC 那侧是最省事的写法,也是最坏的:两份种子解析迟早对不上,
 * 而它们对不上的表现是<b>覆盖率不一样</b>,不是编译错误 ——
 * 与 {@code CoverageReader} 开头那句「两处算同一个数就一定会算出两个数」是同一条。
 *
 * <h2>🔴 读是逐字段列举的,不用自动反序列化</h2>
 *
 * {@link #toTouch} 只认那几个键,JSON 里出现别的键一律被忽略、进不了内存。
 * 于是即便有人手工往 {@code touches.json} 或种子文件里塞了一段题干,它也<b>到不了任何地方</b>:
 * 既不会被读进来,更不会因为 {@link Touch} 将来多了个字段就悄悄流回文件。
 * 与 {@code FileTouchStore#toNode}(写的那一侧)是同一条纪律 ——
 * 不给内容留位置(决策记录 §2.2 / docs/technical/INDEX.md §5.1)。
 */
final class TouchSeed {

    /** 行为层种子。第一次跑起来就能看见 44%,而不是一个空白页。 */
    private static final String SEED_RESOURCE = "/seed/touches-demo.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TouchSeed() {
    }

    /**
     * 从 classpath 播种。
     *
     * @param clock 播种基准时刻的来源。种子里的 {@code daysAgo} 以 {@code clock.instant()} 为第 0 天 ——
     *              <b>种子不写死日期,否则放几天后「生疏」的判定就漂了</b>
     *              ({@link com.kaodian.server.coverage.NodeState#RUSTY_AFTER} 是 30 天)。
     *              落盘 / 落库之后存的是绝对时间戳,此后记录会随真实时间自然变旧;
     *              这不是缺陷,「多久前」本来就是产品的三个维度之一。
     *              <p>
     *              🔴 必须是<b>注入的那个 {@link Clock}</b>,不能图省事写 {@code Instant.now()}:
     *              一旦有人把它换成固定时刻回放场景({@code DomainBeans} 里那个 bean 就是为此存在的),
     *              种子会落在真实的今天、而差集按固定时刻算 —— 两条时间线一错开,
     *              「稳3·弱2·生疏2」这个契约会<b>不报错地</b>变成另一组数。
     */
    static List<Touch> load(Clock clock) {
        Instant seedAt = clock.instant();
        try (InputStream in = TouchSeed.class.getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("找不到行为层种子文件:" + SEED_RESOURCE);
            }
            return parse(MAPPER.readTree(in), seedAt);
        } catch (IOException e) {
            throw new IllegalStateException("行为层种子文件读取失败:" + SEED_RESOURCE, e);
        }
    }

    /**
     * 解析一份行为层 JSON。
     *
     * <h2>认不出来就吵着失败,绝不当成 0 条</h2>
     *
     * {@code path("touches")} 在缺键、根节点是数组、键名写错时都只是安静地给回一个 MissingNode,
     * 于是「解析成功、0 条记录」——而下一次 {@code FileTouchStore#append} 是<b>全量重写</b>,
     * 那 0 条会原样盖掉磁盘上真实存在的记录。{@code 坏文件 → 空数据 → 覆盖} 这条链走完,
     * 用户丢的是这个产品的全部资产,而且全程没有一行报错。
     * <p>
     * 所以这里要求 {@code touches} <b>必须是一个数组</b>:宁可启动不了,也不要静默清空。
     *
     * @param seedAt 非 null 时允许种子里的相对天数;读落盘文件时传 {@code null}
     */
    static List<Touch> parse(JsonNode root, Instant seedAt) {
        JsonNode array = root.path("touches");
        if (!array.isArray()) {
            throw new IllegalStateException(
                    "行为层数据里没有 touches 数组 —— 宁可在这里失败,也不能当成 0 条记录,"
                            + "否则下一次追加会把磁盘上真实存在的记录整个盖掉");
        }

        List<Touch> result = new ArrayList<>();
        for (JsonNode n : array) {
            try {
                result.add(toTouch(n, seedAt));
            } catch (IllegalArgumentException | DateTimeException e) {
                // 领域构造器(Touch / Drill / TouchKind.valueOf)的校验消息是写给【接口调用方】看的,
                // 接口层据此回 400「你的请求不合法」并原样回显。可这里的输入不是请求,是磁盘上的
                // 一份坏文件 —— 那是服务端的事,得 5xx 吵出来,而不是让前端收到一句
                // 「No enum constant com.kaodian.server.collect.TouchKind.X」。
                throw new IllegalStateException(
                        "行为层数据里有一条记录不合法:" + n.path("id").asString("?"), e);
            }
        }
        result.sort(Comparator.comparing(Touch::occurredAt));   // 契约:按发生时间升序
        return result;
    }

    /**
     * 一个 JSON 对象 → 一条记录。<b>只认这几个键,别的一概不看。</b>
     *
     * @param seedAt 非 null 时允许用相对天数 {@code daysAgo};落地文件里只会有绝对 {@code occurredAt}
     */
    static Touch toTouch(JsonNode n, Instant seedAt) {
        Instant at;
        if (n.has("occurredAt")) {
            at = Instant.parse(required(n, "occurredAt"));
        } else if (seedAt != null && n.has("daysAgo")) {
            at = seedAt.minus(Duration.ofDays(n.path("daysAgo").asInt(0)));
        } else {
            throw new IllegalStateException("记录缺少时间:" + n.path("id").asString("?")
                    + " —— 「多久前」全靠它");
        }

        // 没有 practiced 就是没做题(仅接触)。不是 0 道,是这条记录里根本没有做题这回事。
        Touch.Drill drill = n.has("practiced")
                ? new Touch.Drill(n.path("practiced").asInt(0), n.path("correct").asInt(0))
                : null;

        // 没有 clientToken 就是没有 —— 空串会被 Touch 的构造器归一成 null,
        // 好过让一堆「都没填」的老记录在 append 里互相判重。
        String clientToken = n.path("clientToken").asString("");

        return new Touch(
                required(n, "id"),
                required(n, "nodeCode"),
                n.path("sourceName").asString(""),
                TouchKind.valueOf(required(n, "kind")),
                at,
                drill,
                clientToken);
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asString("");
        if (v.isEmpty()) {
            throw new IllegalStateException("行为层记录缺少必填字段:" + field);
        }
        return v;
    }
}
