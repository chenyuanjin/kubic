package com.kaodian.server.api.syllabus;

import com.kaodian.server.api.dto.syllabus.ArchivedNodesResponse;
import com.kaodian.server.api.dto.syllabus.CreateGroupRequest;
import com.kaodian.server.api.dto.syllabus.CreateNodeRequest;
import com.kaodian.server.api.dto.syllabus.DeletedResponse;
import com.kaodian.server.api.dto.syllabus.GroupEditResponse;
import com.kaodian.server.api.dto.syllabus.GroupOrderRequest;
import com.kaodian.server.api.dto.syllabus.MoveNodeRequest;
import com.kaodian.server.api.dto.syllabus.MoveRecordsRequest;
import com.kaodian.server.api.dto.syllabus.NodeEditResponse;
import com.kaodian.server.api.dto.syllabus.NodeOrderRequest;
import com.kaodian.server.api.dto.syllabus.RecordsMovedResponse;
import com.kaodian.server.api.dto.syllabus.RenameRequest;
import com.kaodian.server.api.dto.syllabus.SetFrequencyRequest;
import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.api.dto.syllabus.SyllabusExportResponse;
import com.kaodian.server.api.dto.syllabus.SyllabusGroupDto;
import com.kaodian.server.api.dto.common.SyllabusNodeDto;
import com.kaodian.server.api.dto.syllabus.TreeResponse;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.api.dto.syllabus.ArchivedNodesResponse;
import com.kaodian.server.api.dto.syllabus.CreateGroupRequest;
import com.kaodian.server.api.dto.syllabus.CreateNodeRequest;
import com.kaodian.server.api.dto.syllabus.DeletedResponse;
import com.kaodian.server.api.dto.syllabus.GroupEditResponse;
import com.kaodian.server.api.dto.syllabus.GroupOrderRequest;
import com.kaodian.server.api.dto.syllabus.MoveNodeRequest;
import com.kaodian.server.api.dto.syllabus.MoveRecordsRequest;
import com.kaodian.server.api.dto.syllabus.NodeEditResponse;
import com.kaodian.server.api.dto.syllabus.NodeOrderRequest;
import com.kaodian.server.api.dto.syllabus.RecordsMovedResponse;
import com.kaodian.server.api.dto.syllabus.RenameRequest;
import com.kaodian.server.api.dto.syllabus.SetFrequencyRequest;
import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.api.dto.syllabus.SyllabusExportResponse;
import com.kaodian.server.api.dto.syllabus.SyllabusGroupDto;
import com.kaodian.server.api.dto.common.SyllabusNodeDto;
import com.kaodian.server.api.dto.syllabus.TreeResponse;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 考点管理 —— <b>骨架层的写端点</b>。
 *
 * <h2>为什么现在要有它</h2>
 *
 * docs/decisions/实施路径.md §1.2 的阶段 1 是「骨架冷启动 + <b>人工校正命名</b>」。校正命名的意思是:
 * 一边标真题一边发现某个考点切得太粗、某个名字沿用了机构的说法、某两个其实是一回事 ——
 * 然后当场改。没有这一组端点,骨架只能永远停在种子文件的样子,阶段 1 走不下去。
 *
 * <h2>控制器里没有任何规则</h2>
 *
 * 生成 code、拒绝删除、判断排列是否完整,全部在 {@link SyllabusStore} 里。
 * 这里只做三件事:收参数、把领域对象翻成 DTO、把改完之后的覆盖概览一起带回去。
 * <b>规则放在 store 而不是控制器,是因为控制器可以再写一个,store 只有这一个</b> ——
 * 与 {@code RecordController} 不自己 {@code new Touch} 是同一条纪律。
 *
 * <h2>🔴 为什么删除是 POST,不是 DELETE</h2>
 *
 * 两个理由,都不是风格问题:
 * <ol>
 *   <li>{@code ApiCorsConfig} 的方法白名单只有 {@code GET / POST}。那份 javadoc 写着
 *       「将来真要开 DELETE 时,这里必须显式加,而<b>必须显式加</b>正是要的效果」——
 *       这一版没有理由去动那道锁。</li>
 *   <li>更要紧的是语义:这里的删除<b>不是「让一个资源消失」,而是一条带前置条件的命令</b>,
 *       它会失败,而且失败才是常态(有记录就不许删)。
 *       {@code DELETE} 那种「幂等地让它不在」的形状会诱导调用方把 4xx 当成噪音重试。</li>
 * </ol>
 *
 * <h2>🔴 这里<b>没有</b>批量导入考点体系的端点,以后也不会有</h2>
 *
 * 只有逐个新增。一个能一次提交整棵子树的端点,现实中的第一个用途一定是把某个机构的
 * 目录页整块拷进来 —— 而 R-07 / docs/decisions/实施路径.md §1.2 要求<b>考点标签自行归纳、
 * 不沿用机构既有体系与措辞</b>。逐个新增很慢,慢正是要的效果。
 * <p>
 * 导出是有的({@link #export}),那是 决策记录 §2.6 对用户的承诺:你的东西你随时能拿走。
 * 它的反向操作是<b>把导出的文件放回 {@code ~/.kaodian/syllabus.json}</b>,
 * 不是一个接受任意树形 JSON 的接口。详见 {@link SyllabusExportResponse}。
 */
@RestController
@RequestMapping("/api/syllabus")
public class SyllabusAdminController {

    private final SyllabusStore store;
    private final CoverageReader reader;

    public SyllabusAdminController(SyllabusStore store, CoverageReader reader) {
        this.store = store;
        this.reader = reader;
    }

    // ———————————————————————— 考点 ————————————————————————

    /**
     * 新增考点。
     *
     * <p>🔴 <b>返回体里的 code 是服务端生成的</b>,请求体里没有这个字段 ——
     * 客户端不能指定 code,更不能拿中文名当 code(见 {@link CreateNodeRequest})。
     */
    @PostMapping("/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeEditResponse createNode(@Valid @RequestBody CreateNodeRequest req) {
        Syllabus.Node created = store.addNode(req.groupCode(), req.name(), req.recent5yCount());
        return nodeResponse(created.code());
    }

    /**
     * 重命名考点 —— <b>只改名字,code 一个字符都不动。</b>
     *
     * <h2>🔴 为什么改名是安全的,而删除不是</h2>
     *
     * 行为层的每一条记录都挂在 <b>code</b> 上({@code Touch.nodeCode}),不挂名字。
     * 所以改完名之后,那个考点上的记录、覆盖率、五态、盲区排序<b>一个数都不会变</b> ——
     * 响应里的 {@code summary} 会和改之前逐字一致。
     * <p>
     * 这正是当初用 code 而不是中文名做主键的全部理由:阶段 1 要反复「人工校正命名」,
     * 如果记录挂在名字上,<b>每改一次名就断一次历史</b>,用户会看见自己练过的东西
     * 一夜之间全变成空白 —— 而那恰恰是这个产品最不能出的错。
     */
    @PostMapping("/nodes/{code}/rename")
    public NodeEditResponse renameNode(@PathVariable String code, @Valid @RequestBody RenameRequest req) {
        store.renameNode(code, req.name());
        return nodeResponse(code);              // 🔴 还是原来那个 code
    }

    /** 把考点移到另一个题型下。code 不变,记录一条都不受影响。 */
    @PostMapping("/nodes/{code}/move")
    public NodeEditResponse moveNode(@PathVariable String code, @Valid @RequestBody MoveNodeRequest req) {
        store.moveNode(code, req.groupCode());
        return nodeResponse(code);
    }

    /** 改近五年频次。这是统计事实,不是难度也不是权重 —— 见 {@link SetFrequencyRequest}。 */
    @PostMapping("/nodes/{code}/frequency")
    public NodeEditResponse setFrequency(@PathVariable String code, @Valid @RequestBody SetFrequencyRequest req) {
        store.setRecent5yCount(code, req.recent5yCount());
        return nodeResponse(code);
    }

    /**
     * 归档考点 —— 🔴 <b>「已有记录但想弃用」的正确出路。</b>
     *
     * <p>归档之后它退出差集(分母和分子同时少一个,比值仍然诚实)、退出盲区列表、
     * 不能再挂新记录;但 code 和历史记录一条都没动,时间线上仍然认得出名字。
     * 想找回来走 {@link #unarchiveNode},想看有哪些走 {@link #archivedNodes}。
     */
    @PostMapping("/nodes/{code}/archive")
    public NodeEditResponse archiveNode(@PathVariable String code) {
        store.archiveNode(code);
        return nodeResponse(code);
    }

    /** 取消归档,把考点接回差集。 */
    @PostMapping("/nodes/{code}/unarchive")
    public NodeEditResponse unarchiveNode(@PathVariable String code) {
        store.unarchiveNode(code);
        return nodeResponse(code);
    }

    /**
     * 删除考点。
     *
     * <h2>🔴 删除守则 —— 上面挂着记录就不许删,而且要说出有几条</h2>
     *
     * 记录挂在 code 上。删掉一个已有记录的考点,那些记录就成了孤儿:
     * 覆盖率的分母少一个、分子也少一个,<b>而覆盖率是这个产品唯一的那个数</b>,
     * 用户只会看见百分比莫名其妙地动了。
     * <p>
     * 所以这条路径在有记录时返回 <b>409 {@code NODE_HAS_RECORDS}</b>,消息里带着条数,
     * 并指出两条正确出路:{@link #moveRecords}(把记录搬到别的考点)
     * 或 {@link #archiveNode}(留着 code 和记录,只让它退出差集)。
     * <p>
     * <b>没有 force 参数,也不接受任何形式的「我确定」</b> ——
     * 判断在 {@link SyllabusStore#deleteNode} 里做,不在这里,因为控制器可以再写一个。
     */
    @PostMapping("/nodes/{code}/delete")
    public DeletedResponse deleteNode(@PathVariable String code) {
        store.deleteNode(code);
        return new DeletedResponse(code, summary());
    }

    /**
     * 把一个考点上的记录整体搬到另一个考点 —— 删除守则给出的第一条出路。
     *
     * <p>搬迁只改 {@code nodeCode}:时间戳、来源名、做题数原样保留,<b>记录总数不变</b>。
     * 不重置时间戳是有意的 —— 「多久前」是这个产品仅有的三个维度之一,
     * 让一批记录因为搬家而集体变年轻,五态会不报错地整体漂移。
     */
    @PostMapping("/nodes/{code}/records/move")
    public RecordsMovedResponse moveRecords(@PathVariable String code,
                                            @Valid @RequestBody MoveRecordsRequest req) {
        int moved = store.moveRecords(code, req.toNodeCode());
        return new RecordsMovedResponse(code, req.toNodeCode(), moved, summary());
    }

    // ———————————————————————— 题型 ————————————————————————

    /** 新增题型。新建的题型一定是空的 —— 见 {@link CreateGroupRequest} 里关于批量导入的那段。 */
    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupEditResponse createGroup(@Valid @RequestBody CreateGroupRequest req) {
        Syllabus.Group created = store.addGroup(req.name());
        return new GroupEditResponse(SyllabusGroupDto.from(created), summary());
    }

    /** 重命名题型。同样只改 name,code 不动。 */
    @PostMapping("/groups/{code}/rename")
    public GroupEditResponse renameGroup(@PathVariable String code, @Valid @RequestBody RenameRequest req) {
        store.renameGroup(code, req.name());
        return groupResponse(code);
    }

    /**
     * 删除题型。<b>下面还有考点(含已归档的)时返回 409 {@code GROUP_NOT_EMPTY}。</b>
     *
     * <p>连带删除会一次性把一整组考点连同它们的记录变成孤儿 ——
     * 那是「删一个考点会丢数据」的放大版,没有理由在题型这一层反而更宽松。
     */
    @PostMapping("/groups/{code}/delete")
    public DeletedResponse deleteGroup(@PathVariable String code) {
        store.deleteGroup(code);
        return new DeletedResponse(code, summary());
    }

    // ———————————————————————— 顺序 ————————————————————————

    /**
     * 调整题型顺序。返回整棵新树 —— 顺序改完,用户要看的就是新的排布。
     *
     * <p>🔴 <b>树序会改变产品给出的答案</b>:盲区排序在 {@code blindScore} 并列时按树序决定先后,
     * 而「先补这几个」的前几名就是用户唯一会看的东西。所以它不是排版偏好,要显式持久化。
     */
    @PostMapping("/groups/order")
    public TreeResponse reorderGroups(@Valid @RequestBody GroupOrderRequest req) {
        store.reorderGroups(req.groupCodes());
        return tree();
    }

    /** 调整某个题型下考点的顺序。已归档的不参与排序,重排后沉到末尾。 */
    @PostMapping("/groups/{code}/nodes/order")
    public TreeResponse reorderNodes(@PathVariable String code, @Valid @RequestBody NodeOrderRequest req) {
        store.reorderNodes(code, req.nodeCodes());
        return tree();
    }

    // ———————————————————————— 查看 ————————————————————————

    /**
     * 已归档的考点。它们不在 {@code /tree} 里 —— 归档的意思就是退出差集。
     *
     * <p>一个看不见又删不掉的东西是最糟的状态,所以必须有一处能看见它们:
     * 取消归档接回来,或者把记录搬走之后真正删掉。
     */
    @GetMapping("/archived")
    public ArchivedNodesResponse archivedNodes() {
        Syllabus s = store.current();
        List<SyllabusNodeDto> items = s.groups().stream()
                .flatMap(g -> g.archivedNodes().stream()
                        .map(n -> SyllabusNodeDto.of(n, g, store.recordCount(n.code()))))
                .toList();
        return new ArchivedNodesResponse(items.size(), items);
    }

    /**
     * 导出自己的骨架树。
     *
     * <p>🔴 <b>有导出,没有导入</b>。理由写在 {@link SyllabusExportResponse} 的类注释里:
     * 一个接受任意树形 JSON 的端点,现实中的第一个用途就是把机构的目录整块拷进来(违反 R-07)。
     * 恢复备份是「把这份文件放回 {@code ~/.kaodian/syllabus.json}」,不是一次 API 调用。
     */
    @GetMapping("/export")
    public SyllabusExportResponse export() {
        return SyllabusExportResponse.from(store.current());
    }

    // ———————————————————————— 内部 ————————————————————————

    /**
     * 编辑之后统一从 {@link CoverageReader} 重新读一次。
     *
     * <p>不在这里自己拼一个「大概是这样」的概览:覆盖率的口径只有一处
     * ({@code CoverageService}),两处算同一个数就一定会算出两个数。
     */
    private NodeEditResponse nodeResponse(String nodeCode) {
        Syllabus s = store.current();
        Syllabus.Node node = s.nodeIncludingArchived(nodeCode);
        return new NodeEditResponse(
                SyllabusNodeDto.of(node, s.groupOf(nodeCode), store.recordCount(nodeCode)),
                summary());
    }

    private GroupEditResponse groupResponse(String groupCode) {
        return new GroupEditResponse(SyllabusGroupDto.from(store.current().group(groupCode)), summary());
    }

    private SummaryDto summary() {
        CoverageReader.Snapshot snapshot = reader.read();
        return SummaryDto.from(reader.summarize(snapshot));
    }

    private TreeResponse tree() {
        CoverageReader.Snapshot snapshot = reader.read();
        return TreeResponse.of(snapshot.syllabus(), reader.summarize(snapshot), snapshot.groups());
    }
}
