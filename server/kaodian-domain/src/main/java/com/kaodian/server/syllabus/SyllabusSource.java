package com.kaodian.server.syllabus;

/**
 * 「当前这棵骨架树」的来源。
 *
 * <h2>为什么读取方不能直接持有一个 {@link Syllabus}</h2>
 *
 * 骨架层从这一版起是<b>可写的</b>(docs/实施路径 §1.2 阶段 1 是「骨架冷启动 + 人工校正命名」,
 * 没有考点管理就走不到那一步)。{@link Syllabus} 是不可变的 record,所以一旦有人把它当成
 * 单例 bean 注入进去,拿到的就是<b>进程启动那一刻的快照</b> ——
 * 新增一个考点之后覆盖率的分母不动,而且不会报错。
 * <p>
 * 于是读取方一律依赖这个接口,每次算差集之前问一次「现在的树长什么样」。
 * 生产实现是 {@link SyllabusStore};而一棵固定的树本身也是一个合法的来源
 * ({@link Syllabus#current()} 返回它自己)—— 不关心编辑的调用方(测试、离线脚本)
 * 可以直接递一棵树进去,不必为此造一个假的 store。
 */
public interface SyllabusSource {

    /** 当前的骨架树。<b>每次调用都要重新问,不要缓存返回值。</b> */
    Syllabus current();
}
