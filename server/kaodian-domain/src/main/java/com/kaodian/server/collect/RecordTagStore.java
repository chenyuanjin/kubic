package com.kaodian.server.collect;

import java.util.List;

/**
 * 标签层的存储契约 —— docs/技术架构 §5.2 的 {@code record_tag} 表。
 *
 * <h2>库里存的<b>不是</b>全部标签</h2>
 *
 * 每条记录采集那一刻就有一条主标签({@link RecordTag#primaryOf}),它<b>不占一行</b> ——
 * 理由写在 {@link RecordTag#effectiveTagsOf} 上,一句话是:推出来的失败方向是「多算」,
 * 存出来的失败方向是「少算」,而少算这个产品唯一的那个数是无声的。
 * <p>
 * 所以这里存的是<b>后来发生的事</b>:识别补标的自动标签、事后手动加挂的标签、
 * 以及主标签被确认或丢弃之后的状态。查一条记录的全部标签,一律走
 * {@link RecordTag#effectiveTagsOf}，不要直接拿 {@link #findByRecord} 的返回当全集。
 *
 * <h2>为什么先是接口,而且现在的实现是一个文件</h2>
 *
 * 与 {@link TouchStore} 逐字同理:docs/技术架构 §零 写明数据层落库最早到阶段 1 的 {@code 1.2.4}。
 * 到那天换成 JDBC 只是多一个实现类,{@code CoverageService} 一行不用改。
 *
 * <h2>🔴 这个接口上没有「改标签」这个方法</h2>
 *
 * 只有 {@link #put},而它<b>拒绝改动 origin / recordId / nodeCode</b>。
 * 一个通用的 {@code update(tag)} 会让「把 auto 改成 manual」成为一次普通调用,
 * 而那条改动会让 {@code 1.2.5.2} 的准确率口径永久算不出来(见 {@link TagOrigin})。
 */
public interface RecordTagStore {

    /** 库里存着的全部标签行。<b>不含推出来的主标签</b>,见类注释。 */
    List<RecordTag> findAll();

    /** 某条记录名下存着的标签行,按写入顺序。 */
    List<RecordTag> findByRecord(String recordId);

    /** 按 id 找;没有返回 {@code null}(不抛 —— 「查一个不存在的标签」是调用方要分辨的情况)。 */
    RecordTag find(String tagId);

    /**
     * 新增或更新一行。
     *
     * <h2>🔴 同一个 id 已经存在时,这三个字段一个都不许变</h2>
     *
     * <table border="1">
     *   <caption>为什么是这三个</caption>
     *   <tr><th>字段</th><th>改了会怎样</th></tr>
     *   <tr><td>{@code origin}</td>
     *       <td>docs/技术架构 §5.2:确认写 {@code confirmed_at},<b>不把 auto 改成 manual</b>。
     *           改了,准确率口径(标对的/标了的)的分母会随用户的每一次确认缩水,
     *           指标恒等于 0 而不报错</td></tr>
     *   <tr><td>{@code recordId}</td>
     *       <td>标签换了宿主,而覆盖度是按记录去重的 —— 同一条记录会被数进两个考点,或一个都不进</td></tr>
     *   <tr><td>{@code nodeCode}</td>
     *       <td>「改挂到另一个考点」不是改标签,是<b>丢弃这条、新挂一条</b>。
     *           原地改会让「我曾经把它标成 A」这件事消失,而那正是丢弃标志要留住的东西</td></tr>
     * </table>
     *
     * <p>要挪到别的考点,正确做法是 {@link RecordTag#discard()} 这一条,再 {@code put} 一条新的。
     *
     * @return 落下的那行
     * @throws IllegalArgumentException 试图改动上面三个字段之一
     */
    RecordTag put(RecordTag tag);

    /**
     * 删掉某条记录名下的全部标签行 —— docs/技术架构 §6.2 {@code DELETE /records/{id}} 的「<b>级联删标签</b>」。
     *
     * <p>不级联会留下一批指向不存在记录的标签行。今天它们进不了覆盖度
     * ({@code CoverageService} 只认得上记录的标签),所以这不是一条会算错数的路;
     * 但那些行会一直躺在文件里,而<b>下一个读它们的人未必也做这层过滤</b>。
     *
     * @return 删掉了几行
     */
    int deleteByRecord(String recordId);

    /** 存着的标签行数。 */
    int count();
}
