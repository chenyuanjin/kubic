package com.kaodian.server.collect;

import java.util.List;

/**
 * 「我已掌握」的存储契约 —— docs/10 §5.2 的 {@code user_assertion} 表。
 *
 * <h2>为什么先是接口,而且现在的实现是一个文件</h2>
 *
 * 与 {@link TouchStore} / {@link RecordTagStore} 逐字同理:docs/10 §零 写明数据层落库最早到
 * 阶段 1 的 {@code 1.2.4}。到那天换成 JDBC 只是多一个实现类。
 *
 * <h2>🔴 幂等在这一层,不在调用方</h2>
 *
 * 这张表没有「更新」这个动作 —— 一个考点要么被声明过,要么没有,<b>没有第三种状态</b>。
 * 所以两个写方法都是幂等的:{@link #put} 重复调用不新增一行也不报错,
 * {@link #remove} 删一个没声明过的考点同样不报错。
 * <p>
 * 放在存储层而不是让调用方「先查再写」,与 {@link TouchStore#append} 的理由是同一条:
 * 先查再写有一个窗口,而这个按钮在界面上就是<b>连点会重复发请求</b>的那一类。
 * 两个线程各自查到「没有」然后各自写一行,概览里那个「已声明 N 个」就会变成 N+1,
 * 而它是 docs/10 §6.4 要求单列出来给用户看的那个数。
 *
 * <h2>🔴 这个接口上没有任何以「考点名字」为参数的方法</h2>
 *
 * 只有 {@code nodeCode}。与 {@code R-07} 在打标那一侧的实现同一条:
 * 只要写入口上没有传自由文本的位置,自己起名的考点就进不了库。
 */
public interface AssertionStore {

    /** 全部声明,按写入顺序。 */
    List<UserAssertion> findAll();

    /** 某个考点上的声明;没有返回 {@code null}(「查一个没声明过的考点」是调用方要分辨的情况)。 */
    UserAssertion find(String nodeCode);

    /**
     * 声明掌握。<b>幂等</b>。
     *
     * <h2>🔴 已经声明过时,{@code assertedAt} 保持第一次那个值</h2>
     *
     * 不是「后写的覆盖先写的」。这个字段唯一的用处是在界面上说「你在 X 月 X 日说过你会了」,
     * 而<b>连点两下按钮不该改写那句话</b>。真要重新计时,得先取消再声明 ——
     * 那是两次明确的动作,不是一次误触。
     *
     * @return 库里那一行:新声明时是传进来的这条,已经声明过时是<b>原来那条</b>
     */
    UserAssertion put(UserAssertion assertion);

    /**
     * 取消声明。<b>幂等 —— 没声明过也不报错。</b>
     *
     * <p>取消一个没声明过的考点,用户想要的结果(「这个考点不带『我已掌握』」)已经成立了。
     * 这时候回 404 是在报告一个<b>不存在的失败</b>,而界面除了弹一句让人困惑的话之外什么都做不了。
     *
     * @return 真的删掉了一行返回 {@code true};本来就没有返回 {@code false}
     */
    boolean remove(String nodeCode);

    /** 声明的总数。概览里单列的那一格就是它(docs/10 §6.4:<b>断言单列不并入</b>)。 */
    int count();
}
