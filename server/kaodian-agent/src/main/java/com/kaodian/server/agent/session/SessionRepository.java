package com.kaodian.server.agent.session;

import java.util.List;
import java.util.Optional;

/**
 * 会话元信息的存储。
 *
 * <p>与 {@code RunRepository} 分开:那边是 append-only 的执行流水,这边是可改可删的元信息。
 * 合成一个接口会让「删会话」这个动作看起来像是能删掉执行记录 —— 而那两件事的语义不一样,
 * 见 {@link #delete}。
 */
public interface SessionRepository {

    /** 建档或整体覆写。 */
    void save(AgentSession session);

    Optional<AgentSession> find(String sessionId);

    /** 某用户的全部会话,按 {@code updatedAt} <b>倒序</b>(最近用过的排前面)。 */
    List<AgentSession> findByUser(long userId);

    /**
     * 删除会话<b>及其全部 run 数据</b>。
     *
     * <p>🔴 是真删,不是打个标记 —— 用户点「删除对话」时期待的就是它不见了。
     * 软删除会让「删了但其实还在磁盘上」成为默认状态,而这一层存着用户问过什么,
     * 那是比覆盖率更私人的东西。
     *
     * @return 是否真的删掉了(会话不存在时 false)
     */
    boolean delete(String sessionId);
}
