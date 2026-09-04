package com.kaodian.server.syllabus;

/**
 * 一次考点管理操作被拒绝。
 *
 * <h2>为什么不用 {@link IllegalArgumentException}</h2>
 *
 * 接口层把 {@code IllegalArgumentException} 统一兜成 400 并<b>原样回显 message</b>
 * (见 {@code ApiExceptionHandler})。可这里的拒绝有三种完全不同的语义:
 * 「树里没有这个 code」是 404,「上面还挂着 3 条记录」是 409,「名字太长」才是 400。
 * 合成一个 400 之后,前端就没法分辨「换个 code 重试」和「先把记录搬走」——
 * 而这两句话在界面上要说的下一步完全不同。
 * <p>
 * 所以每一次拒绝都带一个 {@link Reason},由接口层逐个映射成状态码与错误码。
 *
 * <h2>🔴 回声在这里就被截断</h2>
 *
 * 这些消息里的变量是<b>用户送来的字符串</b>(路径变量里的考点 code)。路径变量没有任何
 * 长度上限,能塞满一整个请求行 —— 于是「删一个不存在的考点」这条最不起眼的路径,
 * 就成了把一整段题干写进日志文件的通道。这与 {@code ApiException} 开头那条纪律是同一条:
 * <b>回声要留(不然报错没法定位),但长度由我们说了算。</b>
 */
public class SyllabusEditException extends RuntimeException {

    /** 与 {@code ApiException.MAX_ECHOED_VALUE_LENGTH} 取同一个数:合法的 code 永远短于它。 */
    private static final int MAX_ECHOED_VALUE_LENGTH = 64;

    /**
     * 拒绝的原因。<b>每一条在界面上该说的下一步都不一样,所以不能合并。</b>
     */
    public enum Reason {

        /** 树里没有这个考点(归档的也算在树里)。 */
        NODE_NOT_FOUND,

        /** 树里没有这个题型。 */
        GROUP_NOT_FOUND,

        /**
         * 🔴 <b>删除守则</b>:这个考点上还挂着记录,不允许直接删除。
         * 正确出路是「先把记录移到别的考点」或「归档」。带记录条数,界面要说得出具体数字。
         */
        NODE_HAS_RECORDS,

        /** 题型下面还有考点(含已归档的),不允许删除题型 —— 删了那些考点就成了孤儿。 */
        GROUP_NOT_EMPTY,

        /** 已经归档过了。 */
        NODE_ALREADY_ARCHIVED,

        /**
         * 目标考点在树里,但<b>已经归档</b>了 —— 不能把记录往上搬。
         *
         * <p>与 {@link #NODE_NOT_FOUND} 分开,是因为下一步差得很远:
         * 「树里没这个 code」要用户刷新或换一个,而这个要用户<b>先把目标取消归档</b>。
         * 合成 404 更糟的是它当场自相矛盾 —— {@code GET /api/v1/syllabus/archived}
         * 刚刚把这个考点连名字带记录条数列出来过,下一句却说「骨架树里没有这个考点」。
         */
        NODE_ARCHIVED,

        /** 没归档,谈不上取消归档。 */
        NODE_NOT_ARCHIVED,

        /**
         * 名字不合法:空、太长、夹了换行(那通常意味着有人往名字里贴了一段内容),
         * 夹了<b>看不见的字符</b>(零宽空格、谚文填充符、盲文空点这一类 ——
         * 它们在名字里没有任何正当用途,唯一效果是造一个肉眼不可见的区别),
         * 或者<b>整个名字都看不见</b>。判定口径见 {@link SyllabusNames#isInvisible}。
         */
        INVALID_NAME,

        /**
         * 🔴 <b>名字被占了</b>:整棵树里已经有一个同名的考点(或题型)。
         *
         * <p>与 {@link #INVALID_NAME} 分开,因为下一步完全不同:名字不合法是「这个名字本身不能用」,
         * 而这里是「这个名字挺好,只是已经有人叫了」—— 界面要说的是<b>它被谁占着</b>。
         * <p>
         * 状态码取 409,与 {@link #NODE_HAS_RECORDS} 同档:两者都不是「你写错了」,
         * 而是「树当前的状态不允许这次操作」。
         */
        NAME_TAKEN,

        /** 近五年频次必须是非负整数。 */
        INVALID_FREQUENCY,

        /** 顺序列表不是现有条目的一个排列 —— 少一个就等于悄悄删一个。 */
        ORDER_NOT_A_PERMUTATION,

        /** 记录的来源与目标是同一个考点,这次搬迁没有意义。 */
        SAME_NODE
    }

    private final Reason reason;

    /** 只有 {@link Reason#NODE_HAS_RECORDS} / {@link Reason#GROUP_NOT_EMPTY} 用得上;其余是 0。 */
    private final int count;

    public SyllabusEditException(Reason reason, String message) {
        this(reason, message, 0);
    }

    public SyllabusEditException(Reason reason, String message, int count) {
        super(message);
        this.reason = reason;
        this.count = count;
    }

    public Reason reason() {
        return reason;
    }

    public int count() {
        return count;
    }

    // —— 工厂方法。每一个都把用户输入过一遍 echo ——

    public static SyllabusEditException nodeNotFound(String nodeCode) {
        return new SyllabusEditException(Reason.NODE_NOT_FOUND,
                "骨架树里没有这个考点:" + echo(nodeCode));
    }

    public static SyllabusEditException groupNotFound(String groupCode) {
        return new SyllabusEditException(Reason.GROUP_NOT_FOUND,
                "骨架树里没有这个题型:" + echo(groupCode));
    }

    /**
     * 🔴 删除守则的那句话。<b>必须说出有几条记录</b> —— 「不能删」而不说为什么,
     * 用户下一步只会去别处找个更硬的删法。
     */
    public static SyllabusEditException nodeHasRecords(String nodeCode, int count) {
        return new SyllabusEditException(Reason.NODE_HAS_RECORDS,
                "这个考点上挂着 " + count + " 条记录,不能直接删除:" + echo(nodeCode)
                        + "。记录是挂在 code 上的,删掉考点它们就成了孤儿,覆盖率的分母和分子会同时失真。"
                        + "正确的做法是:先把这些记录移到别的考点(records/move),或者把这个考点归档(archive)。",
                count);
    }

    public static SyllabusEditException groupNotEmpty(String groupCode, int nodeCount) {
        return new SyllabusEditException(Reason.GROUP_NOT_EMPTY,
                "这个题型下面还有 " + nodeCount + " 个考点(含已归档),不能删除:" + echo(groupCode)
                        + "。先把考点移到别的题型,或者逐个处理掉。",
                nodeCount);
    }

    public static SyllabusEditException alreadyArchived(String nodeCode) {
        return new SyllabusEditException(Reason.NODE_ALREADY_ARCHIVED,
                "这个考点已经归档了:" + echo(nodeCode));
    }

    /**
     * 目标考点已归档。<b>消息里必须点名「取消归档」</b> —— 说「没有这个考点」会把用户
     * 支到刷新页面那条死路上,而他刚在归档清单里亲眼看见过它。
     */
    public static SyllabusEditException nodeArchived(String nodeCode) {
        return new SyllabusEditException(Reason.NODE_ARCHIVED,
                "这个考点已经归档了,不能把记录搬到它上面:" + echo(nodeCode)
                        + "。归档的意思正是「不再往上挂东西」,搬进去等于把记录挪到不参与差集的地方,"
                        + "覆盖率会跟着少一块。要么先给它取消归档(unarchive),要么换一个没归档的考点。");
    }

    public static SyllabusEditException notArchived(String nodeCode) {
        return new SyllabusEditException(Reason.NODE_NOT_ARCHIVED,
                "这个考点没有归档,谈不上取消归档:" + echo(nodeCode));
    }

    public static SyllabusEditException invalidName(String detail) {
        return new SyllabusEditException(Reason.INVALID_NAME, detail);
    }

    /**
     * 🔴 考点名被树上另一个考点占着。<b>必须说出它在哪个题型下</b>。
     *
     * <p>只说「名字重复了」是不够的:唯一性是<b>整棵树</b>的,占名字的那个考点很可能在
     * 另一个题型下 —— 用户正盯着自己这个题型,看不到冲突对象,只会觉得这条报错莫名其妙。
     * 说出题型名,他才找得到。
     *
     * @param wanted   用户这次想用的名字(用户输入)
     * @param existing 占着这个名字的考点的<b>原样名字</b>。它未必与 {@code wanted} 逐字相同 ——
     *                 大小写、全角半角、空格都不构成区别,所以要把两个都摆出来
     */
    public static SyllabusEditException nodeNameTaken(String wanted, String existing, String groupName) {
        return new SyllabusEditException(Reason.NAME_TAKEN,
                "这个考点名已经有人叫了:" + echo(wanted)
                        + "。占着它的是「" + echo(groupName) + "」下面的「" + echo(existing) + "」"
                        + NAME_COMPARISON_NOTE + WHOLE_TREE_NOTE);
    }

    /**
     * 🔴 考点名被一个<b>已归档</b>的考点占着 —— 这是最容易让人困惑的一种冲突,
     * 因为<b>用户在树上根本看不见它</b>。
     *
     * <p>所以这句话必须做到三件事:显式说明占名字的东西已经归档、说出它在哪、给出出路。
     * 少了任何一件,用户看到的就是「这个名字明明没人用,却说被占了」——
     * 下一步只会是换个近义词硬凑一个,而那正是重名想防的事。
     *
     * <p>归档的考点也算占名字,不是苛刻,是<b>不变式的必要条件</b>:
     * 放它过去的话,给那个归档考点 {@code unarchive} 一下就静默造出一个重名。
     */
    public static SyllabusEditException nodeNameTakenByArchived(String wanted, String existing, String groupName) {
        return new SyllabusEditException(Reason.NAME_TAKEN,
                "这个考点名被一个【已归档】的考点占着:" + echo(wanted)
                        + "。它是「" + echo(groupName) + "」下面的「" + echo(existing) + "」,"
                        + "已经归档了,所以你在树上看不见它 —— 这也正是这条报错最容易让人困惑的地方。"
                        + "归档的考点照样算占名字:否则给它取消归档就会静默造出一个重名。"
                        + "两条出路:给那个归档考点改个名(rename),或者先给它取消归档(unarchive)再决定怎么处理。"
                        + "归档清单在 GET /api/v1/syllabus/archived。"
                        + NAME_COMPARISON_NOTE);
    }

    /** 题型名被另一个题型占着。题型没有归档这回事,所以只有这一种。 */
    public static SyllabusEditException groupNameTaken(String wanted, String existing) {
        return new SyllabusEditException(Reason.NAME_TAKEN,
                "这个题型名已经有人叫了:" + echo(wanted) + "。占着它的是「" + echo(existing) + "」"
                        + NAME_COMPARISON_NOTE
                        + "题型名同样整棵树唯一 —— 两个同名的题型,「整块空白」会指向两个地方。");
    }

    /** 为什么「看起来不一样」也算同一个名字。三条消息共用,口径见 {@link SyllabusNames#nameKey}。 */
    private static final String NAME_COMPARISON_NOTE =
            "(比较时忽略前后空格、内部多余空格、全角半角、英文大小写,以及变体选择符这类看不见的码点,"
                    + "所以看起来不一样也可能是同一个名字)。";

    /** 为什么范围是整棵树而不是同题型内。见 {@link SyllabusNames} 的类注释。 */
    private static final String WHOLE_TREE_NOTE =
            "考点名在整棵树里唯一,不是「同题型内唯一」:面板上是按名字挑考点的,不显示题型,"
                    + "跨题型同名一样分不出来。要区分就起两个不同的名字(「增长率计算」vs「增长率速算」),"
                    + "而不是靠所在题型去区分。";

    public static SyllabusEditException invalidFrequency(int value) {
        return new SyllabusEditException(Reason.INVALID_FREQUENCY,
                "近五年频次必须是非负整数,收到的是 " + value);
    }

    public static SyllabusEditException orderNotAPermutation(String detail) {
        return new SyllabusEditException(Reason.ORDER_NOT_A_PERMUTATION, detail);
    }

    public static SyllabusEditException sameNode(String nodeCode) {
        return new SyllabusEditException(Reason.SAME_NODE,
                "记录的来源和目标是同一个考点:" + echo(nodeCode));
    }

    /**
     * 把用户送来的字符串截到可以放心回声、放心落日志的长度。
     *
     * <p>不是转义、不是过滤 —— 只管长度。要挡的是<b>体量</b>,不是字符。
     */
    private static String echo(String userInput) {
        if (userInput == null) {
            return "(空)";
        }
        return userInput.length() <= MAX_ECHOED_VALUE_LENGTH
                ? userInput
                : userInput.substring(0, MAX_ECHOED_VALUE_LENGTH) + "…(已截断)";
    }
}
