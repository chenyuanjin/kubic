package com.kaodian.server.coverage;

import com.kaodian.server.collect.AssertionStore;
import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.RecordTagStore;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.collect.UserAssertion;
import com.kaodian.server.coverage.CoverageService.GroupCoverage;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.coverage.CoverageService.Summary;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusSource;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * <b>唯一的取数入口</b> —— HTTP 的四个查询端点走它,agent 的工具也走它。
 *
 * <p><b>2026-08-28 从 {@code api} 包下沉到 {@code coverage}。</b>它一直是纯领域取数
 * (零 web 依赖:没有 HttpServletRequest、没有 DTO),待在接口层只是历史位置。
 * 真正逼它搬家的是 agent:agent 的覆盖率工具需要<b>同一个口径</b>,
 * 而 kaodian-agent 不依赖 kaodian-app —— 留在原地的话,agent 只能自己再拼一次三层取数,
 * 那正好就是下面这段注释反对的事情。
 *
 * <h2>为什么所有查询必须走同一个类</h2>
 *
 * 覆盖率、五态、盲区排序,口径全部在 {@link CoverageService} 里。这里做的事只有两件:
 * 把「骨架 + 行为 + 现在几点」凑齐,和把结果原样递给控制器。
 * <b>两处算同一个数就一定会算出两个数</b> —— 树上那个 44% 和概览那个 44% 一旦分头计算,
 * 迟早会在某个边界条件上分道扬镳,而覆盖率就是这个产品唯一的那个数字。
 * <p>
 * 所以 {@link #summarize} 和 {@link #blindSpots} 是<b>纯转发</b>,一行逻辑都不加。
 * 它们存在只是为了让控制器只依赖一个东西,不是为了在中间插一层。
 */
@Component
public class CoverageReader {

    private final SyllabusSource syllabus;
    private final TouchStore store;
    private final RecordTagStore tagStore;
    private final AssertionStore assertionStore;
    private final CoverageService coverage;
    private final Clock clock;

    /**
     * @param syllabus 🔴 是 {@link SyllabusSource} 而不是一棵 {@link Syllabus}。
     *                 骨架层现在可写,持有一棵不可变的 record 等于持有进程启动那一刻的快照 ——
     *                 用户新增一个考点之后覆盖率的分母不动,<b>而且不会报错</b>。
     *                 这正是「新增考点后分母 +1」那条测试守着的东西
     * @param tagStore 标签层。<b>覆盖度的分子从这里出来</b>({@code discarded=0} 的那些,
     *                 docs/technical/INDEX.md §6.4)。它和行为层必须在<b>同一次读取</b>里取齐 —— 见 {@link #read}
     * @param assertionStore 「我已掌握」。🔴 <b>覆盖度的分子<u>不</u>从这里出来</b> ——
     *                 它只做两件事:让盲区榜少一行、让概览多一格(docs/technical/INDEX.md §6.4
     *                 「断言单列不并入」/「排除已断言节点」)。为什么不并入见 {@link UserAssertion}。
     *                 它必须和另外两层在<b>同一次读取</b>里取齐,理由同上
     */
    public CoverageReader(SyllabusSource syllabus, TouchStore store, RecordTagStore tagStore,
                          AssertionStore assertionStore, CoverageService coverage, Clock clock) {
        this.syllabus = syllabus;
        this.store = store;
        this.tagStore = tagStore;
        this.assertionStore = assertionStore;
        this.coverage = coverage;
        this.clock = clock;
    }

    /**
     * 一次请求内的差集快照。
     *
     * <p>把 {@code at} 一起带上,是因为五态里的「生疏」由时间推出:同一个请求里
     * 如果各处各自取一次 {@code now},就可能出现同一屏上两个节点用了不同的基准时刻。
     *
     * @param at      本次计算的基准时刻
     * @param touches 行为层原始记录,按发生时间升序(时间线端点直接用它)
     * @param groups  差集结果
     */
    public record Snapshot(Syllabus syllabus, Instant at, List<Touch> touches, List<GroupCoverage> groups) {

        /** 按 code 找考点的覆盖视图;不在骨架树里返回 {@code null}。 */
        public NodeCoverage node(String code) {
            return groups.stream()
                    .flatMap(g -> g.nodes().stream())
                    .filter(n -> n.code().equals(code))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * 读一次全量。18 个考点的规模下没有分页的必要,单模块整棵树一次返回(docs/technical/INDEX.md §6.4)。
     *
     * <p><b>树只在这里问一次</b>,然后随快照一路传下去。同一个请求里问两次,
     * 中间要是恰好有一次编辑落盘,顶上的百分比就会和树上的格子对不上 ——
     * 与「生疏」为什么共用一个 {@code at} 是同一个理由。
     */
    public Snapshot read(long userId) {
        return read(store.findAll(userId), tagStore.findAll(userId), assertionStore.findAll(userId));
    }

    /**
     * 🔴 <b>跨用户读一次全量。今天只剩一个调用方:{@code kaodian-agent} 的三个工具。</b>
     *
     * <p>{@code /api/agent/**} 的租户列归 KUBI-78,B0 §5.4 明写「本轮给目标形态,不动手」——
     * 而 {@code AgentController} 那个恒为 {@code 0L} 的 userId 是那条冲突的实读记录
     * (KUBI-76 {@code metadata.redline_hit}),本轮不撤销它。
     * <p>
     * 它今天在 HTTP 上走不通:{@code ApiAuthFilter} 覆盖 {@code /api/**},那五个端点没有令牌
     * 一律 {@code 401}。所以这条路存在,但打不开 —— <b>B0 §3.5 判据②:延后的是动手时间,
     * 不是那条判据,它现在是红的,红着是对的。</b>
     * <p>
     * ⚠️ 不要给它加新的调用方。要用户维度的差集,调 {@link #read(long)}。
     */
    public Snapshot read() {
        return read(store.findAllAcrossUsers(), tagStore.findAllAcrossUsers(),
                assertionStore.findAllAcrossUsers());
    }

    private Snapshot read(List<Touch> touches, List<RecordTag> storedTags,
                          List<UserAssertion> assertions) {
        Instant now = clock.instant();
        Syllabus tree = syllabus.current();

        // 行为层与标签层<b>一起读一次</b>,然后才算差集。分两次去问的话,中间要是恰好落了一次丢弃,
        // 就可能出现「记录已经有了、它的标签还没读到」——那个考点会不报错地少算一次触达。
        // 与「树只在这里问一次」是同一条纪律。
        //
        // 🔴 tagStore.findAll() 不能直接拿去算:库里只有【后来发生的事】(补标、加挂、确认、丢弃),
        //    采集那一刻的主标签是推出来的。派生规则只有 RecordTag.effectiveTagsOf 一处。
        //
        // 🔴 三层是【同一个用户维度】取来的(见两个 read 重载)。混着来 —— 比如记录按用户过滤、
        //    标签取全库 —— 会让一个人的覆盖度里混进别人的标签行,而且不会报错。
        //    所以三次取数都在调用方一处完成,这里只负责把它们对齐算差集。
        List<RecordTag> tags = RecordTag.effectiveTagsOf(touches, storedTags);

        return new Snapshot(tree, now, touches,
                coverage.compute(tree, touches, tags, assertions, now));
    }

    /** 纯转发给 {@link CoverageService#summarize}。 */
    public Summary summarize(Snapshot snapshot) {
        return coverage.summarize(snapshot.groups());
    }

    /** 纯转发给 {@link CoverageService#blindSpots}。 */
    public List<NodeCoverage> blindSpots(Snapshot snapshot, int top) {
        return coverage.blindSpots(snapshot.groups(), top);
    }

    /** 当前骨架树本身(不含行为层)。校验 nodeCode 用它,不需要先算一遍差集。 */
    public Syllabus syllabus() {
        return syllabus.current();
    }
}
