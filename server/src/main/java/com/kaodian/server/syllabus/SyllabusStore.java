package com.kaodian.server.syllabus;

import java.util.List;

/**
 * 骨架层的存储与编辑契约 —— <b>骨架树从这一版起是可写的</b>。
 *
 * <h2>为什么现在需要它</h2>
 *
 * docs/04 §1.2 的阶段 1 是「骨架冷启动 + <b>人工校正命名</b>」。校正命名的意思是:
 * 一边标真题一边发现某个考点切得太粗、某个名字沿用了机构的说法、某两个其实是一回事 ——
 * 然后当场改。没有考点管理,这一步<b>根本做不了</b>,骨架只能停在种子文件的样子。
 *
 * <h2>🔴 三条不变量,写在这里而不是写在控制器里</h2>
 *
 * <ol>
 *   <li><b>code 由服务端生成</b>({@link #addNode} / {@link #addGroup} 都不收 code 参数)。
 *       客户端指定 code 等于把主键交给调用方,而中文名当 code 更是直接把「改名」变成「断历史」。</li>
 *   <li><b>重命名只改 name,绝不改 code</b>({@link #renameNode})。这是安全的,
 *       因为记录挂 code 不挂名字 —— 见 {@link Syllabus} 的「code 是主键,名字不是」。</li>
 *   <li><b>已有记录的考点不允许直接删除</b>({@link #deleteNode})。见下。</li>
 * </ol>
 *
 * 把这三条放在存储契约上、而不是放在接口层的校验里,是因为校验可以被绕过(再开一个端点、
 * 写一个脚本、跑一次测试夹具),而<b>不存在一个能绕过 store 的写入路径</b>。
 * 这与 {@code CaptureService} 是同一条纪律:红线逐条挂在写入路径上,写入路径只有一条。
 *
 * <h2>🔴 没有「批量导入考点体系」这类方法,以后也不会有</h2>
 *
 * 只有逐个新增。批量导入的下一步一定是从某个机构的目录页整块拷进来,
 * 而 R-07 / docs/04 §1.2 要求<b>标签自行命名、不沿用机构既有体系与措辞</b>。
 * 逐个新增很慢,慢正是要的效果 —— 它逼着人对每一个考点名做一次自己的判断。
 * <p>
 * 导出是有的({@code GET /api/syllabus/export}),那是「把自己的树拿走」;
 * 它的反向操作是<b>把导出的文件放回 {@code ~/.kaodian/syllabus.json}</b>,
 * 不是一个接受任意树形 JSON 的端点。
 *
 * <h2>换库那天</h2>
 *
 * 与 {@code TouchStore} 一样,现在的实现是一个 JSON 文件({@link FileSyllabusStore})。
 * 到阶段 1 的 {@code 1.2.4} 换成 JDBC 时,只增加一个实现类,差集那一侧一行不用改。
 */
public interface SyllabusStore extends SyllabusSource {

    /** 当前的骨架树。 */
    @Override
    Syllabus current();

    // ———————————————————————— 考点 ————————————————————————

    /**
     * 新增考点。
     *
     * <p>🔴 <b>没有 code 参数</b> —— code 由服务端生成,而且<b>不从名字派生</b>。
     * 派生等于把名字焊回 code,而 code 存在的全部理由就是「改名不断历史」;
     * 中文名派生还只有两条路:直接拿中文当 code(不可搬运),或者转拼音
     * (等于把某一种措辞编码进主键)。两条都不走。
     *
     * <p>🔴 <b>父级只能是题型</b>,不能是另一个考点 —— 三层就是三层(01 §2.5)。
     *
     * @param groupCode     挂到哪个题型下
     * @param name          自行归纳的考点名(R-07)
     * @param recent5yCount 近五年频次,非负整数
     * @throws SyllabusEditException 题型不存在 / 名字不合法 / 频次为负
     */
    Syllabus.Node addNode(String groupCode, String name, int recent5yCount);

    /**
     * 重命名考点 —— <b>只改 name,code 一个字符都不动</b>。
     *
     * <h2>🔴 为什么改名是安全的</h2>
     *
     * 因为行为层的每一条记录都挂在 <b>code</b> 上({@code Touch.nodeCode}),不挂名字。
     * 改名之后,那个考点上原有的记录、覆盖率、五态、盲区排序<b>一个数都不会变</b>。
     * <p>
     * 这正是当初用 code 而不是中文名做主键的原因:阶段 1 要反复「人工校正命名」
     * (docs/04 §1.2),如果记录挂在名字上,<b>每改一次名就断一次历史</b> ——
     * 用户会看见自己练过的东西一夜之间全变成空白,而这恰恰是这个产品最不能出的错。
     */
    Syllabus.Node renameNode(String nodeCode, String newName);

    /**
     * 把考点移到另一个题型下。<b>code 不变</b>,所以记录同样一条都不受影响。
     *
     * <p>会影响的只有两件事:它算进哪个题型的「整块空白」,以及它在盲区并列时的先后。
     */
    Syllabus.Node moveNode(String nodeCode, String targetGroupCode);

    /**
     * 改近五年频次。
     *
     * <p>这个数是<b>统计事实</b>(docs/07),也是盲区排序的权重之一
     * ({@code blindScore = 频次 × 状态权重})。改它会改「先补这几个」的名次,
     * 但不会改任何一条记录。
     */
    Syllabus.Node setRecent5yCount(String nodeCode, int recent5yCount);

    /**
     * 归档考点 —— 🔴 <b>「已有记录但想弃用」的正确出路。</b>
     *
     * <p>归档之后:它退出差集(分母和分子同时少一个,比值仍然诚实)、退出盲区列表、
     * 不能再挂新记录;但<b>它的 code 和历史记录一条都没动</b>,时间线上仍然认得出名字,
     * 随时可以 {@link #unarchiveNode} 接回来。
     * <p>
     * 与删除的区别就在这里:删除会让记录成为孤儿,归档不会。
     */
    Syllabus.Node archiveNode(String nodeCode);

    /** 取消归档,把考点接回差集。 */
    Syllabus.Node unarchiveNode(String nodeCode);

    /**
     * 删除考点。
     *
     * <h2>🔴 删除守则 —— 已有记录的考点不允许删除</h2>
     *
     * 记录是挂在 <b>code</b> 上的。删掉一个已有记录的考点,那些记录就成了孤儿:
     * 覆盖率的分母少一个、分子也少一个,<b>而覆盖率是这个产品唯一的那个数</b>。
     * 更糟的是这件事没有任何提示 —— 用户只会看见百分比莫名其妙地动了。
     * <p>
     * 所以这里<b>不接受任何「强制删除」参数</b>。上面挂着记录就抛
     * {@link SyllabusEditException.Reason#NODE_HAS_RECORDS},并且<b>说出有几条</b>。
     * 两条正确出路:
     * <ul>
     *   <li>{@link #moveRecords} —— 把记录搬到别的考点,搬完再删</li>
     *   <li>{@link #archiveNode} —— 留着 code、留着记录,只是让它退出差集</li>
     * </ul>
     * 这个判断在 store 里做而不是在控制器里做,是因为<b>控制器可以再写一个</b>,
     * store 只有这一个。
     */
    void deleteNode(String nodeCode);

    /**
     * 把挂在 {@code from} 上的记录整体改挂到 {@code to} —— 删除守则给出的第一条出路。
     *
     * <p>来源<b>可以是归档的考点</b> —— 「把记录搬走再真正删掉」正是归档清单那一屏的用途。
     * 目标则必须是树里<b>没有归档的</b>考点(归档的意思就是不再往上挂东西)。
     * 记录总数不变,这个方法搬家、不扔东西。
     *
     * <p>🔴 目标已归档时抛 {@link SyllabusEditException.Reason#NODE_ARCHIVED},
     * <b>不是</b> {@code NODE_NOT_FOUND}:那个考点明明在树里,归档清单刚列过它,
     * 而两者在界面上要说的下一步完全不同(取消归档 vs. 刷新重来)。
     *
     * @return 搬走了几条
     */
    int moveRecords(String fromNodeCode, String toNodeCode);

    /** 这个考点上挂着几条记录。界面在弹「确定删除吗」之前先问它。 */
    int recordCount(String nodeCode);

    // ———————————————————————— 题型 ————————————————————————

    /** 新增题型。同样 <b>code 由服务端生成</b>。 */
    Syllabus.Group addGroup(String name);

    /** 重命名题型 —— 同样只改 name,code 不动。 */
    Syllabus.Group renameGroup(String groupCode, String newName);

    /**
     * 删除题型。<b>下面还有考点(含已归档的)时不允许删。</b>
     *
     * <p>连带删除会一次性把一整组考点连同它们的记录一起变成孤儿 ——
     * 那是「删一个考点会丢数据」的放大版,没有理由在题型这一层反而更宽松。
     */
    void deleteGroup(String groupCode);

    /**
     * 调整题型顺序。
     *
     * <p>🔴 <b>树序是有产品含义的</b>:盲区排序在 {@code blindScore} 并列时按树序决定先后
     * ({@code CoverageService.blindSpots}),而「先补这几个」的前几名就是用户唯一会看的东西。
     * 所以顺序不是展示细节,它<b>要被显式支持、显式持久化</b>。
     *
     * @param groupCodes 现有题型 code 的一个<b>完整排列</b>。少一个就等于悄悄删一个 ——
     *                   所以对不上就整体拒绝,不做「剩下的按原序补在后面」这种补救
     */
    Syllabus reorderGroups(List<String> groupCodes);

    /**
     * 调整某个题型下考点的顺序。
     *
     * @param nodeCodes 该题型下<b>未归档</b>考点 code 的完整排列。
     *                  已归档的考点不参与排序(它们不进差集,先后没有意义),
     *                  重排后统一排在末尾、保持原有相对顺序
     */
    Syllabus reorderNodes(String groupCode, List<String> nodeCodes);
}
