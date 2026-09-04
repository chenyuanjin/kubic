package com.kaodian.server.api.record;

import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.api.dto.record.AssertionRequest;
import com.kaodian.server.api.dto.record.AssertionResponse;
import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.api.dto.record.AssertionRequest;
import com.kaodian.server.api.dto.record.AssertionResponse;
import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.collect.AssertionStore;
import com.kaodian.server.collect.UserAssertion;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.syllabus.Syllabus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * 「我已掌握」/ 取消 —— docs/technical/INDEX.md §6.4 那张表的最后一行。
 *
 * <h2>🔴 这两个端点<b>动不了覆盖率</b>,这是它们最重要的性质</h2>
 *
 * 决策记录 §5.2 用一句话给这个按钮定了性:<b>「『我已掌握』按钮是补丁不是解法。」</b>
 * 那一节讲的是录入完整度 —— 用户在抖音看了半小时没记,产品说「你没碰过」,
 * 于是他认为工具不准。这个按钮给他一个当场消音的办法,<b>治的是那个感受,不是那个病</b>。
 * <p>
 * 所以它一旦计进覆盖度,补丁就被当成了疗效:那个百分比会因为<b>点按钮</b>而上升。
 * 一个能靠自我声明刷高的覆盖率,和一个没有覆盖率的产品,价值是一样的
 * (决策记录 §2.2 的能力边界只有「有没有、几次、多久前」,而声明不是其中任何一样)。
 * <p>
 * 落到这两个端点上,写完之后<b>只有两个数会变</b>:
 * <table border="1">
 *   <caption>按下按钮之后什么变了、什么没变</caption>
 *   <tr><th></th><th>变不变</th><th>依据</th></tr>
 *   <tr><td>{@code summary.percent}</td><td><b>不变</b></td>
 *       <td>§6.4「分子 = {@code discarded=0} 的触达节点数」—— 声明不是触达</td></tr>
 *   <tr><td>{@code summary.covered} / {@code empty} / {@code distribution}</td><td><b>不变</b></td>
 *       <td>同上。五态是从记录推出来的</td></tr>
 *   <tr><td>{@code summary.asserted}</td><td>+1 / −1</td><td>§6.4「<b>断言单列不并入</b>」</td></tr>
 *   <tr><td>{@code /coverage/blindspots}</td><td>少一行 / 多一行</td>
 *       <td>§6.4「<b>排除已断言节点</b>」</td></tr>
 * </table>
 *
 * <h2>⚠️ 断言不是归档,{@code POST /syllabus/nodes/&#123;code&#125;/archive} 是另一件事</h2>
 *
 * 归档把考点从<b>分母</b>里拿掉(比值仍然诚实,上下同时少一),断言把考点<b>留在分母里</b>、
 * 不进分子、单列一格。前者说「这个考点与我无关」,后者说「我会了,只是没记」。
 * 归档那一侧有一条单独的风险记录({@code docs/execution/INDEX.md} §四 {@code R-49}:归档可无声刷高覆盖率);
 * 断言这一侧没有那条风险,<b>因为它根本不动那个比值</b>。
 * 两者不要混成一个概念 —— 混了之后,「取消归档」和「取消声明」会被写成同一个按钮,
 * 而它们恢复的是两个不同的东西。
 *
 * <h2>两个动作都是幂等的,而且都不返回 404</h2>
 *
 * 重复声明同一个考点:不新建行、不刷新时刻、不报错,返回 200(不是 201)。
 * 取消一个没声明过的考点:同样 200 —— 用户要的结果已经成立了,
 * 这时候回 404 是在报告一个<b>不存在的失败</b>。理由写在 {@link AssertionStore} 上。
 * <p>
 * 唯一的拒绝是 {@code nodeCode} 不在(未归档的)骨架树里 → 400 {@code NODE_NOT_IN_SYLLABUS}。
 * 那与 {@code R-07} 是同一条:<b>只能从树里选,不能新建</b>。
 */
@RestController
@RequestMapping("/api/assertions")
public class AssertionController {

    private final AssertionStore store;
    private final CoverageReader reader;
    private final Clock clock;

    public AssertionController(AssertionStore store, CoverageReader reader, Clock clock) {
        this.store = store;
        this.reader = reader;
        this.clock = clock;
    }

    /**
     * 我已掌握。<b>body 只接受 {@code nodeCode}</b>(docs/technical/INDEX.md §6.4)。
     *
     * <h2>201 与 200 的区别是「新声明了没有」,不是「成功了没有」</h2>
     *
     * 与 {@code POST /records/&#123;id&#125;/tags} 完全同一条语义:同一个考点声明第二次,
     * 服务端什么都没新建,这时候还回 201 Created 是在说谎。
     * 而「说谎」在这里有具体后果 —— 界面按 201 弹一次「已记下」的动效,
     * 用户会以为自己刚才那一下没生效,于是再点一次。
     */
    @PostMapping
    public ResponseEntity<AssertionResponse> assertMastery(CurrentSession session,
                                                           @Valid @RequestBody AssertionRequest req) {
        session.requireWrite();
        String nodeCode = requireNodeInSyllabus(req.nodeCode());

        // 🔴 「已经声明过」问的是【这个人】声明过没有 —— 主键是 (userId, nodeCode)。
        //    按 nodeCode 单列判的那一版,第二个人的第一次声明会被当成重复,回 200 而且不落行。
        UserAssertion existing = store.find(session.userId(), nodeCode);
        store.put(new UserAssertion(session.userId(), nodeCode, clock.instant()));

        return ResponseEntity
                .status(existing == null ? HttpStatus.CREATED : HttpStatus.OK)
                .body(responseFor(session.userId(), nodeCode));
    }

    /**
     * 取消。<b>body 只接受 {@code nodeCode}</b>,与 POST 同一副形状。
     *
     * <h2>为什么 {@code DELETE} 也带请求体,而不是 {@code /assertions/&#123;nodeCode&#125;}</h2>
     *
     * 因为契约就是这么写的(§6.4 那一行的两个方法共用一句「body 只接受 {@code nodeId}」),
     * 而且这样两个方向的入口<b>形状完全一样</b> —— 前端一个函数改个方法名就是取消,
     * 不会出现「声明走 body、取消走路径」这种得记住的差别。
     * <p>
     * 顺带的好处是路径变量少一个:路径变量没有任何长度上限
     * ({@code ApiException} 类注释),而请求体上的 {@code nodeCode} 有 {@code @Size(max = 64)}。
     *
     * <p>永远 200:取消一个没声明过的考点不是错误,见类注释。
     */
    @DeleteMapping
    public AssertionResponse cancel(CurrentSession session, @Valid @RequestBody AssertionRequest req) {
        session.requireWrite();
        String nodeCode = requireNodeInSyllabus(req.nodeCode());
        store.remove(session.userId(), nodeCode);
        return responseFor(session.userId(), nodeCode);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 🔴 只能声明树里已有的考点(R-07)。
     *
     * <p>用 {@code node(code)} 而不是 {@code nodeIncludingArchived(code)}:已归档的考点
     * <b>整个退出了差集</b>(它既不在分母里,也不会出现在盲区榜上),给它贴一句「我已掌握」
     * 不会产生任何可见的效果 —— 那是一次静默无效的写入,比一次明确的拒绝更难查。
     * 与 {@code TaggingService.mount} 用的是同一棵树。
     *
     * @return 原样的 code(已确认在树里),后续一律用它,不再碰用户送来的那个串
     */
    private String requireNodeInSyllabus(String nodeCode) {
        Syllabus tree = reader.syllabus();
        Syllabus.Node node = tree.node(nodeCode);
        if (node == null) {
            // 这个工厂方法已经把用户输入过了一遍截断,这里不再拼一次(与 TagController 同一条)。
            throw ApiException.nodeNotInSyllabus(nodeCode);
        }
        return node.code();
    }

    /**
     * 写完之后再读一次差集,把这个考点和整体概览一起带回去。
     *
     * <p><b>读的是 {@link CoverageReader},不是把 {@code store.count()} 直接拿来用。</b>
     * 后者会得到一个「声明表里有几行」的数,而概览要的是「树上有几个考点被声明了」——
     * 一个指向已删除 / 已归档考点的声明行不该出现在那一格里(见
     * {@code CoverageService#compute} 的 {@code assertions} 参数说明)。
     * <b>两处算同一个数就一定会算出两个数。</b>
     */
    private AssertionResponse responseFor(long userId, String nodeCode) {
        CoverageReader.Snapshot snapshot = reader.read(userId);
        NodeCoverage node = snapshot.node(nodeCode);
        SummaryDto summary = SummaryDto.from(reader.summarize(snapshot));

        return new AssertionResponse(
                node != null && node.asserted(),
                node == null ? null : node.assertedAt(),
                summary.asserted(),
                node == null ? null : NodeDetailDto.from(node),
                summary);
    }
}
