package com.kaodian.server.api;

import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.coverage.CoverageService;
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
 * 接口层<b>唯一的取数入口</b>。
 *
 * <h2>为什么四个查询端点必须走同一个类</h2>
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
    private final CoverageService coverage;
    private final Clock clock;

    /**
     * @param syllabus 🔴 是 {@link SyllabusSource} 而不是一棵 {@link Syllabus}。
     *                 骨架层现在可写,持有一棵不可变的 record 等于持有进程启动那一刻的快照 ——
     *                 用户新增一个考点之后覆盖率的分母不动,<b>而且不会报错</b>。
     *                 这正是「新增考点后分母 +1」那条测试守着的东西
     */
    public CoverageReader(SyllabusSource syllabus, TouchStore store, CoverageService coverage, Clock clock) {
        this.syllabus = syllabus;
        this.store = store;
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
     * 读一次全量。18 个考点的规模下没有分页的必要,单模块整棵树一次返回(docs/10 §6.4)。
     *
     * <p><b>树只在这里问一次</b>,然后随快照一路传下去。同一个请求里问两次,
     * 中间要是恰好有一次编辑落盘,顶上的百分比就会和树上的格子对不上 ——
     * 与「生疏」为什么共用一个 {@code at} 是同一个理由。
     */
    public Snapshot read() {
        Instant now = clock.instant();
        Syllabus tree = syllabus.current();
        List<Touch> touches = store.findAll();
        return new Snapshot(tree, now, touches, coverage.compute(tree, touches, now));
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
