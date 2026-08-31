package com.kaodian.server.syllabus;

/**
 * 行为层账本 —— 骨架层唯一被允许知道的关于「记录」的事。
 *
 * <h2>🔴 这个接口存在的唯一理由是「删除守则」</h2>
 *
 * 记录挂在 <b>code</b> 上。删掉一个已有记录的考点,那些记录就成了孤儿:
 * 覆盖率的分母少一个、分子也少一个,而<b>覆盖率是这个产品唯一的那个数</b>。
 * 所以 {@link SyllabusStore#deleteNode} 必须能回答两个问题 ——
 * 「这个考点上挂着几条记录」和「不删的话正确的出路是什么」。
 * <p>
 * 把这两件事分给两个类(树在 store 里删、记录在别处数)就等于承认存在一种调用顺序:
 * 先删树、后想起来还有记录。那条顺序一旦存在,迟早会有人走。
 * 所以计数与迁移都收在 {@link SyllabusStore} 的同一把锁下,由这个接口喂进去。
 *
 * <h2>为什么是接口,而不是直接依赖 {@code collect} 包</h2>
 *
 * docs/技术架构 §2.2:包之间只通过接口调用。{@code syllabus} 包不认识 {@code Touch},
 * 也不该认识 —— 它只需要一个整数和一次搬迁。装配这件事留在最外层的 {@code api} 包里
 * ({@code ApiBeans}),<b>谁组装,谁依赖框架</b>。
 */
public interface NodeRecordLedger {

    /** 这个考点上挂着几条记录。 */
    int countFor(String nodeCode);

    /**
     * 把挂在 {@code from} 上的记录整体改挂到 {@code to}。
     *
     * <p>这是「已有记录的考点想删掉」时的<b>正确出路之一</b>(另一条是归档)。
     * 实现必须保证记录总数不变 —— 这个方法搬家,不扔东西。
     *
     * @return 搬走了几条
     */
    int moveAll(String fromNodeCode, String toNodeCode);
}
