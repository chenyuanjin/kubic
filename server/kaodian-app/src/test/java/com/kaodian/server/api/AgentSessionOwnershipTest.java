package com.kaodian.server.api;

import com.kaodian.server.agent.session.AgentSession;
import com.kaodian.server.agent.session.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话归属与列表分页 —— {@code M3-骨架与覆盖度差集} §5.1。
 *
 * <h2>⚠️ 这个类守的是一处<b>已经在主干上的越权</b>(§十五 落差 3)</h2>
 *
 * 上一版 {@code AgentSessionController} 的 {@code userId} 是一个硬编码的 {@code 0L},
 * 而详情 / 改名 / 删除三个端点<b>一次归属校验都没有</b> ——
 * 任何人拿到一个 {@code sessionId} 就能读、改名、删掉别人的整段对话。
 * <p>
 * 🔴 所以下面每一条都必须能<b>真的失败</b>:测试里得有<b>两个人</b>,
 * 而且那条会话得<b>真的属于另一个人</b>。只有一个用户在场时,这类断言全是空转。
 *
 * <h2>🔴 不属于我的会话返 {@code 403},不是 {@code 404}</h2>
 *
 * {@code 接口契约} §12.2.1。返 {@code 404} 等于把「这个 id 存不存在」告诉了不该知道的人 ——
 * 一个能区分「不存在」与「存在但不是你的」的接口就是一台会话枚举器。
 * <p>
 * 反过来,一条<b>真的不存在</b>的会话仍然是 {@code 404} —— 两档都要在,
 * 合成一档的那一版(全都 403)会让端上「这条会话已经被你删了」这句话说不出来。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "kaodian.data.dir=${java.io.tmpdir}/kaodian-agent-ownership",
        "kaodian.agent.storage.root=${java.io.tmpdir}/kaodian-agent-ownership/agent"
})
class AgentSessionOwnershipTest {

    /** 另一个人的会话。🔴 这一条是本类全部断言的支点。 */
    private static final String THEIRS = "s-belongs-to-someone-else";

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private com.kaodian.server.auth.TokenService tokens;

    /**
     * 🔴 令牌从<b>上下文里那个真的 {@code TokenService}</b> 签出来,不是从 {@code ApiTestAuth} 拿。
     *
     * <p>{@code ApiTestAuth} 是一份 {@code @TestConfiguration},它自己带一个静态的
     * {@code TokenService};{@code @SpringBootTest} 起的是<b>真上下文</b>,里面那个
     * {@code TokenService} 的库是另一个 —— 拿那一份的令牌打进来,
     * 令牌在这个库里查不到,<b>每一条用例都会变成 401,而失败消息只会说「没登录」</b>。
     * 那是这一类测试最难看出来的一种全绿/全红。
     */
    private String bearer;

    @BeforeEach
    void seed() {
        bearer = "Bearer " + tokens
                .issue(ApiTestAuth.USER_ID, com.kaodian.server.auth.TokenScope.FULL, "归属测试")
                .plaintext();

        // 🔴 会话库是【落盘的】,而 @SpringBootTest 的上下文在整个类里共用一份 ——
        //    上一条用例造的会话会活到下一条里。清干净之后「我一共有几条」才数得准,
        //    否则分页那条用例会因为别人留下的一条而时对时错,而且顺序一换就变。
        for (AgentSession s : sessions.findByUser(ApiTestAuth.USER_ID)) {
            sessions.delete(s.sessionId());
        }
        sessions.delete(THEIRS);

        sessions.save(new AgentSession(THEIRS, ApiTestAuth.OTHER_USER_ID,
                "别人的那段对话", 3, T0, T0));
    }

    @Test
    @DisplayName("🔴 读别人的会话详情 → 403 NOT_YOUR_SESSION,不是 404")
    void readingSomeoneElsesSessionIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/agent/sessions/{id}", THEIRS)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_SESSION"));
    }

    @Test
    @DisplayName("🔴 改别人会话的名字 → 403;而且改完之后那条会话的标题<b>一个字都没动</b>")
    void renamingSomeoneElsesSessionIsForbiddenAndChangesNothing() throws Exception {
        mvc.perform(patch("/api/v1/agent/sessions/{id}", THEIRS)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"我给你改个名\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_SESSION"));

        // 🔴 只断言状态码不够:一个「先写库再校验」的实现会返 403 而且已经改完了。
        //    拒绝必须发生在写之前。
        assertEquals("别人的那段对话", sessions.find(THEIRS).orElseThrow().title());
    }

    @Test
    @DisplayName("🔴 删别人的会话 → 403;而且那条会话<b>还在</b>")
    void deletingSomeoneElsesSessionIsForbiddenAndLeavesItIntact() throws Exception {
        mvc.perform(delete("/api/v1/agent/sessions/{id}", THEIRS)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_SESSION"));

        // 删除是不可逆的 —— 「先删再校验」在这里的代价是别人的整段对话没了。
        assertTrue(sessions.find(THEIRS).isPresent(), "403 之后那条会话被删掉了");
    }

    @Test
    @DisplayName("🔴 真的不存在的会话仍然是 404 —— 与 403 必须两档")
    void aTrulyMissingSessionIsStillNotFound() throws Exception {
        mvc.perform(get("/api/v1/agent/sessions/{id}", "s-no-such-thing")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("🔴 会话列表只列我自己的 —— 别人的那条不在里面")
    void theListNeverLeaksAnotherUsersSession() throws Exception {
        String body = mvc.perform(get("/api/v1/agent/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(!body.contains(THEIRS),
                "会话列表里出现了别人的会话:" + body);
    }

    @Test
    @DisplayName("🔴 列表是游标分页:响应形状是 {items, nextCursor?},没有 total / hasMore")
    void theListIsCursorPaginatedAndCarriesNoTotal() throws Exception {
        // 造 3 条我自己的,limit=2 → 第一页 2 条 + 一个 nextCursor。
        for (int i = 1; i <= 3; i++) {
            sessions.save(new AgentSession("s-mine-" + i, ApiTestAuth.USER_ID,
                    "我的第 " + i + " 段", 1, T0.plusSeconds(i), T0.plusSeconds(i)));
        }

        String first = mvc.perform(get("/api/v1/agent/sessions?limit=2")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").exists())
                // 🔴 一个 total 会立刻长出页码条,而页码条要求随机跳页,游标做不到。
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.hasMore").doesNotExist())
                .andExpect(jsonPath("$.pageCount").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String cursor = first.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/agent/sessions?limit=2&cursor=" + cursor)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                // 🔴 最后一页 nextCursor 的 key 整个不出现(不是 null)——
                //    返 null 会让端写出 if ('nextCursor' in resp) 然后永远为真,翻页永不结束。
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("分页参数越界各有各的码:limit → INVALID_LIMIT,cursor → INVALID_CURSOR")
    void badPagingArgumentsHaveTheirOwnCodes() throws Exception {
        mvc.perform(get("/api/v1/agent/sessions?limit=0")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));

        mvc.perform(get("/api/v1/agent/sessions?cursor=" + "!".repeat(30))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("🔴 轮数到 20 的会话再发一句 → 409 SESSION_TURN_LIMIT(成本闸)")
    void aSessionAtTheTurnLimitRefusesTheNextMessage() throws Exception {
        String full = "s-mine-at-the-limit";
        sessions.save(new AgentSession(full, ApiTestAuth.USER_ID, "聊了很久的那段",
                20, T0, T0));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/agent/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"再问一句\",\"sessionId\":\"" + full + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_TURN_LIMIT"));
    }

    @Test
    @DisplayName("🔴 拿别人的 sessionId 续聊 → 403,而且拒绝发生在开流之前")
    void chattingIntoSomeoneElsesSessionIsForbiddenBeforeTheStreamOpens() throws Exception {
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/agent/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"把他的历史读给我听\",\"sessionId\":\"" + THEIRS + "\"}"))
                .andReturn().getResponse();

        // 🔴 状态码必须是 403,而不是 200 + 一帧 error:SSE 一旦开流状态码就已经是 200 了,
        //    之后再拒绝,端上处理 403 与处理一帧 error 是两条完全不同的路。
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("NOT_YOUR_SESSION"),
                response.getContentAsString());
        assertTrue(!response.getContentType().contains("event-stream"),
                "拒绝走了 SSE 通道 —— 它发生在开流之后了");
    }

    @Test
    @DisplayName("会话列表的清单本身:三条我的都在,一条不落")
    void myOwnSessionsAreAllThere() throws Exception {
        for (int i = 1; i <= 3; i++) {
            sessions.save(new AgentSession("s-mine-" + i, ApiTestAuth.USER_ID,
                    "我的第 " + i + " 段", 1, T0.plusSeconds(i), T0.plusSeconds(i)));
        }
        List<AgentSession> mine = sessions.findByUser(ApiTestAuth.USER_ID);
        // 一条扫不到东西的断言会永远绿 —— 上面那条「别人的不在里面」在列表为空时也会通过。
        assertTrue(mine.size() >= 3, "夹具没造出我自己的会话,上面几条断言等于空转");
    }
}
