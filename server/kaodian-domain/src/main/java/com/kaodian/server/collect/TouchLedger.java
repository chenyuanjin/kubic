package com.kaodian.server.collect;

import com.kaodian.server.syllabus.NodeRecordLedger;

/**
 * 把 {@link TouchStore} 接到骨架层要的那扇小窗上 —— {@link NodeRecordLedger} 的唯一实现。
 *
 * <h2>为什么中间要有这么一层</h2>
 *
 * 骨架层需要知道两件事才能守住删除守则:「这个考点上挂着几条记录」和
 * 「把它们搬到另一个考点去」。它<b>不需要、也不该</b>知道 {@code Touch} 长什么样 ——
 * docs/technical/INDEX.md §2.2:包之间只通过接口调用。
 * <p>
 * 所以接口定义在 {@code syllabus} 侧,实现放在 {@code collect} 侧,依赖方向是
 * <b>行为层去满足骨架层的需求</b>,而不是骨架层反过来认识行为层的数据结构。
 * 这个类薄到只有两行,薄正是它的价值:它是两个包之间唯一的接触面。
 */
public final class TouchLedger implements NodeRecordLedger {

    private final TouchStore store;

    public TouchLedger(TouchStore store) {
        this.store = store;
    }

    /**
     * 🔴 <b>跨用户</b>数,不按当前这个人收窄 —— 理由写在
     * {@link TouchStore#countByNodeAcrossUsers} 上:骨架树是全进程共用的一棵,
     * 删掉一个节点会让所有人挂在它上面的记录变成孤儿。
     */
    @Override
    public int countFor(String nodeCode) {
        return store.countByNodeAcrossUsers(nodeCode);
    }

    /** 🔴 同样跨用户 —— <b>数的口径与搬的口径必须是同一个</b>,见 {@link TouchStore#reassign}。 */
    @Override
    public int moveAll(String fromNodeCode, String toNodeCode) {
        return store.reassign(fromNodeCode, toNodeCode);
    }
}
