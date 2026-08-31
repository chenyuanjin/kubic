package com.kaodian.server.syllabus;

import java.util.List;

/**
 * 骨架层 —— 一棵维护好的考点树,{@code 模块 → 题型 → 考点} 三层。
 *
 * <h2>只做三层,不做第四层</h2>
 *
 * 决策记录 §2.5 已定:三层足够表达「整块题型都没碰过」,而这正是树相对扁平清单的<b>唯一优势</b>。
 * 再往下切会让粒度失控 —— docs/详细排期 甚至把「标到 500 题还在冒新考点」列为分类过细的诊断信号。
 * <p>
 * 🔴 <b>这条限制长在数据结构上,不是长在校验里</b>:{@link Node} 没有 {@code children},
 * {@link Group} 没有嵌套的 {@code groups}。想加第四层必须先改这两个 record 的字段表,
 * 而那是一次要过 决策记录 §2.5 的产品决定,不是一次「顺手支持一下」。
 *
 * <h2>🔴 树里只有名称、层级、频次统计</h2>
 *
 * 没有题干、没有解析、没有任何机构的课程内容。
 * {@code recent5yCount} 是<b>统计事实</b>(某考点近五年出现几次),
 * 不受真题汇编著作权保护 —— 依据 docs/数据线:汇编著作权保护的是「选择与编排」,
 * 不延伸到底层事实。真题原文留在离线区,永不进入本仓库与线上库。
 * <p>
 * 考点命名为<b>自行归纳</b>,不沿用任何机构既有体系与措辞(R-07 / docs/实施路径 §1.2)。
 * 这也是<b>没有「从机构导入考点体系」这类入口</b>的原因:批量导入的下一步一定是照抄。
 *
 * <h2>🔴 code 是主键,名字不是</h2>
 *
 * 行为层的每一条记录都挂在 {@link Node#code()} 上({@code Touch.nodeCode})。
 * 阶段 1 的任务是「骨架冷启动 + <b>人工校正命名</b>」(docs/实施路径 §1.2)—— 也就是说,
 * 名字会被反复改。如果记录挂在名字上,每改一次名就断一次历史;挂在 code 上,
 * 改名对行为层<b>完全没有影响</b>。
 * <p>
 * 这就是当初用 code 而不是中文名做主键的全部理由,也是
 * {@link SyllabusStore#renameNode} 敢于存在、而 {@link SyllabusStore#deleteNode}
 * 必须被守住的原因。
 */
public record Syllabus(
        Subject subject,
        List<Group> groups
) implements SyllabusSource {

    /** 一棵固定的树本身就是一个(永不变化的)来源。见 {@link SyllabusSource}。 */
    @Override
    public Syllabus current() {
        return this;
    }

    public record Subject(
            String code,
            String region,
            String exam,
            String module,
            String recent5yWindow
    ) {
        /** 如「山东省考 · 行测 · 资料分析」。 */
        public String display() {
            return region + " · " + exam + " · " + module;
        }
    }

    /**
     * 题型层。「整块空白」这个产品语义就落在这一层。
     *
     * <p>{@link #nodes()} 是这个题型下的<b>全部</b>考点,按展示顺序,含已归档的。
     * 参与差集运算的是 {@link #activeNodes()}。
     */
    public record Group(String code, String name, List<Node> nodes) {

        /**
         * 参与差集运算的考点 —— <b>归档的不算</b>。
         *
         * <p>覆盖率的分母就是它。归档一个考点会让分母和分子同时少一个,比值仍然诚实;
         * 而它的历史记录一条都没动,随时可以取消归档接回来。
         */
        public List<Node> activeNodes() {
            return nodes.stream().filter(n -> !n.archived()).toList();
        }

        /** 已归档的考点。它们不进树、不进盲区,但 code 还在,历史记录仍然认得出来。 */
        public List<Node> archivedNodes() {
            return nodes.stream().filter(Node::archived).toList();
        }

        /** 组内频次合计。<b>只算参与差集的那些</b>,否则归档之后这个数会对不上下面的考点。 */
        public int recent5yCount() {
            return activeNodes().stream().mapToInt(Node::recent5yCount).sum();
        }
    }

    /**
     * 考点层 —— 差集运算的最小单位。
     *
     * <p>🔴 <b>没有 {@code children}</b>。第四层不是「暂时不做」,是结构上没有这个位置(决策记录 §2.5)。
     *
     * @param recent5yCount 近五年出现次数。<b>统计事实</b>,也是盲区排序里「值不值得补」的权重
     * @param archived      归档标记。归档的考点退出差集,但 code 与历史记录都还在 ——
     *                      它是「这个考点当初命名错了、可上面已经有记录」的正确出路
     */
    public record Node(String code, String name, int recent5yCount, boolean archived) {}

    /** 参与差集的全部考点,按树的顺序摊平。<b>覆盖度的分母。</b> */
    public List<Node> allNodes() {
        return groups.stream().flatMap(g -> g.activeNodes().stream()).toList();
    }

    /** 全部考点,含已归档的。用于按 code 反查名字(时间线上的老记录)与导出。 */
    public List<Node> allNodesIncludingArchived() {
        return groups.stream().flatMap(g -> g.nodes().stream()).toList();
    }

    public int nodeCount() {
        return allNodes().size();
    }

    /**
     * 按 code 找<b>参与差集的</b>考点。挂载记录时用它校验 —— 挂不上就说明那个 code 不在树里。
     *
     * <p>归档的考点在这里查不到,于是新记录挂不上去。这是有意的:归档的意思正是
     * 「这个考点不再使用了」,继续往上挂新记录会让归档变成一句空话。
     */
    public Node node(String code) {
        return allNodes().stream()
                .filter(n -> n.code().equals(code))
                .findFirst()
                .orElse(null);
    }

    /** 按 code 找考点,含已归档的。反查名字用这个 —— 归档不该让时间线上的老记录变成无名氏。 */
    public Node nodeIncludingArchived(String code) {
        return allNodesIncludingArchived().stream()
                .filter(n -> n.code().equals(code))
                .findFirst()
                .orElse(null);
    }

    /** 按 code 找题型。 */
    public Group group(String code) {
        return groups.stream()
                .filter(g -> g.code().equals(code))
                .findFirst()
                .orElse(null);
    }

    /** 某个考点属于哪个题型。含已归档的考点。 */
    public Group groupOf(String nodeCode) {
        return groups.stream()
                .filter(g -> g.nodes().stream().anyMatch(n -> n.code().equals(nodeCode)))
                .findFirst()
                .orElse(null);
    }
}
