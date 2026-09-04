package com.kaodian.server.api;

import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.coverage.CoverageService.Summary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「为 0」与「没数过」在响应里必须是两个值 ——
 * {@code M3-骨架与覆盖度差集} §二(判据总表第 7 条)。
 *
 * <h2>三档,不是两档</h2>
 *
 * <table border="1">
 *   <caption>三档各自的形态</caption>
 *   <tr><th>档</th><th>响应形态</th><th>界面</th></tr>
 *   <tr><td><b>数过了,是零</b></td><td>key <b>在</b>,值 {@code 0}</td><td>恒等式项照常显示 0</td></tr>
 *   <tr><td><b>没数过</b></td><td>key <b>整个不出现</b>(不是 {@code null},不是 {@code 0},不是 {@code ""})</td>
 *       <td>写该项的缺失文案</td></tr>
 *   <tr><td><b>骨架还没建好</b></td><td>不是字段档,是<b>状态码档</b> {@code 422 SYLLABUS_EMPTY}</td>
 *       <td>整屏进空态,一个数都不显示</td></tr>
 * </table>
 *
 * <h2>🔴 这个类断言的是 JSON <b>文本</b>,不是反序列化之后的对象</h2>
 *
 * 「key 不出现」与「key 在但值是 null」在一个 {@code Map} 里长得几乎一样,
 * 而它们在端上是两句完全不同的话:后者会让端写出一句
 * {@code if ('assertedCount' in resp)} 然后<b>永远为真</b>。
 * 所以下面用 {@link String#contains} 直接看序列化出来的那串字符 —— 那才是端真正收到的东西。
 */
class NullKeyOmissionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("🔴 重算中:三个数与 assertedCount 四个 key 全不出现,archivedCount 在")
    void recalculatingDropsFourKeysAndKeepsArchivedCount() {
        String json = mapper.writeValueAsString(SummaryDto.recalculating(6));

        for (String key : new String[]{"nodeTotal", "nodeTouched", "nodeUntouched", "assertedCount"}) {
            assertFalse(json.contains(key),
                    "重算中 " + key + " 的 key 还在 JSON 里:" + json);
        }
        // 🔴 archivedCount 与那四个待遇不同,理由是依赖不同:它只依赖骨架层,
        //    与用户行为无关,不参与这次重算 —— 所以它算得出来,就该照常返回。
        //    而 assertedCount 的定义含着 B = D∖N,依赖行为层,重算中算不出来。
        assertTrue(json.contains("\"archivedCount\":6"),
                "archivedCount 必须照常出现,R-49「归档计数常驻可见」:" + json);
        assertTrue(json.contains("\"recalculating\":true"), json);
    }

    @Test
    @DisplayName("🔴 数过了是零:五个数为 0 时 key 全都在,值是 0")
    void zeroIsAValueNotAnAbsence() {
        String json = mapper.writeValueAsString(
                SummaryDto.of(new Summary(0, 0, 0, 0, 0), null));

        assertTrue(json.contains("\"nodeTotal\":0"), json);
        assertTrue(json.contains("\"nodeTouched\":0"), json);
        assertTrue(json.contains("\"nodeUntouched\":0"), json);
        assertTrue(json.contains("\"archivedCount\":0"), json);
        assertTrue(json.contains("\"assertedCount\":0"), json);
        assertTrue(json.contains("\"recalculating\":false"), json);

        // ⚠️ 这一屏上的「0 个考点」是合法的:它说的是「这棵树上未归档的叶子一个都没有」。
        //    而「这个科目的骨架还没建好」是另一档 —— 它走 422,根本不到这里。
        //    两者混成一档的那一版会让空态屏显示一个假的 0。
    }

    @Test
    @DisplayName("🔴 没数过:statsAsOfYear 没有来源时,key 整个不出现,不是 null 也不是 0")
    void missingStatsYearDropsTheKeyEntirely() {
        String withNothing = mapper.writeValueAsString(
                SummaryDto.of(new Summary(412, 180, 232, 6, 3), null));
        assertFalse(withNothing.contains("statsAsOfYear"),
                "没有统计时 statsAsOfYear 的 key 必须整个消失:" + withNothing);

        String withYear = mapper.writeValueAsString(
                SummaryDto.of(new Summary(412, 180, 232, 6, 3), 2024));
        assertTrue(withYear.contains("\"statsAsOfYear\":2024"), withYear);
    }

    @Test
    @DisplayName("三个数由服务端算并返回 —— 前端不做任何一次减法")
    void allThreeNumbersAreOnTheWire() {
        SummaryDto dto = SummaryDto.of(new Summary(412, 180, 232, 6, 3), null);
        assertEquals(412, dto.nodeTotal());
        assertEquals(180, dto.nodeTouched());
        // 🔴 232 是服务端数出来的,不是 412 − 180 算出来的。这个字段存在的全部理由
        //    就是让端不必做那次减法 —— 端做的那一版会在服务端口径改变时无声地对不上。
        assertEquals(232, dto.nodeUntouched());
    }
}
