package com.kaodian.server.auth;

import java.util.List;
import java.util.Optional;

/**
 * 令牌的存放处。
 *
 * <p>接口在这里、实现在 {@link FileTokenStore},与 {@code collect}/{@code syllabus}
 * 两个包同一形态:阶段 0/1 是一个 JSON 文件,{@code 1.2.4} 换 JDBC 时上层一行不动
 * (docs/technical/INDEX.md §零)。
 */
public interface TokenStore {

    /** 按哈希查。<b>调用方永远拿明文换哈希再查,明文不进这一层</b>。 */
    Optional<AccessToken> findByHash(String tokenHash);

    /** 某账号的全部令牌,含已吊销与已过期的 —— 设备管理页要自己决定显示哪些。 */
    List<AccessToken> findByUser(long userId);

    void save(AccessToken token);

    /** 覆盖已有的那一行。哈希不在库里时抛 {@link IllegalStateException}。 */
    void replace(AccessToken token);

    /**
     * 吊销一个账号的全部令牌,返回吊销了几条。
     *
     * <p>注销账号与「退出全部设备」都走它。<b>这是 docs/technical/后端系统设计与组件接入.md §1.9 里
     * 「立即失效」那四个字排除 JWT 的具体位置</b> —— JWT 在这一步无事可做。
     */
    int revokeAllOfUser(long userId, java.time.Instant now);
}
