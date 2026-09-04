package com.kaodian.server.api.auth;

import com.kaodian.server.api.dto.auth.TokenDto;
import com.kaodian.server.api.dto.auth.TokenPageResponse;
import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.auth.AccessToken;
import com.kaodian.server.auth.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 登录设备列表与单条退出 —— 界面 D26,契约 §7.4。
 *
 * <h2>为什么它不在 {@link AccountController} 里</h2>
 *
 * 契约把这一组挂在 {@code /tokens} 下,和 {@code M4} 的 {@code /tokens/readonly}、
 * {@code /tokens/revoke-all} 同域。留在 {@code /account/sessions} 的代价很具体:
 * 🔴 <b>「只读令牌不能管理令牌」这条锁要写两处路径</b>,而写两处的东西迟早只剩一处
 * ({@code M5-账号与登录通道} §9.7 裁定 1)。
 *
 * <h2>🔴 这一整个控制器只读令牌一律进不来,不论方法</h2>
 *
 * 见 {@link CurrentSession#requireTokenManagement()}:少了它,一条泄露出去的
 * {@code ro_} 就能把账号里所有的 {@code at_} 全吊销掉。
 *
 * <p>⚠️ <b>请求体里没有「本机队列还有几条」这样的字段,一个都没有</b>({@code M5} §6.2):
 * 服务端看不见别人机器上的离线队列,收一个它无法验证、也无法据以改变行为的布尔,
 * 只会让端以为服务端在管这件事。旧的 {@code RevokeSessionRequest.confirmedPendingUploads}
 * 因此被删掉 —— 那条日志有价值,但它该是<b>被退设备侧的端上埋点</b>,不占契约字段。
 */
@RestController
@RequestMapping("/api/v1/tokens")
public class TokenController {

    /** 一页最多几条。设备列表天然很短,这个数只是防一个手写的 {@code limit=100000}。 */
    private static final int MAX_LIMIT = 100;

    private static final int DEFAULT_LIMIT = 20;

    private final TokenService tokens;

    public TokenController(TokenService tokens) {
        this.tokens = tokens;
    }

    /**
     * 登录设备列表。
     *
     * <p>🔴 <b>只返回此刻可用的行</b> —— 已吊销/已过期的不在列表里。
     * {@code U5.6} §6.2 ※11:陈旧列表上的「退出这台」若仍可点,
     * 用户会以为退掉了一台其实已经不在的设备。那一页回答的是「现在有谁登着」。
     *
     * @param cursor 上一页的 {@code nextCursor},原样回传。首页不带
     */
    @GetMapping
    public TokenPageResponse list(CurrentSession session,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        session.requireTokenManagement();
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Instant now = Instant.now();
        String currentHash = session.token().tokenHash();

        // 排序键必须是全序,否则同一个游标在两次请求里可能落在不同位置 ——
        // lastUsedAt 会撞(同一秒登录两台),所以拿 tokenHash 兜底。
        List<AccessToken> usable = tokens.sessionsOf(session.userId()).stream()
                .filter(t -> t.isUsableAt(now))
                .sorted(Comparator.comparing(AccessToken::lastUsedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AccessToken::tokenHash))
                .toList();

        // ponytail: O(n) 线性定位游标。一个账号的在用设备是个位数,
        // 建索引省下的时间比不上多出来的一处会写错的代码。真到成千上万条再说。
        int from = 0;
        if (cursor != null && !cursor.isBlank()) {
            int at = indexOfHash(usable, cursor);
            if (at < 0) {
                // 游标指向的那一条在翻页途中被吊销了。回第一页而不是报错:
                // 这一页是「现在有谁登着」,给一个 400 只会让用户卡在一个刷不出来的列表上。
                from = 0;
            } else {
                from = at + 1;
            }
        }
        List<TokenDto> items = usable.subList(Math.min(from, usable.size()),
                        Math.min(from + size, usable.size())).stream()
                .map(t -> TokenDto.from(t, currentHash))
                .toList();
        boolean hasNext = from + size < usable.size();
        return new TokenPageResponse(items,
                hasNext && !items.isEmpty() ? items.get(items.size() - 1).tokenId() : null);
    }

    /**
     * 退出某一台。<b>立即生效,天然幂等</b> —— 重复吊销返回 {@code revoked=false},不报错。
     *
     * <p>🔴 越权吊销别人的会话是<b>显式失败</b>({@code 403 NOT_YOUR_SESSION}),
     * 不是静默无事发生。
     */
    @PostMapping("/{tokenId}/revoke")
    public Map<String, Object> revoke(CurrentSession session, @PathVariable String tokenId) {
        session.requireTokenManagement();
        try {
            return Map.of("revoked", tokens.revokeByHash(session.userId(), tokenId));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_SESSION", e.getMessage());
        }
    }

    private static int indexOfHash(List<AccessToken> list, String hash) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).tokenHash().equals(hash)) {
                return i;
            }
        }
        return -1;
    }
}
