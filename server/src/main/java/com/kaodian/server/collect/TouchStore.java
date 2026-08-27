package com.kaodian.server.collect;

import java.util.List;

/**
 * 行为层的存储契约。
 *
 * <h2>为什么先是接口,而且现在的实现是一个文件</h2>
 *
 * docs/10 §零 写明:数据层落库最早到<b>阶段 1 的 {@code 1.2.4}</b>,
 * 「阶段 0 是本地文件夹 + 纯文本」,阶段 0/1 全本地、不需要服务器。
 * 所以现在的实现就是一个 JSON 文件,没有数据库、没有 ORM、没有连接池。
 * <p>
 * 留这个接口不是为了「将来可能要换」这种空泛理由,而是因为换库这件事<b>已经排好期了</b> ——
 * 到阶段 1 换成 JDBC 时,只增加一个实现类,{@link com.kaodian.server.coverage.CoverageService}
 * 一行不用改。docs/10 §2.2 说的「包之间只通过接口调用,不共享 DAO」就是这个意思。
 *
 * <h2>🔴 这个接口上没有「按内容搜索」这类方法,也永远不会有</h2>
 *
 * 因为 {@link Touch} 里根本没有内容可搜。查询维度只有考点、来源名、时间 ——
 * 「有没有、几次、多久前」,与 01 §2.2 的能力边界逐字对应。
 */
public interface TouchStore {

    /** 全部记录,按发生时间升序。 */
    List<Touch> findAll();

    /** 某个考点下的全部记录。 */
    List<Touch> findByNode(String nodeCode);

    /**
     * 追加一条记录。
     *
     * <p><b>这个方法必须永不失败地把记录落下来。</b> docs/08 §1.3.7:
     * 识别服务不可用时,记录动作本身永不失败 —— 先落地,标签可以之后再补。
     * 所以调用方在识别失败时应当照样写入一条 {@code nodeCode} 已由用户指定的记录,
     * 而不是把整条记录丢掉。
     */
    Touch append(Touch touch);

    /** 记录总数。 */
    int count();

    /**
     * 把挂在 {@code fromNodeCode} 上的记录整体改挂到 {@code toNodeCode}。
     *
     * <h2>🔴 这是「删除守则」给出的出路,不是一个通用的编辑接口</h2>
     *
     * 记录挂在 code 上,所以删掉一个已有记录的考点会让那些记录成为孤儿
     * (见 {@code SyllabusStore#deleteNode})。这个方法存在,是为了让「我想删掉这个考点」
     * 有一个<b>不丢数据的答复</b>:先把记录搬到另一个考点,搬完那个考点就是空的,可以删了。
     * <p>
     * 实现必须保证<b>记录总数不变</b> —— 它搬家,不扔东西。搬迁只改 {@code nodeCode} 一个字段,
     * 时间戳、来源名、做题数原样保留:「多久前」是这个产品仅有的三个维度之一,不能因为搬家而重置。
     * <p>
     * 目标 code 是否在骨架树里、是否已归档,由 {@code SyllabusStore#moveRecords} 在调用前判定 ——
     * 这个接口不认识骨架树。
     *
     * @return 搬走了几条;来源上本来就没有记录时返回 0
     */
    int reassign(String fromNodeCode, String toNodeCode);
}
