package com.kaodian.server.api;

import com.kaodian.server.api.dto.common.BlindSpotDto;
import com.kaodian.server.api.dto.common.GroupDto;
import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.common.NodeDto;
import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.api.dto.common.SyllabusNodeDto;
import com.kaodian.server.api.dto.insight.BlindSpotsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 {@code M3} 的响应体里<b>没有任何浮点字段</b> ——
 * {@code M3-骨架与覆盖度差集} §7.2(判据总表第 19 条)。
 *
 * <h2>为什么这条比字段名黑名单硬</h2>
 *
 * 黑名单({@code accuracy} / {@code percent} / {@code mastery} …)拦的是<b>名字</b>,
 * 而<b>改个名字就绕过去了</b>:一个叫 {@code weight} 或 {@code score} 或 {@code w}
 * 的 {@code double} 一样是掌握度。
 * <p>
 * 这条拦的是<b>类型</b>。「有没有 / 几次 / 多久前」三件事的答案分别是
 * {@code bool} / {@code int} / 带时区的绝对时间 —— <b>三种里没有一种需要小数</b>。
 * 所以一个浮点字段出现在这一域,它一定是一个比值;而比值只有两种:掌握度,或百分比。
 * 两种都不许上屏({@code 看盲区} §2.9)。
 *
 * <h2>怎么让它变红(它必须先红过一次)</h2>
 *
 * 往 {@link SummaryDto} 上加一个 {@code double ratio} 分量,或者把
 * {@link NodeDetailDto} 的 {@code recent5yCount} 改成 {@code Double}。两种都当场判红。
 * <p>
 * ⚠️ 上一版这条<b>是红的</b>:{@code Summary#ratio()} / {@code #percent()}、
 * {@code NodeCoverage#accuracy()}、{@code NodeState.WEAK_BELOW} 四处。
 */
class CoverageNoRatioTest {

    /**
     * 🔴 遍历的是<b>响应体</b>上的类型,不是随便挑的几个类。
     *
     * <p>{@link BlindSpotsResponse} 与 {@link GroupDto} 是容器,它们把上面四个包起来 ——
     * 列在这里是为了让「往容器上直接加一个浮点」也被拦住,而不只是拦叶子 DTO。
     */
    private static final List<Class<?>> M3_RESPONSE_TYPES = List.of(
            SummaryDto.class,
            BlindSpotDto.class,
            NodeDetailDto.class,
            SyllabusNodeDto.class,
            NodeDto.class,
            GroupDto.class,
            BlindSpotsResponse.class);

    private static final Set<Class<?>> FORBIDDEN =
            Set.of(double.class, float.class, Double.class, Float.class, BigDecimal.class);

    @Test
    @DisplayName("🔴 M3 的响应 DTO 里没有一个 double / float / Double / Float / BigDecimal")
    void noFloatingPointAnywhereInM3Responses() {
        List<String> hits = new ArrayList<>();
        for (Class<?> type : M3_RESPONSE_TYPES) {
            for (RecordComponent c : type.getRecordComponents()) {
                if (FORBIDDEN.contains(c.getType())) {
                    hits.add(type.getSimpleName() + "#" + c.getName()
                            + " : " + c.getType().getSimpleName());
                }
            }
        }
        assertTrue(hits.isEmpty(),
                "M3 的响应体里出现了浮点字段。一个浮点数在这一域一定是一个比值,"
                        + "而比值只有掌握度和百分比两种,两种都不许上屏(§7.2):" + hits);
    }

    @Test
    @DisplayName("🔴 遍历的清单本身不许空掉 —— 一条断言最常见的死法是它扫了 0 个类")
    void theInventoryItselfIsNotEmpty() {
        // 上面那条断言在 M3_RESPONSE_TYPES 被清空时会「通过」。
        // 这一条守的就是那种通过 —— 它与 AgentBoundaryTest 里 scanned >= 15 是同一条纪律。
        assertTrue(M3_RESPONSE_TYPES.size() >= 7, "要扫的响应类型少了");
        for (Class<?> type : M3_RESPONSE_TYPES) {
            assertTrue(type.isRecord() && type.getRecordComponents().length > 0,
                    type.getSimpleName() + " 不是 record 或者一个分量都没有,反射扫不到东西");
        }
    }
}
