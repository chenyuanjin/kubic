package com.kaodian.server.api;

import com.kaodian.server.api.insight.CoverageController;
import com.kaodian.server.api.record.RecordController;
import com.kaodian.server.api.syllabus.SyllabusAdminController;
import com.kaodian.server.api.syllabus.SyllabusController;
import com.kaodian.server.api.insight.TimelineController;
import com.kaodian.server.config.DomainBeans;
import com.kaodian.server.coverage.CoverageReader;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.kaodian.server.api.dto.syllabus.CreateNodeRequest;
import com.kaodian.server.collect.CaptureService;
import com.kaodian.server.collect.FileTouchStore;
import com.kaodian.server.collect.InMemoryRecordTagStore;
import com.kaodian.server.collect.AssertionStore;
import com.kaodian.server.collect.InMemoryAssertionStore;
import com.kaodian.server.collect.RecordTagStore;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.recognize.VisionTagger;
import com.kaodian.server.syllabus.FileSyllabusStore;
import com.kaodian.server.syllabus.NodeRecordLedger;
import com.kaodian.server.syllabus.SyllabusSource;
import com.kaodian.server.syllabus.SyllabusStore;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 考点管理的接口契约 —— <b>骨架层可写之后,红线还在不在。</b>
 *
 * <p>起点永远是那份设计契约:<b>18 个考点 / 8 个有记录 / 覆盖 44%</b>。
 * 每个测试先动一下树,再看那个百分比动没动、动得对不对 ——
 * 因为覆盖率是这个产品唯一的那个数,而考点管理是唯一能从<b>分母那一侧</b>动它的东西。
 *
 * <h2>为什么用真的 {@code FileSyllabusStore} 而不是内存假实现</h2>
 *
 * 因为规则就长在 store 上(code 谁生成、有记录能不能删、顺序算不算完整排列)。
 * 换成一个内存假实现,等于把要验的规则再写一遍,然后验它自己 —— 那什么也没验。
 * 每个方法跑完销毁上下文({@link DirtiesContext}),下一个方法从种子重新播一次。
 */
@WebMvcTest(controllers = {
        SyllabusAdminController.class,
        SyllabusController.class,
        CoverageController.class,
        TimelineController.class,
        RecordController.class})
@Import(DomainBeans.class)     // web 切片不扫 @Configuration,领域装配要显式带进来
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SyllabusAdminApiTest {

    /** 类级临时目录。真正保证测试互不影响的是 {@link #freshData()} + {@link DirtiesContext}。 */
    @TempDir
    static Path dataDir;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void freshData() throws IOException {
        Files.deleteIfExists(dataDir.resolve("syllabus.json"));
        Files.deleteIfExists(dataDir.resolve("touches.json"));
    }

    // ---------------------------------------------------------------- 新增

    @Test
    @DisplayName("🔴 新增考点:code 由服务端生成,不是中文名;覆盖率的分母当场 +1")
    void createNodeGeneratesTheCodeAndMovesTheDenominator() throws Exception {
        String body = mockMvc.perform(json(post("/api/syllabus/nodes"), """
                        {"groupCode":"growth","name":"复合增长率","recent5yCount":2}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.node.name").value("复合增长率"))
                .andExpect(jsonPath("$.node.groupCode").value("growth"))
                .andExpect(jsonPath("$.node.recent5yCount").value(2))
                .andExpect(jsonPath("$.node.archived").value(false))
                .andExpect(jsonPath("$.node.recordCount").value(0))
                // 🔴 分母 +1:18 → 19,覆盖率 8/19 = 42%
                .andExpect(jsonPath("$.summary.total").value(19))
                .andExpect(jsonPath("$.summary.covered").value(8))
                .andExpect(jsonPath("$.summary.percent").value(42))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"code\" : \"n-") || body.contains("\"code\":\"n-"),
                "服务端生成的 code 有固定前缀,一眼可辨:" + body);
        assertFalse(body.contains("\"code\" : \"复合增长率\""), "🔴 不许拿中文名当 code");

        // 树上也真的多了一个 —— 不是只在响应里多了一个
        mockMvc.perform(get("/api/syllabus/tree"))
                .andExpect(jsonPath("$.groups[0].nodes.length()").value(8))
                .andExpect(jsonPath("$.summary.total").value(19));
    }

    @Test
    @DisplayName("🔴 客户端指定 code 被拒 —— 接口上根本没有这个字段")
    void clientCannotChooseTheCode() throws Exception {
        for (String field : List.of("code", "nodeCode", "id")) {
            mockMvc.perform(json(post("/api/syllabus/nodes"), """
                            {"groupCode":"growth","name":"复合增长率","recent5yCount":2,"%s":"my-own-code"}
                            """.formatted(field)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        }
        mockMvc.perform(get("/api/coverage/summary")).andExpect(jsonPath("$.total").value(18));
    }

    @Test
    @DisplayName("🔴 不做第四层:请求体里没有指向另一个考点的位置,写了也进不来")
    void thereIsNoFourthLevelThroughTheApi() throws Exception {
        assertEquals(List.of("groupCode", "name", "recent5yCount"),
                Arrays.stream(CreateNodeRequest.class.getRecordComponents())
                        .map(RecordComponent::getName).toList(),
                "父级只能是题型 —— 加字段前先回去看 01 §2.5");

        for (String field : List.of("parentNodeCode", "parentCode", "children", "subNodes", "nodes")) {
            mockMvc.perform(json(post("/api/syllabus/nodes"), """
                            {"groupCode":"growth","name":"更细的一层","recent5yCount":1,"%s":"growth-rate"}
                            """.formatted(field)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        }
    }

    // ---------------------------------------------------------------- 重命名

    @Test
    @DisplayName("🔴 重命名只改 name:code 不变、记录不丢、覆盖率逐字不变")
    void renameKeepsCodeRecordsAndPercent() throws Exception {
        mockMvc.perform(json(post("/api/syllabus/nodes/growth-rate/rename"), """
                        {"name":"增长率(我自己的说法)"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.code").value("growth-rate"))     // 🔴 code 一个字符没动
                .andExpect(jsonPath("$.node.name").value("增长率(我自己的说法)"))
                .andExpect(jsonPath("$.node.recordCount").value(1))
                // 🔴 记录挂 code 不挂名字,所以这三个数必须逐字不变
                .andExpect(jsonPath("$.summary.total").value(18))
                .andExpect(jsonPath("$.summary.covered").value(8))
                .andExpect(jsonPath("$.summary.percent").value(44));

        mockMvc.perform(get("/api/syllabus/nodes/growth-rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("增长率(我自己的说法)"))
                .andExpect(jsonPath("$.touchCount").value(1))
                .andExpect(jsonPath("$.practiced").value(12))
                .andExpect(jsonPath("$.state").value("STABLE"));

        // 记录列表上那条老记录也跟着显示新名字 —— 它本来就是按 code 反查的。
        // 逐条的记录读 /api/records(§6.2);/api/timeline 现在只出按天/周的格子(§6.4)
        mockMvc.perform(get("/api/records"))
                .andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.items[0].nodeCode").value("growth-rate"))
                .andExpect(jsonPath("$.items[0].nodeName").value("增长率(我自己的说法)"));
    }

    @Test
    @DisplayName("重命名一个树里没有的考点 → 404,不新建")
    void renamingAnUnknownNodeIs404() throws Exception {
        mockMvc.perform(json(post("/api/syllabus/nodes/我自己想的考点/rename"), """
                        {"name":"随便"}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));

        mockMvc.perform(get("/api/coverage/summary")).andExpect(jsonPath("$.total").value(18));
    }

    // ---------------------------------------------------------------- 🔴 删除守则

    @Test
    @DisplayName("🔴 删除有记录的考点必须失败:409,说出有几条,并给出两条出路")
    void deletingANodeWithRecordsIsRefused() throws Exception {
        mockMvc.perform(post("/api/syllabus/nodes/growth-rate/delete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NODE_HAS_RECORDS"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("1 条记录")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("归档")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        // 被拒之后:考点还在、记录还在、那个数一点没动
        mockMvc.perform(get("/api/syllabus/nodes/growth-rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.touchCount").value(1));
        mockMvc.perform(get("/api/coverage/summary"))
                .andExpect(jsonPath("$.total").value(18))
                .andExpect(jsonPath("$.covered").value(8))
                .andExpect(jsonPath("$.percent").value(44));
        mockMvc.perform(get("/api/records")).andExpect(jsonPath("$.total").value(8));
    }

    @Test
    @DisplayName("🔴 出路一:先把记录搬到别的考点,再删。记录总数不变,时间戳不重置")
    void moveRecordsThenDelete() throws Exception {
        mockMvc.perform(get("/api/records"))
                .andExpect(jsonPath("$.items[0].nodeCode").value("growth-rate"))
                .andExpect(jsonPath("$.items[0].practiced").value(12));

        mockMvc.perform(json(post("/api/syllabus/nodes/growth-rate/records/move"), """
                        {"toNodeCode":"average-calc"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromNodeCode").value("growth-rate"))
                .andExpect(jsonPath("$.toNodeCode").value("average-calc"))
                .andExpect(jsonPath("$.movedCount").value(1))
                // 搬家不改总数:还是 8 个考点有记录、还是 44%
                .andExpect(jsonPath("$.summary.total").value(18))
                .andExpect(jsonPath("$.summary.covered").value(8))
                .andExpect(jsonPath("$.summary.percent").value(44));

        mockMvc.perform(get("/api/records"))
                .andExpect(jsonPath("$.total").value(8))                       // 🔴 一条都没丢
                .andExpect(jsonPath("$.items[0].nodeCode").value("average-calc"))
                .andExpect(jsonPath("$.items[0].practiced").value(12));

        mockMvc.perform(post("/api/syllabus/nodes/growth-rate/delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("growth-rate"))
                .andExpect(jsonPath("$.summary.total").value(17))               // 分母 −1
                .andExpect(jsonPath("$.summary.covered").value(8))              // 分子不动
                .andExpect(jsonPath("$.summary.percent").value(47));

        mockMvc.perform(get("/api/syllabus/nodes/growth-rate")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("记录不许搬进不存在的考点,也不许原地搬")
    void recordsCannotBeMovedIntoNowhere() throws Exception {
        mockMvc.perform(json(post("/api/syllabus/nodes/growth-rate/records/move"), """
                        {"toNodeCode":"我自己想的考点"}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));

        mockMvc.perform(json(post("/api/syllabus/nodes/growth-rate/records/move"), """
                        {"toNodeCode":"growth-rate"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_NODE"));

        mockMvc.perform(get("/api/records")).andExpect(jsonPath("$.total").value(8));
    }

    /**
     * 归档清单刚把一个考点连名字带记录条数列出来,搬记录过去却被告知「骨架树里没有这个考点」——
     * 那是两个端点在同一次操作里互相打脸,而且把用户支到「刷新一下」这条死路上。
     *
     * <p>{@code SyllabusEditException.Reason} 的存在理由就是这一句:
     * 「每一条在界面上该说的下一步都不一样,所以不能合并」。目标已归档的下一步是<b>取消归档</b>,
     * 不是刷新 —— 所以它必须有自己的码,而且是 409 不是 404。
     */
    @Test
    @DisplayName("🔴 记录搬进【已归档】的考点:409 NODE_ARCHIVED,不是 404 —— 归档清单里明明还看得见它")
    void movingRecordsIntoAnArchivedNodeSaysSoInsteadOfPretendingItIsGone() throws Exception {
        mockMvc.perform(post("/api/syllabus/nodes/average-calc/archive")).andExpect(status().isOk());

        // 同一个 code,一个端点说「在,还挂着 0 条记录」
        mockMvc.perform(get("/api/syllabus/archived"))
                .andExpect(jsonPath("$.items[0].code").value("average-calc"))
                .andExpect(jsonPath("$.items[0].name").value("平均数计算"));

        // 另一个端点必须别说「不存在」
        mockMvc.perform(json(post("/api/syllabus/nodes/growth-rate/records/move"), """
                        {"toNodeCode":"average-calc"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NODE_ARCHIVED"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("已经归档")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("unarchive")));

        // 树里真的不存在的 code 才是 404 —— 两者不能是同一个答复
        mockMvc.perform(json(post("/api/syllabus/nodes/growth-rate/records/move"), """
                        {"toNodeCode":"我自己想的考点"}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));

        // 两次都被拒,记录一条没动
        mockMvc.perform(get("/api/records"))
                .andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.items[0].nodeCode").value("growth-rate"));
    }

    @Test
    @DisplayName("🔴 出路二:归档 —— 退出差集,但历史一条不少,还能接回来")
    void archiveRetiresTheNodeButKeepsHistory() throws Exception {
        mockMvc.perform(post("/api/syllabus/nodes/growth-rate/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.archived").value(true))
                .andExpect(jsonPath("$.node.recordCount").value(1))
                .andExpect(jsonPath("$.summary.total").value(17))       // 分母 −1
                .andExpect(jsonPath("$.summary.covered").value(7))      // 分子也 −1,比值仍然诚实
                .andExpect(jsonPath("$.summary.percent").value(41));

        // 退出了树
        mockMvc.perform(get("/api/syllabus/tree"))
                .andExpect(jsonPath("$.groups[0].nodes.length()").value(6));
        mockMvc.perform(get("/api/syllabus/nodes/growth-rate")).andExpect(status().isNotFound());

        // 但历史还在,而且还有名字 —— 归档不是「这段历史不存在了」
        mockMvc.perform(get("/api/records"))
                .andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.items[0].nodeCode").value("growth-rate"))
                .andExpect(jsonPath("$.items[0].nodeName").value("增长率计算"));

        // 看得见、找得回
        mockMvc.perform(get("/api/syllabus/archived"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].code").value("growth-rate"))
                .andExpect(jsonPath("$.items[0].recordCount").value(1));

        // 🔴 归档之后挂不上新记录 —— 否则「归档」是一句空话
        mockMvc.perform(json(post("/api/records"), """
                        {"kind":"MANUAL","sourceName":"粉笔 · 资料分析系统班 L12","nodeCode":"growth-rate"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NODE_NOT_IN_SYLLABUS"));

        mockMvc.perform(post("/api/syllabus/nodes/growth-rate/unarchive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.archived").value(false))
                .andExpect(jsonPath("$.summary.total").value(18))
                .andExpect(jsonPath("$.summary.covered").value(8))
                .andExpect(jsonPath("$.summary.percent").value(44));
    }

    // ---------------------------------------------------------------- 移动 / 频次

    @Test
    @DisplayName("移动考点到另一个题型:code 不变,记录不动,只换了归属")
    void moveNodeKeepsCodeAndRecords() throws Exception {
        mockMvc.perform(json(post("/api/syllabus/nodes/growth-rate/move"), """
                        {"groupCode":"fast-math"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.code").value("growth-rate"))
                .andExpect(jsonPath("$.node.groupCode").value("fast-math"))
                .andExpect(jsonPath("$.node.groupName").value("速算技巧"))
                .andExpect(jsonPath("$.node.recordCount").value(1))
                .andExpect(jsonPath("$.summary.total").value(18))
                .andExpect(jsonPath("$.summary.percent").value(44));

        mockMvc.perform(get("/api/syllabus/nodes/growth-rate"))
                .andExpect(jsonPath("$.groupName").value("速算技巧"))
                .andExpect(jsonPath("$.touchCount").value(1));
    }

    @Test
    @DisplayName("改近五年频次会改「先补这几个」的名次 —— 它是排序权重之一")
    void frequencyChangesTheBlindSpotRanking() throws Exception {
        mockMvc.perform(get("/api/coverage/blindspots").param("top", "3"))
                .andExpect(jsonPath("$.items[1].name").value("平均数计算"));

        mockMvc.perform(json(post("/api/syllabus/nodes/average-calc/frequency"), """
                        {"recent5yCount":1}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.recent5yCount").value(1))
                .andExpect(jsonPath("$.summary.percent").value(44));    // 频次不影响覆盖率

        mockMvc.perform(get("/api/coverage/blindspots").param("top", "3"))
                .andExpect(jsonPath("$.items[1].name").value("截位直除"));

        mockMvc.perform(json(post("/api/syllabus/nodes/average-calc/frequency"), """
                        {"recent5yCount":-1}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------------------------------------------------------------- 题型

    @Test
    @DisplayName("题型:新增是空的;非空不许删;空了才能删")
    void groupLifecycle() throws Exception {
        String created = mockMvc.perform(json(post("/api/syllabus/groups"), """
                        {"name":"自己归纳的一类"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.group.name").value("自己归纳的一类"))
                .andExpect(jsonPath("$.group.nodeCount").value(0))
                .andExpect(jsonPath("$.summary.total").value(18))   // 空题型不影响分母
                .andReturn().getResponse().getContentAsString();
        assertTrue(created.contains("g-"), "题型 code 同样由服务端生成:" + created);

        mockMvc.perform(post("/api/syllabus/groups/effect/delete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_EMPTY"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("2 个考点")));

        // effect 下面两个考点都没有记录,可以逐个删掉
        mockMvc.perform(post("/api/syllabus/nodes/contribution-rate/delete")).andExpect(status().isOk());
        mockMvc.perform(post("/api/syllabus/nodes/pull-growth/delete")).andExpect(status().isOk());
        mockMvc.perform(post("/api/syllabus/groups/effect/delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total").value(16))
                .andExpect(jsonPath("$.summary.whollyEmptyGroups").value(1));   // 原来是 2

        mockMvc.perform(get("/api/syllabus/tree")).andExpect(jsonPath("$.groups.length()").value(5));
    }

    @Test
    @DisplayName("题型改名同样只改 name")
    void renameGroupKeepsCode() throws Exception {
        mockMvc.perform(json(post("/api/syllabus/groups/effect/rename"), """
                        {"name":"效应与拉动"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.code").value("effect"))
                .andExpect(jsonPath("$.group.name").value("效应与拉动"));

        mockMvc.perform(get("/api/syllabus/tree"))
                .andExpect(jsonPath("$.groups[2].code").value("effect"))
                .andExpect(jsonPath("$.groups[2].name").value("效应与拉动"))
                .andExpect(jsonPath("$.groups[2].whollyEmpty").value(true));
    }

    // ---------------------------------------------------------------- 顺序

    @Test
    @DisplayName("🔴 调整树序会改变「先补这几个」里并列项的名次 —— 所以它是产品功能,不是排版")
    void reorderingGroupsChangesTheTieBreak() throws Exception {
        mockMvc.perform(get("/api/coverage/blindspots").param("top", "5"))
                .andExpect(jsonPath("$.items[3].name").value("现期量计算"))
                .andExpect(jsonPath("$.items[4].name").value("倍数计算"));

        mockMvc.perform(json(post("/api/syllabus/groups/order"), """
                        {"groupCodes":["multiple","growth","effect","average-share","fast-math"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].code").value("multiple"))
                .andExpect(jsonPath("$.summary.percent").value(44));

        mockMvc.perform(get("/api/coverage/blindspots").param("top", "5"))
                .andExpect(jsonPath("$.items[3].name").value("倍数计算"))
                .andExpect(jsonPath("$.items[4].name").value("现期量计算"));
    }

    @Test
    @DisplayName("🔴 顺序必须是完整排列:少一个就整体拒绝,不悄悄补")
    void partialOrderIsRejected() throws Exception {
        mockMvc.perform(json(post("/api/syllabus/groups/order"), """
                        {"groupCodes":["multiple","growth"]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_A_PERMUTATION"));

        mockMvc.perform(json(post("/api/syllabus/groups/order"), """
                        {"groupCodes":[]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/syllabus/tree")).andExpect(jsonPath("$.groups[0].code").value("growth"));
    }

    @Test
    @DisplayName("组内调序:考点在树上的先后跟着变")
    void reorderingNodesWithinAGroup() throws Exception {
        mockMvc.perform(json(post("/api/syllabus/groups/multiple/nodes/order"), """
                        {"nodeCodes":["yoy-mom","multiple-change","multiple-calc"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[1].nodes[0].code").value("yoy-mom"))
                .andExpect(jsonPath("$.groups[1].nodes[2].code").value("multiple-calc"));
    }

    // ---------------------------------------------------------------- 🔴 名字不是放内容的地方

    @Test
    @DisplayName("🔴 考点名有长度上限、不许带换行 —— 挡住把一段题干贴进「考点名」")
    void namesCannotCarryContent() throws Exception {
        mockMvc.perform(json(post("/api/syllabus/nodes"), """
                        {"groupCode":"growth","name":"%s","recent5yCount":1}
                        """.formatted("题".repeat(200))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("name")));

        mockMvc.perform(json(post("/api/syllabus/nodes"), """
                        {"groupCode":"growth","name":"增长率\\n2023 年全国粮食总产量为 13908 亿斤","recent5yCount":1}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NAME"));

        mockMvc.perform(get("/api/coverage/summary")).andExpect(jsonPath("$.total").value(18));
    }

    // ---------------------------------------------------------------- 🔴 名字必须唯一

    /**
     * 🔴 唯一性的范围是<b>整棵树</b>,不是「同题型内唯一」——— 这是本次规则的关键分歧点。
     *
     * <p>前端是按<b>名字</b>从命令面板挑考点的,面板上只显示名字与状态、<b>不显示题型</b>。
     * 两个同名考点在用户眼里就是同一个,记录会被劈到两个语义相同的 code 上:
     * 覆盖率的分子被稀释,「整块空白」跟着失真 —— 而覆盖率是这个产品唯一的那个数(01 §2.2)。
     */
    @Test
    @DisplayName("🔴 考点重名 → 409 NAME_TAKEN:同题型如此,【跨题型】同样如此")
    void duplicateNodeNameIsRefusedAcrossTheWholeTree() throws Exception {
        mockMvc.perform(json(post("/api/syllabus/nodes"), """
                        {"groupCode":"growth","name":"增长量计算","recent5yCount":0}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NAME_TAKEN"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("增长类")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        // 🔴 换个题型再来一次 —— 这一条如果放行,树上就会出现两个渲染完全相同的「增长量计算」
        mockMvc.perform(json(post("/api/syllabus/nodes"), """
                        {"groupCode":"effect","name":"增长量计算","recent5yCount":0}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NAME_TAKEN"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("增长类")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("整棵树")));

        // 改名撞上别人同样是 409
        mockMvc.perform(json(post("/api/syllabus/nodes/growth-rate/rename"), """
                        {"name":"基期量计算"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NAME_TAKEN"));

        // 🔴 三次都被拒之后,覆盖率一个数都没变
        mockMvc.perform(get("/api/coverage/summary"))
                .andExpect(jsonPath("$.total").value(18))
                .andExpect(jsonPath("$.covered").value(8))
                .andExpect(jsonPath("$.percent").value(44));
        mockMvc.perform(get("/api/syllabus/nodes/growth-rate"))
                .andExpect(jsonPath("$.name").value("增长率计算"));
    }

    @Test
    @DisplayName("🔴 前后空格 / 内部多空格 / 全角半角 / 大小写,都不构成区别 → 409")
    void namesThatOnlyLookDifferentAreStillTaken() throws Exception {
        // 前后空格不构成区别
        mockMvc.perform(json(post("/api/syllabus/nodes"), """
                        {"groupCode":"growth","name":"  增长量计算  ","recent5yCount":0}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NAME_TAKEN"));

        // 内部连续空白折叠成一个。🔴 折叠的是【多个空格】,不是删掉空格 ——
        // 「增长量计算」与「增长量 计算」仍然是两个不同的名字,那个空格是看得见的
        mockMvc.perform(json(post("/api/syllabus/nodes"), """
                        {"groupCode":"growth","name":"增长量 速算","recent5yCount":1}
                        """))
                .andExpect(status().isCreated());
        for (String variant : List.of("增长量   速算", " 增长量  速算 ")) {
            mockMvc.perform(json(post("/api/syllabus/nodes"), """
                            {"groupCode":"effect","name":"%s","recent5yCount":0}
                            """.formatted(variant)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        }

        mockMvc.perform(json(post("/api/syllabus/nodes"), """
                        {"groupCode":"fast-math","name":"GDP 速算","recent5yCount":1}
                        """))
                .andExpect(status().isCreated());

        // 全角 ＧＤＰ 与半角 GDP、大小写 gdp,渲染出来分得出,挑的时候分不出
        for (String variant : List.of("ＧＤＰ 速算", "gdp 速算", "Gdp 速算")) {
            mockMvc.perform(json(post("/api/syllabus/nodes"), """
                            {"groupCode":"growth","name":"%s","recent5yCount":0}
                            """.formatted(variant)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        }

        // 只成功了两次:「增长量 速算」与「GDP 速算」;被拒的五次一个都没落到树上
        mockMvc.perform(get("/api/coverage/summary")).andExpect(jsonPath("$.total").value(20));
    }

    /**
     * 🔴 这是最容易让人困惑的一种冲突:占着名字的考点<b>已归档</b>,用户在 {@code /tree} 上看不见它。
     *
     * <p>所以这条 409 必须显式说明「被一个已归档的考点占着」,并给出出路
     * (给那个归档考点改名,或先取消归档)。只说「名字重复」的话,用户看到的是
     * 「这个名字明明没人用」—— 下一步只会是换个近义词硬凑,而那正是这条规则想防的事。
     */
    @Test
    @DisplayName("🔴 与【已归档】考点重名 → 409,而且报错里必须出现「归档」两个字")
    void aNameHeldByAnArchivedNodeSaysSo() throws Exception {
        mockMvc.perform(post("/api/syllabus/nodes/growth-amount/archive")).andExpect(status().isOk());
        mockMvc.perform(get("/api/syllabus/tree"))
                .andExpect(jsonPath("$.groups[0].nodes.length()").value(6));   // 树上确实看不见了

        mockMvc.perform(json(post("/api/syllabus/nodes"), """
                        {"groupCode":"effect","name":"增长量计算","recent5yCount":0}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NAME_TAKEN"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("归档")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("unarchive")));

        // 🔴 名字在归档期间没被让出去,所以取消归档不需要再查一次重名,也不会失败
        mockMvc.perform(post("/api/syllabus/nodes/growth-amount/unarchive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.name").value("增长量计算"))
                .andExpect(jsonPath("$.summary.total").value(18));
    }

    @Test
    @DisplayName("重命名成自己原来的名字 → 200,不是 409")
    void renamingToItsOwnNameIsFine() throws Exception {
        mockMvc.perform(json(post("/api/syllabus/nodes/growth-rate/rename"), """
                        {"name":"增长率计算"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.code").value("growth-rate"))
                .andExpect(jsonPath("$.node.name").value("增长率计算"))
                .andExpect(jsonPath("$.summary.percent").value(44));

        // 只差前后空格的写法同样是自己
        mockMvc.perform(json(post("/api/syllabus/nodes/growth-rate/rename"), """
                        {"name":"  增长率计算  "}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.name").value("增长率计算"));

        mockMvc.perform(json(post("/api/syllabus/groups/growth/rename"), """
                        {"name":"增长类"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.code").value("growth"));
    }

    @Test
    @DisplayName("🔴 题型重名 → 409 NAME_TAKEN")
    void duplicateGroupNameIsRefused() throws Exception {
        mockMvc.perform(json(post("/api/syllabus/groups"), """
                        {"name":"效应类"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NAME_TAKEN"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("效应类")));

        mockMvc.perform(json(post("/api/syllabus/groups/growth/rename"), """
                        {"name":" 速算技巧 "}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NAME_TAKEN"));

        mockMvc.perform(get("/api/syllabus/tree"))
                .andExpect(jsonPath("$.groups.length()").value(5))
                .andExpect(jsonPath("$.groups[0].name").value("增长类"));
    }

    /**
     * 零宽字符是 <b>400 {@code INVALID_NAME}</b>,不是 409。
     *
     * <p>两者要分得开:409 是「名字挺好,只是有人先叫了」,下一步是换一个;
     * 400 是「这个名字本身不能用」,下一步是把粘贴进来的东西重新手打一遍。
     * 而且这里<b>不做规范化删掉它</b> —— 悄悄删字符等于替用户改了名字,他永远不会知道。
     */
    @Test
    @DisplayName("🔴 零宽字符 → 400 INVALID_NAME(不是 409);中英文数字标点一个都别误伤 → 201")
    void zeroWidthNamesAreRejectedButOrdinaryOnesAreNot() throws Exception {
        // JSON 里的 \\u200b 由 Jackson 还原成真正的零宽空格 —— 源码里不放看不见的字符
        for (String escaped : List.of("\\u200b", "\\u200d", "\\ufeff")) {
            mockMvc.perform(json(post("/api/syllabus/nodes"), """
                            {"groupCode":"growth","name":"%s增长量计算","recent5yCount":0}
                            """.formatted(escaped)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_NAME"))
                    .andExpect(jsonPath("$.message").value(Matchers.containsString("零宽")));
        }

        // 🔴 别误伤:中文、英文、数字、常见标点(含全角)都要 201
        List<String> legal = List.of(
                "增长率计算(逆向)", "GDP compound rate", "2021-2025 年均值",
                "速算:截位直除法", "A/B 对比,含 %", "题型 #3 —— 特殊情形");
        for (String name : legal) {
            mockMvc.perform(json(post("/api/syllabus/nodes"), """
                            {"groupCode":"growth","name":"%s","recent5yCount":0}
                            """.formatted(name)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.node.name").value(name));
        }

        mockMvc.perform(get("/api/coverage/summary"))
                .andExpect(jsonPath("$.total").value(18 + legal.size()));
    }

    /**
     * 🔴 「看不见的字符」<b>不止 Cf 一类</b> —— 这条是一次实打实的绕过换来的。
     *
     * <p>早先的判定是 {@code Character.getType(cp) == Character.FORMAT}。谚文填充符 U+3164
     * 的类别是 <b>Lo(字母)</b>、盲文空点 U+2800 是 So(符号)、变体选择符 U+FE0F 是 Mn ——
     * 三个都不是 Cf,三个都渲染不出任何东西。当时它们全都返回 201,
     * 于是树上多出一个与「增长量计算」<b>逐像素相同</b>的考点,而用户是按名字挑考点的。
     *
     * <p>处置分两种,因为来路不同(见 {@code SyllabusNames#isVariationSelector}):
     * <ul>
     *   <li>独立成字的那些(填充符、盲文空点)→ <b>400 拒绝</b>,它们没有正当用途</li>
     *   <li>变体选择符 → <b>放行</b>(它跟着 emoji 一起被正常输入,拒绝就是误伤),
     *       但比较时被剥掉,所以只会得到 <b>409</b>,不会得到第二个考点</li>
     * </ul>
     */
    @Test
    @DisplayName("🔴 填充符/盲文空点 → 400;变体选择符 → 409 NAME_TAKEN(都不能变成第二个考点)")
    void invisibleCharactersBeyondCfCannotMintATwinNode() throws Exception {
        // JSON 里写转义,由 Jackson 还原成真正的字符 —— 源码里不放一个看不见的字符
        for (String escaped : List.of("\\u3164", "\\u2800", "\\u034f", "\\u115f", "\\uffa0")) {
            mockMvc.perform(json(post("/api/syllabus/nodes"), """
                            {"groupCode":"effect","name":"增长量计算%s","recent5yCount":0}
                            """.formatted(escaped)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_NAME"))
                    .andExpect(jsonPath("$.message").value(Matchers.containsString("看不见")));
        }

        // 变体选择符:放行进 validName,但 nameKey 剥掉它 → 409,而且报错要说清是被谁占着
        mockMvc.perform(json(post("/api/syllabus/nodes"), """
                        {"groupCode":"effect","name":"增长量计算\\ufe0f","recent5yCount":0}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NAME_TAKEN"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("增长类")));

        // 名字里一个看得见的字符都没有 → 400,否则面板上会出现一个挑不出来的考点
        mockMvc.perform(json(post("/api/syllabus/nodes"), """
                        {"groupCode":"effect","name":"\\ufe0f\\ufe00","recent5yCount":0}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NAME"));

        // 🔴 一次都没成功 —— 覆盖率的分母一个数都没动
        mockMvc.perform(get("/api/coverage/summary"))
                .andExpect(jsonPath("$.total").value(18));
    }


    @Test
    @DisplayName("🔴 报错回声有长度上限 —— 路径变量没有 @Size 管着,别成了写日志的通道")
    void rejectionMessagesDoNotEchoUnboundedInput() throws Exception {
        String pastedStem = "2023 年全国粮食总产量为 13908 亿斤,比上年增加 177 亿斤".repeat(40);

        String message = mockMvc.perform(post("/api/syllabus/nodes/{code}/archive", pastedStem))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(message.length() < pastedStem.length(),
                "整段原文被原样回声了 —— 它同时会进服务端日志");
    }

    // ---------------------------------------------------------------- 🔴 导出有,导入没有

    @Test
    @DisplayName("导出自己的树:名称/层级/频次/归档,四样,没有内容也没有第四层")
    void exportCarriesTheTreeAndNothingElse() throws Exception {
        mockMvc.perform(post("/api/syllabus/nodes/mixed-growth/archive")).andExpect(status().isOk());

        mockMvc.perform(get("/api/syllabus/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject.display").value("山东省考 · 行测 · 资料分析"))
                .andExpect(jsonPath("$.groups.length()").value(5))
                .andExpect(jsonPath("$.groups[0].code").value("growth"))
                .andExpect(jsonPath("$.groups[0].nodes.length()").value(7))     // 归档的也导出
                .andExpect(jsonPath("$.groups[0].nodes[0].code").value("growth-rate"))
                .andExpect(jsonPath("$.groups[0].nodes[0].recent5yCount").value(9))
                .andExpect(jsonPath("$.groups[0].nodes[6].archived").value(true))
                // 🔴 没有内容,也没有第四层
                .andExpect(jsonPath("$.groups[0].nodes[0].children").doesNotExist())
                .andExpect(jsonPath("$.groups[0].nodes[0].explanation").doesNotExist())
                .andExpect(jsonPath("$.groups[0].nodes[0].content").doesNotExist())
                .andExpect(jsonPath("$.groups[0].nodes[0].difficulty").doesNotExist());
    }

    @Test
    @DisplayName("🔴 没有「批量导入考点体系」的端点,请求体里也没有能装下一棵子树的位置(R-07)")
    void thereIsNoBulkImportChannel() throws Exception {
        for (String path : List.of("/api/syllabus/import", "/api/syllabus/nodes/import",
                "/api/syllabus/groups/import", "/api/syllabus/tree")) {
            mockMvc.perform(json(post(path), "{}"))
                    .andExpect(status().is4xxClientError());
        }

        for (Method m : SyllabusAdminController.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            assertFalse(name.contains("import") || name.contains("bulk") || name.contains("batch"),
                    "🔴 不许出现批量导入考点体系的端点:" + m.getName());
        }

        // 结构上也堵住:任何 *Request 里都不许出现能一次提交一棵子树的字段
        Set<String> subtreeFields = Set.of("groups", "nodes", "tree", "syllabus", "subject", "children");
        for (Class<?> req : requestDtos()) {
            for (RecordComponent c : req.getRecordComponents()) {
                assertFalse(subtreeFields.contains(c.getName()),
                        req.getSimpleName() + " 不允许一次提交一棵子树(R-07 / docs/04 §1.2):" + c.getName());
            }
        }
    }

    @Test
    @DisplayName("🔴 每一个写入请求体都有 @JsonAnySetter 那道锁 —— 不依赖任何 Jackson 配置")
    void everyRequestDtoCarriesTheUnknownFieldLock() {
        List<Class<?>> requests = requestDtos();
        assertTrue(requests.size() >= 8, "扫描没扫到东西,这个测试就形同虚设:" + requests);

        for (Class<?> req : requests) {
            boolean locked = Arrays.stream(req.getDeclaredMethods())
                    .anyMatch(m -> m.isAnnotationPresent(JsonAnySetter.class));
            assertTrue(locked, req.getSimpleName()
                    + " 少了 @JsonAnySetter 那道锁 —— 关掉 FAIL_ON_UNKNOWN_PROPERTIES 之后"
                    + "自由文本标签就能进来了(见 UnknownFieldException 的说明)");
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** dto 包里所有以 Request 结尾的 record —— 它们就是全部的写入入口。 */
    private static List<Class<?>> requestDtos() {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AssignableTypeFilter(Object.class));
        List<Class<?>> result = new ArrayList<>();
        for (BeanDefinition bd : provider.findCandidateComponents("com.kaodian.server.api.dto")) {
            try {
                Class<?> c = Class.forName(bd.getBeanClassName());
                if (c.isRecord() && c.getSimpleName().endsWith("Request")) {
                    result.add(c);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    @TestConfiguration
    static class Fixtures {

        /** 行为层:真的文件 store,从种子播出那 8 条记录。 */
        @Bean
        TouchStore touchStore(Clock clock) {
            return new FileTouchStore(dataDir.resolve("touches.json"), clock);
        }

        /**
         * 骨架层:<b>真的</b> {@link FileSyllabusStore}。
         *
         * <p>规则(code 谁生成、有记录能不能删、顺序算不算完整排列)全长在它身上,
         * 换成内存假实现等于把规则再写一遍然后验它自己。
         */
        @Bean
        SyllabusStore syllabusStore(NodeRecordLedger ledger) {
            return new FileSyllabusStore(dataDir.resolve("syllabus.json"), ledger);
        }

        /** {@link CoverageReader} 是 {@code @Component},web 切片不扫它。 */
        @Bean
        CoverageReader coverageReader(SyllabusSource syllabus, TouchStore store,
                                      RecordTagStore tagStore, AssertionStore assertionStore,
                                      CoverageService coverage, Clock clock) {
            return new CoverageReader(syllabus, store, tagStore, assertionStore, coverage, clock);
        }

        /** 「我已掌握」。它不进覆盖度的分子(01 §5.2:补丁不是解法),但 CoverageReader 要读它。 */
        @Bean
        AssertionStore assertionStore() {
            return new InMemoryAssertionStore();
        }

        /** 标签层。考点管理不打标,但覆盖度的分子要从这里出来。 */
        @Bean
        RecordTagStore recordTagStore() {
            return new InMemoryRecordTagStore();
        }

        @Bean
        CaptureService captureService(TouchStore store, VisionTagger tagger,
                                      SyllabusSource syllabus, Clock clock) {
            return new CaptureService(store, tagger, syllabus, clock);
        }

        /** 这个测试里没有任何一条路该调用模型 —— 一旦被调用就红。 */
        @Bean
        VisionTagger visionTagger() {
            return (image, mimeType, candidates) -> {
                throw new AssertionError("考点管理不该调用识别");
            };
        }
    }
}
