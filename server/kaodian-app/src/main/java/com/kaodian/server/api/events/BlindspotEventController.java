package com.kaodian.server.api.events;

import com.kaodian.server.api.dto.common.ErrorCode;
import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.auth.AccountStore;
import com.kaodian.server.auth.AppUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

/**
 * {@code POST /api/v1/events/blindspot-opened} —— 「主动查看盲区」这一个事件
 * ({@code M3-骨架与覆盖度差集} §六)。
 *
 * <h2>🌟 这个端点是北极星的<b>唯一数据源</b></h2>
 *
 * 「主动查看盲区的人数」这个数,全部来自这里落下的行。所以它比别的端点多守两条:
 * <ul>
 *   <li>🔴 <b>响应体是空对象,不回显是不是第一次。</b> 端知道了就会有人拿它做
 *       「今天你已经看过了」的界面,而这两屏<b>没有第四种反馈形态</b>
 *       (红线七:没有小红点、未读计数、推送)。所以 {@link BlindspotEventStore#record}
 *       的那个布尔值到这里为止,一个比特都不出去</li>
 *   <li>🔴 <b>超窗的 {@code localDate} 丢弃,既不接受也不归一化到服务端当天。</b>
 *       理由见 {@link #requireLocalDateInWindow}</li>
 * </ul>
 *
 * <h2>去重不在这里,在 {@link BlindspotEventStore}</h2>
 *
 * 控制器不做「先查一下再决定」——那种写法在两台设备同时上报时会落两行。
 * 幂等由 {@code (userId, localDate, surface)} 这把唯一键天然给出,所以
 * 🔴 <b>这个端点不要 {@code Idempotency-Key}</b>(§6.5):它不触发外部账单、不改账号状态,
 * 多要一个头只会让端多一条出错的路。
 *
 * <h2>四种非法输入走同一个出口:{@code 400 INVALID_ARGUMENT}</h2>
 *
 * 🔴 <b>不新起码</b>(§6.3 末行逐字)。三个闭集加一个日期窗口,界面上都是
 * <b>用户选不出非法值</b>的位置 —— 走到这里就是端上的 bug,而「bug」不是一档界面状态
 * ({@link ApiException#invalidArgument} 类注释同一条)。给它们各起一个码,
 * 端会开始按码做分支,而那些分支永远不会被真实用户触发。
 * <p>
 * 端拿到 4xx 的动作是固定的:<b>从本地队列里删掉,不重试</b>(§6.3「端的队列纪律」)——
 * 它是脏数据,重试到天荒地老也不会变好。{@code 5xx} / 网络失败才保留重试。
 */
@RestController
@RequestMapping("/api/v1/events")
public class BlindspotEventController {

    private final BlindspotEventStore store;
    private final Clock clock;

    /**
     * 账号建号时刻的来源 —— {@code localDate} 下界的唯一依据。
     *
     * <p>用 {@link ObjectProvider} 而不是直接注入,与 {@code ApiAuthFilter} 同一条:
     * 只装了 web 切片的上下文里没有这个 bean,而<b>拿不到它不该让这个端点整个打不通</b>。
     * 拿不到时的行为见 {@link #requireLocalDateInWindow}。
     */
    private final ObjectProvider<AccountStore> accounts;

    public BlindspotEventController(BlindspotEventStore store, Clock clock,
                                    ObjectProvider<AccountStore> accounts) {
        this.store = store;
        this.clock = clock;
        this.accounts = accounts;
    }

    /**
     * 记一次「主动查看盲区」。
     *
     * <p>什么时候打、什么时候不打是<b>端的事</b>(§6.2):首屏内容真正上屏之后才打
     * (路由进入只说明他点了,不说明他看到了)、缓存上屏那一刻就打、空态也打;
     * 切科目 / 切排序 / 下拉刷新 / 从详情返回 / 冷启动恢复<b>都不打</b>。
     * 服务端在这里<b>看不出</b>端有没有守住那张表 —— 能守住的只有去重与窗口这两件事,
     * 所以这两件事必须在服务端。
     *
     * @return 🔴 <b>空对象。</b> 不是 {@code {"counted":true}},不是 {@code {"first":false}} ——
     *         见类注释。重复上报同样 {@code 200} 同样空对象,端<b>分辨不出</b>这一次算没算,
     *         这正是想要的
     */
    @PostMapping("/blindspot-opened")
    public Map<String, Object> blindspotOpened(CurrentSession session,
                                               @RequestBody BlindspotOpenedRequest req) {
        // 第三道锁。前两道在 TokenScope 与 ApiAuthFilter —— 一道失效不该导致整条线失守。
        session.requireWrite();

        String surface = requireOneOf("surface", req.surface(), BlindspotEventStore.SURFACES);
        String entry = requireOneOf("entry", req.entry(), BlindspotEventStore.ENTRIES);
        String outcome = requireOneOf("outcome", req.outcome(), BlindspotEventStore.OUTCOMES);
        LocalDate localDate = requireLocalDateInWindow(session.userId(), req.localDate());

        store.record(session.userId(), localDate, surface, entry, outcome, clock.instant());

        // 🔴 record() 的返回值到此为止。「是不是第一次」是服务端的事,不是端的事。
        return Map.of();
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 闭集校验。<b>不在 DTO 上写 {@code @Pattern}</b> —— 那条路出去的是
     * {@code VALIDATION_FAILED},而契约给<b>越界</b>这一档定的是 {@code INVALID_ARGUMENT}。
     *
     * <h2>🔴 「没传」与「传了但不在闭集里」是两个码</h2>
     *
     * 契约 §十 把两者分得很清:{@code VALIDATION_FAILED} 是「缺必填 / 类型不对」,
     * {@code INVALID_ARGUMENT} 是「参数值不合法」。两者在界面上都是「端上 bug」那一档,
     * 于是很容易被合成一个 —— 但<b>它们指向的排查方向不同</b>:
     * 前者说「端根本没发这个字段」(多半是 DTO 写错了或版本对不上),
     * 后者说「端发了,但发了个我们不认识的值」(多半是闭集加了新值而服务端没跟上)。
     * 合成一档之后,这两种 bug 在日志里长得一模一样。
     */
    private static String requireOneOf(String what, String value, Set<String> closed) {
        if (value == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺必填字段:" + what);
        }
        // 空串是【发了一个我们不认识的值】,不是没发 —— 走 INVALID_ARGUMENT。
        if (!closed.contains(value)) {
            throw ApiException.invalidArgument(what, value);
        }
        return value;
    }

    /**
     * {@code localDate} 的合法窗口:
     * <b>{@code 账号创建日 − 1 天 ≤ localDate ≤ 服务端 UTC 今天 + 1 天}</b>(§6.3 末 / §十四 增量 3)。
     * {@code ±1} 天是<b>跨时区的合法余量</b> —— 端报的是设备本地自然日,
     * 而服务端只有 UTC,两者最多差一天。
     *
     * <h2>🔴 超窗为什么不接受、也不归一化到服务端当天</h2>
     *
     * 一台时钟坏掉的设备会产出 {@code 2035-01-01} 的行,那一行会在<b>未来某一天</b>的人数里
     * 凭空多一个人;而归一化会让<b>今天</b>的人数多一个「从没打开过这一屏的人」。
     * 两种都是编数据 —— <b>丢弃是少一条数据,编是多一条假的</b>({@code U3.8} §2.7 同一句)。
     *
     * <h2>查不到账号时<b>放宽下界</b>,而不是拒绝</h2>
     *
     * 拿不到 {@link AccountStore}(只装了 web 切片的上下文)、或者账号查不到时,
     * 只判上界。理由是这条边的失败方向必须朝「<b>不丢数据</b>」倒:
     * 这一步已经在 {@code ApiAuthFilter} 之后,<b>令牌是真的,人是真的</b>,
     * 查不到账号是服务端自己的状态问题,不是端送来的脏数据。
     * 这时候回 400,端会按队列纪律把一条<b>本来正确</b>的事件从队列里删掉,而它补不回来。
     * <p>
     * 上界不放宽:它不依赖任何外部状态,只依赖 {@link Clock}。
     */
    private LocalDate requireLocalDateInWindow(long userId, String raw) {
        // 🔴 契约 §十 把这三档分得很清,而它们都长得像「端上 bug」:
        //    缺必填 / 类型不对 → VALIDATION_FAILED;参数【值】不合法(超窗)→ INVALID_ARGUMENT。
        //    合成一档之后,「端根本没发这个字段」与「端发了一个 2035 年」在日志里长得一模一样,
        //    而这两种 bug 的排查方向完全不同 —— 前者查端的 DTO,后者查那台设备的时钟。
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺必填字段:localDate");
        }
        LocalDate localDate;
        try {
            localDate = LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            // 「2026-9-3」「昨天」—— 这是【类型不对】,不是值越界。
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "localDate 不是一个 YYYY-MM-DD 日期。");
        }

        // 🔴 显式 UTC,不是 clock.getZone() —— 契约写的就是「服务端 UTC 今天」。
        //    跟着时区走的那一版,换一台机器部署就会让窗口整体挪一天。
        LocalDate serverToday = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (localDate.isAfter(serverToday.plusDays(1))) {
            throw ApiException.invalidArgument("localDate", raw);
        }

        Instant createdAt = accountCreatedAt(userId);
        if (createdAt != null
                && localDate.isBefore(LocalDate.ofInstant(createdAt, ZoneOffset.UTC).minusDays(1))) {
            throw ApiException.invalidArgument("localDate", raw);
        }
        return localDate;
    }

    /** 建号时刻;拿不到账号时 {@code null} —— 调用方据此放宽下界,见上。 */
    private Instant accountCreatedAt(long userId) {
        AccountStore store = accounts.getIfAvailable();
        return store == null
                ? null
                : store.findById(userId).map(AppUser::createdAt).orElse(null);
    }
}
