package com.kaodian.server.api;

import com.kaodian.server.api.record.AssertionController;
import com.kaodian.server.api.insight.CoverageController;
import com.kaodian.server.api.syllabus.SyllabusController;
import com.kaodian.server.config.DomainBeans;
import com.kaodian.server.coverage.CoverageReader;
import com.jayway.jsonpath.JsonPath;
import com.kaodian.server.api.dto.record.AssertionRequest;
import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.kaodian.server.collect.AssertionStore;
import com.kaodian.server.collect.InMemoryAssertionStore;
import com.kaodian.server.collect.InMemoryRecordTagStore;
import com.kaodian.server.collect.RecordTagStore;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.collect.UserAssertion;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusLoader;
import com.kaodian.server.syllabus.SyllabusSource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/technical/INDEX.md §6.4 最后一行:{@code POST/DELETE /assertions} —— 「我已掌握」/ 取消。
 *
 * <h2>🔴 这个文件验的第一件事是「那个大字没变」</h2>
 *
 * 决策记录 §5.2:<b>「『我已掌握』按钮是补丁不是解法。」</b> 所以这两个端点最重要的性质不是它们做了什么,
 * 是它们<b>没做什么</b> —— 按下去之后覆盖率一个字不动。
 * <p>
 * 「没做什么」的失败方式是无声的:把断言并进分子,接口全绿、界面更好看、用户更满意,
 * 而这个产品唯一的那个数字从此不再指向任何真实的东西。所以下面的用例是端到端地
 * <b>发一次 {@code GET /coverage/summary} → 发一次 POST → 再发一次 summary</b>,
 * 逐个字段比对,而不是只看 POST 的返回体。
 *
 * <h2>为什么把三个控制器一起装进来</h2>
 *
 * 断言的全部效果都<b>不在它自己的响应里</b> —— 而且现在<b>一个字都不在</b>:
 * 端点只回 {@code {"coverageChanged": false}}。效果分散在另外三处:
 * 盲区榜少一行(§6.4「排除已断言节点」)、概览多一格(§6.4「断言单列不并入」)、
 * 树上那一格的开关翻面。
 * 只切一个 {@code AssertionController} 的话,一个「写库了但三处口径一处都没接上」的实现会全绿。
 *
 * <h2>幂等在这两个端点上是<b>契约</b>,不是实现的宽容</h2>
 *
 * 「我已掌握」在界面上是那种<b>连点会重复发请求</b>的按钮,而「取消」经常发生在
 * 用户已经在另一个标签页取消过之后。两个方向都必须无声地成功 ——
 * 报一个「你已经声明过了」的错,用户除了困惑之外什么都做不了。
 */
@WebMvcTest(controllers = {AssertionController.class, CoverageController.class, SyllabusController.class})
// web 切片不扫 @Configuration,领域装配要显式带进来;ApiTestAuth 给每个请求装上真令牌(B0-4 默认拒绝)
@Import({DomainBeans.class, ApiTestAuth.class})
class AssertionApiTest {

    /**
     * 一个彻头彻尾的空白考点 —— 一条记录都没有,最容易被「按一下就算碰过」。
     *
     * <p>它同时是<b>盲区榜榜首</b>:默认口径 {@code recent5y_count} 下,没碰过的十个里
     * 平均数计算出现 6 次最多(见 {@code ApiContractTest#blindSpotsMatchDesignContract})。
     * 一个考点两种身份不是巧合 —— 这两件事本来就是同一件:榜上列的就是「一次都没碰过」的那些。
     */
    private static final String BLANK_NODE = "average-calc";

    /** 榜上第二名。榜首被声明掉之后顶上来的就是它,用来验「榜真的短了一行」。 */
    private static final String SECOND_BLIND_NODE = "current-value";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryTouchStore store;

    @Autowired
    private CountingAssertionStore assertions;

    @Autowired
    private SwappableSyllabus syllabus;

    @BeforeEach
    void reset() {
        store.reset(contractTouches());
        assertions.reset();
        // 🔴 @WebMvcTest 的上下文在一个类里是复用的,而「归档」那条用例会把树改掉。
        //    不还原的话,后面每一条断言 18 的用例会因为一件与它无关的事而红,
        //    而且红不红取决于方法执行顺序 —— 那是最难查的一种。
        syllabus.reset();
    }

    // ———————————————————— 一、覆盖率不动 ————————————————————

    /**
     * 🔴 <b>这一条如果被删掉或改松,「我已掌握」就退化成一个刷分按钮。</b>
     *
     * <p>断言的是「按之前 == 按之后」<b>逐字段比</b>,而不是写死的期望值:一个写死的数,
     * 在有人把断言并进分子时会被当成「过时的数字」直接改掉。
     *
     * <h2>⚠️ 上一版比的是 {@code percent},这一版比的是四个整数 —— 这是变严了不是变松了</h2>
     *
     * §7.2 把 {@code percent} 从这一域整个拿掉了(响应体里没有任何一个浮点字段)。
     * 而一个百分比本来就<b>盖得住错</b>:分子分母同时错一格,44% 还是 44%。
     * 四个整数逐个比,那种改动一定会红。
     */
    @Test
    @DisplayName("🔴 POST /api/v1/assertions 之后,GET /coverage/summary 那几个数一个都没动")
    void assertingDoesNotMoveTheCoverageNumbers() throws Exception {
        String before = summaryBody();

        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isCreated())
                // 端点自己也把这句话说出来:这次写入<b>没有</b>动覆盖度
                .andExpect(jsonPath("$.coverageChanged").value(false));

        String after = summaryBody();

        assertEquals((int) JsonPath.read(before, "$.nodeTouched"), (int) JsonPath.read(after, "$.nodeTouched"),
                "🔴 分子因为点了一次按钮而变了 —— 分子 = 有【计覆盖度标签】的节点数,而声明不是触达。"
                        + "决策记录 §5.2:「我已掌握」按钮是补丁不是解法");
        assertEquals((int) JsonPath.read(before, "$.nodeTotal"), (int) JsonPath.read(after, "$.nodeTotal"),
                "分母不该动 —— 把考点从分母里拿掉那是【归档】,是另一件事(R-49)");
        assertEquals((int) JsonPath.read(before, "$.nodeUntouched"), (int) JsonPath.read(after, "$.nodeUntouched"),
                "🔴 差集不该动 —— ASSERTED ⊆ 没碰过(U3.3 §2.4)。断言把节点【留在】差集里,"
                        + "它做的唯一一件事是让它不出现在「先补这几个」上");
        assertEquals((int) JsonPath.read(before, "$.archivedCount"), (int) JsonPath.read(after, "$.archivedCount"),
                "声明不是归档,归档计数不该动");

        // 🔴 上面四条比的是「没变」,而「没变」在两边都是 0 的时候也成立。
        //    所以再钉一次它们变的是什么:这就是设计契约上那三个数。
        assertEquals(18, (int) JsonPath.read(after, "$.nodeTotal"));
        assertEquals(8, (int) JsonPath.read(after, "$.nodeTouched"));
        assertEquals(10, (int) JsonPath.read(after, "$.nodeUntouched"));

        assertEquals(0, (int) JsonPath.read(before, "$.assertedCount"));
        assertEquals(1, (int) JsonPath.read(after, "$.assertedCount"),
                "唯一该变的就是这一格(docs/technical/INDEX.md §6.4:断言单列不并入)");

        // 🔴 §7.2:这一域的响应体里没有任何一个百分比。它一旦回来,上面那圈整数比对
        //    会全绿,而界面又有了一个能被这个按钮刷高的数
        assertFalse(after.contains("percent"), "概览里出现了 percent:" + after);
        assertFalse(after.contains("ratio"), "概览里出现了 ratio:" + after);
    }

    /**
     * 🔴 端点自己的响应里<b>只有一个字段</b>,而它说的是「什么都没动」。
     *
     * <p>上一版把整份概览和那个节点一起带回来,理由是「让界面在同一次交互里说清楚发生了什么」。
     * 那份好意有代价:概览一旦出现在写端点的响应里,它就有了<b>第二个来源</b> ——
     * 而两处出同一个数就一定会出两个数。现在界面拿到 {@code coverageChanged: false} 之后
     * 重新拉一次 {@code GET /coverage/summary},那一个来源永远只有一个。
     */
    @Test
    @DisplayName("🔴 响应体只有 coverageChanged 一个字段 —— 不带回概览,也不带回那个节点")
    void theResponseSaysOnlyThatNothingMoved() throws Exception {
        String body = mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coverageChanged").value(false))
                // 上一版那五个字段,一个都不许再回来
                .andExpect(jsonPath("$.node").doesNotExist())
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.asserted").doesNotExist())
                .andExpect(jsonPath("$.assertedAt").doesNotExist())
                .andExpect(jsonPath("$.assertedTotal").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // 🔴 逐个 doesNotExist 只挡得住点名的那几个。键集整体比一次,任何一个新字段
        //    (哪怕叫得再无害)都得先来改这一行,而改这一行要回答「界面为什么不能自己再拉一次」
        assertEquals(Set.of("coverageChanged"), keysOf(body), "响应体多了字段:" + body);

        // 库里那一行是真的写了 —— 否则上面那圈 doesNotExist 只证明了「这个端点什么都没做」
        assertNotNull(assertions.find(ApiTestAuth.USER_ID, BLANK_NODE));
    }

    // ———————————————————— 二、盲区榜排除 ————————————————————

    /**
     * 🔴 声明的<b>唯一</b>可见效果:那一行从「先补这几个」上消失。
     *
     * <p>「默认档排除已断言节点」与「{@code filter=asserted} 时反过来只列它们」是
     * <b>同一条规则的两次读法</b>({@code BlindspotFilter}),所以两边一起验 ——
     * 只验前一半的话,一个「把它从所有档里都过滤掉」的实现会全绿,
     * 而那样用户就再也找不回自己按过哪些了。
     */
    @Test
    @DisplayName("🔴 声明掌握之后,那个考点从 /coverage/blindspots 默认档上消失,并出现在 filter=asserted 那一档")
    void assertedNodeLeavesTheDefaultBoardAndShowsUpUnderItsOwnFilter() throws Exception {
        mockMvc.perform(get("/api/v1/coverage/blindspots"))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].nodeId").value(BLANK_NODE));

        assertMastery(BLANK_NODE);

        mockMvc.perform(get("/api/v1/coverage/blindspots"))
                .andExpect(status().isOk())
                // 🔴 榜真的短了一行,第二名顶上来。top(20)是【上限】不是配额,
                //    不会为了凑够 N 把一个碰过的考点塞进来
                .andExpect(jsonPath("$.items.length()").value(9))
                .andExpect(jsonPath("$.items[0].nodeId").value(SECOND_BLIND_NODE))
                .andExpect(jsonPath("$.items[*].nodeId",
                        Matchers.not(Matchers.hasItem(BLANK_NODE))));

        mockMvc.perform(get("/api/v1/coverage/blindspots").param("filter", "asserted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].nodeId").value(BLANK_NODE))
                // 它在这一档里也还是「碰过 0 次」—— 声明不是触达
                .andExpect(jsonPath("$.items[0].touchCount").value(0))
                .andExpect(jsonPath("$.items[0].lastTouchAt").doesNotExist());
    }

    @Test
    @DisplayName("取消之后它回到盲区榜首 —— 这个按钮的每一个效果都必须能撤回")
    void cancellingBringsItBack() throws Exception {
        assertMastery(BLANK_NODE);
        cancel(BLANK_NODE);

        mockMvc.perform(get("/api/v1/coverage/blindspots"))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].nodeId").value(BLANK_NODE));
        mockMvc.perform(get("/api/v1/coverage/summary"))
                .andExpect(jsonPath("$.assertedCount").value(0));
    }

    /**
     * 树上那一格得跟着翻面,否则用户没有任何地方能看到自己按过什么。
     *
     * <p>⚠️ 翻的是一个<b>开关</b>({@code asserted}),不是一个时刻。上一版树上带着
     * {@code assertedAt} —— 「你什么时候按的」跟着每一个叶子节点上线,一屏十八个时间戳,
     * 而它们合起来读就是一条学习轨迹。树要回答的是「这个开关开着吗」,一个 bool 就够。
     */
    @Test
    @DisplayName("🔴 树上那一格的 asserted 翻成 true,而 touchCount 一动不动 —— 声明不是一次触达")
    void theTreeFlipsTheSwitchWithoutCountingItAsATouch() throws Exception {
        // 定位用绝对下标,并且先把 code 断言一遍 —— 下标错了会当场红,而不是安静地验了别的节点
        mockMvc.perform(get("/api/v1/syllabus/tree"))
                .andExpect(jsonPath("$.groups[3].code").value("average-share"))
                .andExpect(jsonPath("$.groups[3].nodes[1].code").value(BLANK_NODE))
                .andExpect(jsonPath("$.groups[3].nodes[1].asserted").value(false))
                .andExpect(jsonPath("$.groups[3].nodes[1].touchCount").value(0));

        assertMastery(BLANK_NODE);

        mockMvc.perform(get("/api/v1/syllabus/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[3].nodes[1].code").value(BLANK_NODE))
                .andExpect(jsonPath("$.groups[3].nodes[1].asserted").value(true))
                // 🔴 碰过的次数还是 0 —— 断言不进分子,也不假装成一次触达
                .andExpect(jsonPath("$.groups[3].nodes[1].touchCount").value(0))
                // 🔴 树上没有「什么时候按的」,也没有第六态
                .andExpect(jsonPath("$.groups[3].nodes[1].assertedAt").doesNotExist())
                .andExpect(jsonPath("$.groups[3].nodes[1].state").doesNotExist())
                // 顶上那三个数同样没动
                .andExpect(jsonPath("$.summary.nodeTouched").value(8))
                .andExpect(jsonPath("$.summary.nodeUntouched").value(10));
    }

    // ———————————————————— 三、幂等 ————————————————————

    /**
     * 🔴 201 与 200 的区别是「新声明了没有」,不是「成功了没有」。
     *
     * <p>同一个考点声明第二次,服务端什么都没新建,这时候还回 201 是在说谎 ——
     * 而说谎在这里有具体后果:界面按 201 弹一次「已记下」的动效,
     * 用户会以为刚才那一下没生效,于是再点一次。
     */
    @Test
    @DisplayName("🔴 幂等:重复声明同一个考点不报错、不重复落行,第二次是 200 不是 201,而且不刷新时刻")
    void assertingTwiceIsIdempotentAndDoesNotRefreshTheTimestamp() throws Exception {
        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isCreated());

        Instant firstTime = assertions.find(ApiTestAuth.USER_ID, BLANK_NODE).assertedAt();
        assertNotNull(firstTime);
        int writesAfterFirst = assertions.writes();

        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isOk())          // 200,不是 201 —— 服务端什么都没新建
                .andExpect(jsonPath("$.coverageChanged").value(false));

        assertEquals(1, assertions.count(ApiTestAuth.USER_ID),
                "🔴 库里落了两行 —— 「已声明 N 个」从此开始说谎");
        assertEquals(firstTime, assertions.find(ApiTestAuth.USER_ID, BLANK_NODE).assertedAt(),
                "重复声明不该刷新时刻 —— 连点两下不该改写「你在 X 月 X 日说过你会了」这句话");

        // 🔴 上面那条「时刻没变」单独拿出来是<b>验不了控制器的</b>:AssertionStore#put 的契约
        //    本身就写着「已经声明过时返回原来那条」,所以就算控制器把那句
        //    if (existing == null) 删掉、无条件 put 一次,时刻照样不会变。
        //    真正只由控制器守着的是这一条 —— 它压根没再写第二次(契约 §11.2)。
        assertEquals(writesAfterFirst, assertions.writes(),
                "🔴 第二次 POST 又往存储层写了一次。上一版正是无条件 put(clock.instant()) —— "
                        + "换一个「后写覆盖先写」的存储实现,那句话就会被改写,而这里是唯一拦得住的地方");

        mockMvc.perform(get("/api/v1/coverage/summary"))
                .andExpect(jsonPath("$.assertedCount").value(1));
    }

    /**
     * 🔴 取消一个没声明过的考点 → <b>200,不是 404</b>(§9.5 / §十四 增量 6)。
     *
     * <p>断言是一次<b>集合成员关系</b>,不是一个有身份的资源:用户要的结果
     * (「这个考点不带『我已掌握』」)已经成立了,这时候回 404 是在报告一个<b>不存在的失败</b>。
     * <p>
     * 后果很具体:离线队列补传一条「取消」拿到 404,按队列纪律(4xx 从队列里删掉)
     * 被端当失败丢弃 —— 而它本来是成功的。<b>一半天然幂等的接口比不幂等更危险。</b>
     */
    @Test
    @DisplayName("🔴 幂等:取消一个从没声明过的考点同样回 200,不是 404")
    void cancellingSomethingNeverAssertedIsNotAnError() throws Exception {
        assertNull(assertions.find(ApiTestAuth.USER_ID, BLANK_NODE), "前提:它现在确实没被声明过");

        String body = mockMvc.perform(delete("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverageChanged").value(false))
                .andReturn().getResponse().getContentAsString();

        // 取消的响应与声明的<b>形状完全一样</b> —— 前端一个函数改个方法名就是取消
        assertEquals(Set.of("coverageChanged"), keysOf(body), body);

        assertEquals(0, assertions.count(ApiTestAuth.USER_ID));
        // 取消一次不存在的声明也不该动那三个数
        mockMvc.perform(get("/api/v1/coverage/summary"))
                .andExpect(jsonPath("$.nodeTouched").value(8))
                .andExpect(jsonPath("$.nodeUntouched").value(10))
                .andExpect(jsonPath("$.assertedCount").value(0));
    }

    @Test
    @DisplayName("取消之后再取消一次,还是 200 —— 用户要的结果早就成立了")
    void cancellingTwiceIsIdempotent() throws Exception {
        assertMastery(BLANK_NODE);
        cancel(BLANK_NODE);

        mockMvc.perform(delete("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverageChanged").value(false));

        assertEquals(0, assertions.count(ApiTestAuth.USER_ID));
    }

    /**
     * 取消之后再声明,是<b>一次新的声明</b>,时刻重新计。
     *
     * <p>这与「重复声明不刷新时刻」不矛盾,两者恰好是一对:刷不刷新由<b>用户有没有明确取消过</b>
     * 决定,而不是由「他按了几下」决定。取消 + 声明是两次明确的动作,连点两下是一次误触。
     */
    @Test
    @DisplayName("取消之后再声明一次:是一次新的声明,201,而且时刻重新计")
    void reAssertingAfterCancelStartsOver() throws Exception {
        assertMastery(BLANK_NODE);
        Instant firstTime = assertions.find(ApiTestAuth.USER_ID, BLANK_NODE).assertedAt();
        cancel(BLANK_NODE);

        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                // 🔴 201 而不是 200 —— 服务端这次确实新建了一行
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coverageChanged").value(false));

        Instant secondTime = assertions.find(ApiTestAuth.USER_ID, BLANK_NODE).assertedAt();
        assertNotNull(secondTime);
        assertFalse(secondTime.isBefore(firstTime),
                "重新声明拿的是一个更晚(或至少不更早)的时刻,不是把老那一行捡回来:"
                        + firstTime + " → " + secondTime);
        assertEquals(1, assertions.count(ApiTestAuth.USER_ID));
    }

    // ———————————————————— 三之二、树外与已归档,是两档 ————————————————————

    /**
     * 🔴 <b>「不存在」与「已归档」必须能被端分成两档</b>(§9.5 / §十)。
     *
     * <p>上一版两者都走 {@code 400 NODE_NOT_IN_SYLLABUS} —— 界面只能说一句「这个考点不见了」,
     * 而用户会以为<b>自己的记录被删了</b>。归档是产品做的一次整理,不是他的数据没了:
     * 归档那一档的下一步是「取消归档」,树外那一档的下一步是「刷新,它真的没了」。
     * 两句话不一样,所以两个码。
     */
    @Test
    @DisplayName("🔴 声明一个【已归档】的考点 → 409 NODE_ARCHIVED,与树外那个 404 分成两档")
    void archivedAndMissingNodesAreTwoDifferentRefusals() throws Exception {
        syllabus.archive(BLANK_NODE);

        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NODE_ARCHIVED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        // 同一次请求换成一个树里真的没有的 code —— 必须是另一档
        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"我自己想的考点\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));

        // 取消的方向同样分两档 —— 一半严一半松的接口比两边都松更难查
        mockMvc.perform(delete("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + BLANK_NODE + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NODE_ARCHIVED"));

        assertEquals(0, assertions.count(ApiTestAuth.USER_ID), "两次被拒都不该留下任何一行");
    }

    // ———————————————————— 四、body 只接受 nodeCode ————————————————————

    /**
     * 🔴 R-07 在这个端点上的形状:<b>没有一个能装下自由文本的位置</b>。
     *
     * <p>静默忽略比报错危险:双方都以为红线没被碰过。
     * 「我已掌握」是一个布尔事实,不是一条笔记 —— 给它配个 {@code note} 字段,
     * 那个字段一年后装的就是题干(R-01)。
     */
    @Test
    @DisplayName("🔴 body 只接受 nodeCode:多一个键就是 400,不是被静默忽略")
    void bodyAcceptsNothingButNodeCode() throws Exception {
        for (String body : new String[]{
                "{\"nodeCode\":\"" + BLANK_NODE + "\",\"note\":\"这题我在抖音看过\"}",
                "{\"nodeCode\":\"" + BLANK_NODE + "\",\"name\":\"我自己想的考点\"}",
                "{\"nodeCode\":\"" + BLANK_NODE + "\",\"reason\":\"因为我会了\"}"}) {

            mockMvc.perform(post("/api/v1/assertions")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));

            mockMvc.perform(delete("/api/v1/assertions")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        }
        assertEquals(0, assertions.count(ApiTestAuth.USER_ID), "被拒的请求不该留下任何一行");
    }

    /**
     * 🔴 第二道锁 —— <b>关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES} 之后照样进不来</b>。
     *
     * <p>上面那条走的是真实配置,而真实配置里那行开关一直开着,于是它只能证明
     * 「两道锁<b>至少有一道</b>在」,证明不了「有两道」。实测:把 {@link AssertionRequest}
     * 上的 {@code @JsonAnySetter} 拿掉,整个套件<b>一条都不红</b>。
     * <p>
     * 做法与 {@code TagApiTest#unknownFieldsAreRejectedEvenWithoutTheMapperFlag} 同一份 ——
     * R-07 必须在配置被人改掉之后依然成立。
     */
    @Test
    @DisplayName("🔴 第二道锁:关掉 FAIL_ON_UNKNOWN_PROPERTIES,自由文本照样进不了这个请求体")
    void unknownFieldsAreRejectedEvenWithoutTheMapperFlag() {
        JsonMapper lenient = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)   // 第一道锁,故意拆掉
                .build();

        for (String field : List.of("note", "name", "label", "reason", "text")) {
            String body = """
                    {"nodeCode":"growth-rate","%s":"某年某省考资料分析材料第一段……"}
                    """.formatted(field);

            Exception thrown = assertThrows(Exception.class,
                    () -> lenient.readValue(body, AssertionRequest.class),
                    "配置锁拆掉之后 " + field + " 就进 AssertionRequest 了 —— 只剩一行配置撑着");

            UnknownFieldException lock = null;
            for (Throwable t = thrown; t != null && t != t.getCause(); t = t.getCause()) {
                if (t instanceof UnknownFieldException ufe) {
                    lock = ufe;
                }
            }
            assertNotNull(lock, "拒是拒了,但不是 DTO 那道锁拒的(" + field + "):" + thrown);
        }
    }

    @Test
    @DisplayName("nodeCode 缺失或空白 → 400")
    void nodeCodeIsRequired() throws Exception {
        for (String body : new String[]{"{}", "{\"nodeCode\":\"\"}", "{\"nodeCode\":\"   \"}"}) {
            mockMvc.perform(post("/api/v1/assertions")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("🔴 R-07:nodeCode 不在骨架树里 → 404 NODE_NOT_FOUND,不猜最接近的考点(只能从树里选,不能新建)")
    void unknownNodeIsRejectedNotGuessed() throws Exception {
        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"增长率那个\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));

        assertEquals(0, assertions.count(ApiTestAuth.USER_ID));
    }

    /**
     * 🔴 报错里不回显整段原文。
     *
     * <p>请求体上的 {@code nodeCode} 有 {@code @Size(max = 64)} 兜着,所以超长的那个先被
     * 校验拦下;但这条断言守的是<b>无论走哪一支,那 300 字都不会原样出现在响应体和日志里</b>
     * (与 {@code ApiContractTest#rejectionMessagesDoNotEchoUnboundedUserInput} 同一条)。
     */
    @Test
    @DisplayName("🔴 拒绝的时候不回显用户送来的整段原文 —— 那可能就是一整道题")
    void rejectionDoesNotEchoTheWholeInput() throws Exception {
        String stem = "某市 2023 年全年实现地区生产总值 12345.6 亿元,比上年增长 5.4%".repeat(6);

        String body = mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + stem + "\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(stem), "整段原文回到了响应体里 —— 它同时也进了服务端日志");
    }

    // ———————————————————— 五、跨域 ————————————————————

    /**
     * {@code DELETE} 是逐条路径开的({@code ApiCorsConfig} 类注释)。
     *
     * <p>不开这一条的话,浏览器的预检会失败,而表现是<b>「取消」这个按钮在浏览器里静默失灵,
     * 服务端日志一条都看不到</b>。
     */
    @Test
    @DisplayName("CORS:DELETE /api/v1/assertions 放行,而全局白名单里照旧没有 DELETE")
    void corsOpensDeleteForAssertionsOnly() throws Exception {
        mockMvc.perform(options("/api/v1/assertions")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods",
                        Matchers.containsString("DELETE")));

        mockMvc.perform(options("/api/v1/coverage/summary")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- 夹具

    private void assertMastery(String nodeCode) throws Exception {
        mockMvc.perform(post("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + nodeCode + "\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    private void cancel(String nodeCode) throws Exception {
        mockMvc.perform(delete("/api/v1/assertions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"" + nodeCode + "\"}"))
                .andExpect(status().isOk());
    }

    /** 响应体顶层的键集 —— 用来断言「就这几个字段,不多不少」。 */
    private static Set<String> keysOf(String json) {
        Map<String, Object> parsed = JsonPath.read(json, "$");
        return Set.copyOf(parsed.keySet());
    }

    private String summaryBody() throws Exception {
        String body = mockMvc.perform(get("/api/v1/coverage/summary"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNotNull(body);
        return body;
    }

    /**
     * 与 {@code ApiContractTest.contractTouches} 同一份数据契约:8 个考点有记录,覆盖 44%。
     *
     * <p>全部挂在 {@link ApiTestAuth#USER_ID} 名下 —— 令牌里的人和夹具里的人必须是同一个,
     * 否则按用户过滤之后一条都读不到,而失败会以「覆盖率怎么是 0」的形式出现。
     */
    private static List<Touch> contractTouches() {
        Instant now = Instant.now();
        List<Touch> ts = new ArrayList<>();
        drill(ts, now, "growth-rate", "粉笔 · 资料分析系统班 L12", 12, 10, 0);
        drill(ts, now, "share-calc", "华图 · 资料速算网课", 9, 8, 1);
        drill(ts, now, "feature-number", "自己刷题 · 2023 国考真题", 7, 6, 3);
        drill(ts, now, "growth-amount", "自己刷题 · 2023 国考真题", 8, 4, 2);
        drill(ts, now, "truncate-divide", "B站 · 资料分析技巧", 6, 2, 4);
        drill(ts, now, "base-value", "中公 · 资料分析专项", 5, 4, 32);
        drill(ts, now, "interval-growth", "中公 · 资料分析专项", 3, 2, 33);
        ts.add(new Touch("t-share-change", ApiTestAuth.USER_ID, "share-change",
                "粉笔 · 资料分析系统班 L12", TouchKind.VOICE, now.minus(Duration.ofDays(5)), null, null));
        return ts;
    }

    private static void drill(List<Touch> ts, Instant now, String node, String source,
                              int practiced, int correct, int daysAgo) {
        ts.add(new Touch("t-" + node, ApiTestAuth.USER_ID, node, source, TouchKind.DRILL,
                now.minus(Duration.ofDays(daysAgo)), new Touch.Drill(practiced, correct), null));
    }

    /**
     * 行为层的只读桩 —— 这三个端点一条记录都不该写。
     *
     * <p>把写侧实现成「一调用就炸」本身就是一条断言:哪天有人让「我已掌握」<b>顺手记一条记录</b>
     * (那是让覆盖率上升的另一条路,而且更隐蔽),这个测试会当场红。
     */
    static final class InMemoryTouchStore implements TouchStore {

        private final List<Touch> touches = new ArrayList<>();

        void reset(List<Touch> seed) {
            touches.clear();
            touches.addAll(seed);
        }

        @Override
        public List<Touch> findAll(long userId) {
            return touches.stream()
                    .filter(t -> t.userId() == userId)
                    .sorted(Comparator.comparing(Touch::occurredAt))
                    .toList();
        }

        @Override
        public List<Touch> findAllAcrossUsers() {
            return touches.stream().sorted(Comparator.comparing(Touch::occurredAt)).toList();
        }

        @Override
        public int countByNodeAcrossUsers(String nodeCode) {
            return (int) touches.stream().filter(t -> t.nodeCode().equals(nodeCode)).count();
        }

        @Override
        public int count(long userId) {
            return (int) touches.stream().filter(t -> t.userId() == userId).count();
        }

        @Override
        public Touch findByClientToken(long userId, String clientToken) {
            throw new AssertionError("「我已掌握」不写记录,不该去查去重键");
        }

        @Override
        public Touch append(Touch touch) {
            throw new AssertionError("🔴 「我已掌握」写了一条记录 —— 那是让覆盖率上升的另一条路,"
                    + "而它比直接改分子更难被发现(决策记录 §5.2:补丁不是解法)");
        }

        @Override
        public Touch delete(long userId, String id) {
            throw new AssertionError("「我已掌握」不删记录");
        }

        @Override
        public int reassign(String fromNodeCode, String toNodeCode) {
            throw new AssertionError("「我已掌握」不改挂记录");
        }
    }

    /**
     * 「我已掌握」的存储 —— 在真的内存实现外面套一层<b>写入计数</b>。
     *
     * <h2>🔴 为什么要多这一层</h2>
     *
     * 「重复声明不刷新时刻」这条,{@link AssertionStore#put} 的契约<b>已经在存储层保证了</b>
     * (已经声明过时返回原来那条,不覆盖)。于是只断言「时刻没变」的话,就算控制器把那句
     * {@code if (existing == null)} 删掉、无条件 put 一次,断言<b>照样是绿的</b> ——
     * 而一条永远为真的断言,和一条被注释掉的断言,外观是一样的。
     * <p>
     * 数一下写了几次,才验得到只由控制器守着的那一半:第二次 POST <b>压根没往下写</b>。
     */
    static final class CountingAssertionStore implements AssertionStore {

        private final InMemoryAssertionStore delegate = new InMemoryAssertionStore();
        private int writes;

        void reset() {
            delegate.reset();
            writes = 0;
        }

        /** {@link #put} 被调用了几次 —— 数的是<b>调用</b>,不是落了几行。 */
        int writes() {
            return writes;
        }

        @Override
        public List<UserAssertion> findAll(long userId) {
            return delegate.findAll(userId);
        }

        @Override
        public List<UserAssertion> findAllAcrossUsers() {
            return delegate.findAllAcrossUsers();
        }

        @Override
        public UserAssertion find(long userId, String nodeCode) {
            return delegate.find(userId, nodeCode);
        }

        @Override
        public UserAssertion put(UserAssertion assertion) {
            writes++;
            return delegate.put(assertion);
        }

        @Override
        public boolean remove(long userId, String nodeCode) {
            return delegate.remove(userId, nodeCode);
        }

        @Override
        public int count(long userId) {
            return delegate.count(userId);
        }
    }

    /**
     * 一棵可以在测试里就地归档一个考点的树。
     *
     * <p>{@code SyllabusLoader.loadDefault()} 给的是不可变的 record,归不了档;
     * 起真的 {@code FileSyllabusStore} 又会去写 {@code ~/.kaodian/} —— 那是另一条线的事。
     * 与 {@code ExportApiTest.SwappableSyllabus} 是同一份写法。
     */
    static final class SwappableSyllabus implements SyllabusSource {

        private volatile Syllabus tree = SyllabusLoader.loadDefault();

        @Override
        public Syllabus current() {
            return tree;
        }

        void reset() {
            tree = SyllabusLoader.loadDefault();
        }

        /** 把一个考点标成已归档。记录一条不动 —— 归档的语义就是这个。 */
        void archive(String nodeCode) {
            tree = new Syllabus(tree.subject(), tree.groups().stream()
                    .map(g -> new Syllabus.Group(g.code(), g.name(), g.nodes().stream()
                            .map(n -> n.code().equals(nodeCode)
                                    ? new Syllabus.Node(n.code(), n.name(), n.recent5yCount(), true)
                                    : n)
                            .toList()))
                    .toList());
        }
    }

    @TestConfiguration
    static class Fixtures {

        @Bean
        SwappableSyllabus syllabus() {
            return new SwappableSyllabus();
        }

        @Bean
        InMemoryTouchStore touchStore() {
            return new InMemoryTouchStore();
        }

        @Bean
        RecordTagStore recordTagStore() {
            return new InMemoryRecordTagStore();
        }

        @Bean
        CountingAssertionStore assertionStore() {
            return new CountingAssertionStore();
        }

        @Bean
        CoverageReader coverageReader(SyllabusSource syllabus, TouchStore store,
                                      RecordTagStore tagStore, AssertionStore assertionStore,
                                      CoverageService coverage, Clock clock) {
            return new CoverageReader(syllabus, store, tagStore, assertionStore, coverage, clock);
        }
    }
}
