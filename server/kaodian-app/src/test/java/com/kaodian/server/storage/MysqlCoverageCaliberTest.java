package com.kaodian.server.storage;

import com.kaodian.server.collect.AssertionStore;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.coverage.CoverageService.Summary;
import com.kaodian.server.collect.UserAssertion;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 换到 MySQL 之后,<b>覆盖度还是不是同一个数</b>。
 *
 * <h2>为什么要有这一条,以及为什么它必须连真库</h2>
 *
 * KUBI-112 把行为层 / 标签层 / 断言层 / 骨架层四个 store 从文件换成了 MySQL。
 * 换存储的风险不在「跑不起来」——跑不起来会当场报错。风险在<b>算出来的数悄悄变了</b>:
 * 同毫秒两条记录的次序抖一下、幂等没落在唯一索引上多进一条、
 * {@code reassign} 写成 delete+insert 把 {@code occurredAt} 重置掉 ——
 * 这些每一条都不报错,只是让覆盖度变成另一个数。而覆盖度是这个产品唯一的那个数。
 * <p>
 * 所以这条测试<b>不接受任何替身</b>:不用 H2、不用 in-memory、不用 mock。
 * H2 的 MySQL 兼容模式对 {@code ON DUPLICATE KEY} 与 {@code SELECT ... FOR UPDATE}
 * 的语义与 MySQL 并不逐条相同,而那两处正好就是幂等与删除守则的落点 ——
 * 拿它当判据,等于用一个和被测对象不一样的东西证明被测对象是对的。
 *
 * <h2>两处独立断言</h2>
 *
 * <ol>
 *   <li><b>领域层:算得对不对。</b> 直接走 store → {@link CoverageReader},
 *       断言四次写入各自让哪个数动、哪个数<b>不能动</b>。
 *       它验的是公式对存储变化的反应,而不是把公式再实现一遍去对答案 ——
 *       镜像实现只能证明两份代码一样,证明不了它们是对的。</li>
 *   <li><b>契约层:吐出去的还是不是同一个数。</b> 同一个上下文里请求
 *       {@code GET /api/v1/coverage/summary},断言 JSON 里的每个数
 *       与上一步那个 {@link Summary} <b>逐字段相等</b>。
 *       这一条挡的是「库里对了、序列化/DTO 那一层又变了一次」。</li>
 * </ol>
 *
 * <h2>怎么跑</h2>
 *
 * 没有 {@code KAODIAN_TEST_MYSQL_URL} 时整条跳过 —— 让每个 clone 下来的人先装一个 MySQL
 * 才能跑 {@code build.sh test},代价远大于这条断言的价值(仓库里绝大多数测试与库无关)。
 * 跳过是显式的:JUnit 会把它记成 skipped,不是记成 passed。
 * <pre>
 *   ssh -fN -L 3307:127.0.0.1:3307 ubuntu@62.234.164.41
 *   KAODIAN_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3307/kaodian_test?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
 *   KAODIAN_TEST_MYSQL_USER=kaodian KAODIAN_TEST_MYSQL_PASSWORD='...' \
 *   ./server/build.sh -q test -Dtest=MysqlCoverageCaliberTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <h2>🔴 它把库还原成原样</h2>
 *
 * 测试库是共享的(联调环境那一套),所以每一步写入都在 {@code finally} 里撤掉,
 * 顺序与删除守则一致:先删记录、再删断言、最后才删考点 ——
 * 考点上还挂着记录时 {@code deleteNode} 本来就该拒绝,这个顺序同时也验了那条守则还在。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "KAODIAN_TEST_MYSQL_URL", matches = ".+",
        disabledReason = "没有 KAODIAN_TEST_MYSQL_URL —— 这条断言只在连着真 MySQL 时才有意义")
class MysqlCoverageCaliberTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("kaodian.data.store", () -> "jdbc");
        registry.add("spring.datasource.url", () -> System.getenv("KAODIAN_TEST_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("KAODIAN_TEST_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("KAODIAN_TEST_MYSQL_PASSWORD"));
        // 鉴权侧这一轮仍是文件存储,别让它往真正的 ~/.kaodian 里写。
        registry.add("kaodian.data.dir", () -> System.getProperty("java.io.tmpdir") + "/kaodian-caliber");
    }

    @Autowired MockMvc mvc;
    @Autowired CoverageReader reader;
    @Autowired SyllabusStore syllabus;
    @Autowired TouchStore touches;
    @Autowired AssertionStore assertions;
    @Autowired Clock clock;

    private Summary now() {
        return reader.summarize(reader.read());
    }

    @Test
    void 换成MySQL之后覆盖度还是同一个数() throws Exception {
        // 名字带 UUID:测试库是共享的,固定名字会在第二次跑的时候撞上「整棵树名字唯一」。
        String uniqueName = "口径校验-" + UUID.randomUUID().toString().substring(0, 8);
        String groupCode = syllabus.current().groups().getFirst().code();

        Syllabus.Node node = null;
        Touch touch = null;
        String assertedCode = null;
        try {
            Summary s0 = now();

            // ① 新考点进分母,不进分子。
            //    这一步同时是 JdbcSyllabusStore 的写路径:名字唯一性落在 name_key 唯一索引上。
            node = syllabus.addNode(groupCode, uniqueName, 3);
            Summary s1 = now();
            assertEquals(s0.total() + 1, s1.total(), "新考点必须让分母 +1");
            assertEquals(s0.covered(), s1.covered(), "🔴 新考点一条记录都没有,分子不许动");
            assertEquals(s0.empty() + 1, s1.empty(), "空白数跟着分母走");

            // ② 记一笔 → 分子 +1,分母不动。
            String token = "caliber-" + UUID.randomUUID();
            touch = touches.append(new Touch(
                    "t-" + UUID.randomUUID(), node.code(), "口径校验",
                    TouchKind.MANUAL, clock.instant().truncatedTo(ChronoUnit.MILLIS), null, token));
            Summary s2 = now();
            assertEquals(s1.total(), s2.total(), "记一笔不该改变分母");
            assertEquals(s1.covered() + 1, s2.covered(), "记一笔之后这个考点就该算碰过");

            // ③ 幂等:同一个 client_token 再发一次 —— 补传就是重发,两次请求会叠在一起。
            //    这一条落在 uk_touch_client_token 上,不落在「先查再写」。
            //    没落住的表现不是报错,是分子被数了两次,而那正是这个产品唯一的那个数。
            Touch again = touches.append(new Touch(
                    "t-" + UUID.randomUUID(), node.code(), "口径校验",
                    TouchKind.MANUAL, clock.instant().truncatedTo(ChronoUnit.MILLIS), null, token));
            assertEquals(touch.id(), again.id(), "🔴 命中去重键要原样返回第一次那条,不是新建一条");
            Summary s3 = now();
            assertEquals(s2.total(), s3.total());
            assertEquals(s2.covered(), s3.covered(), "🔴 重发一次不许让分子再涨");

            // ④ 断言「我会了」→ 只动 asserted,绝不动覆盖度。
            //    按按钮就能让覆盖率上升的话,这个数就不再是「盲区」的度量了。
            assertedCode = node.code();
            assertions.put(new UserAssertion(assertedCode, clock.instant().truncatedTo(ChronoUnit.MILLIS)));
            Summary s4 = now();
            assertEquals(s3.asserted() + 1, s4.asserted(), "断言数 +1");
            assertEquals(s3.covered(), s4.covered(), "🔴 按「我已掌握」不许让覆盖度动一下");
            assertEquals(s3.total(), s4.total());

            // ⑤ 契约层:同一时刻从 HTTP 出去的,必须是上面那个 Summary 里的同一组数。
            assertTrue(s4.total() > 0, "分母为 0 说明骨架层没读出来,后面的相等断言会变成空断言");
            mvc.perform(get("/api/v1/coverage/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(s4.total()))
                    .andExpect(jsonPath("$.covered").value(s4.covered()))
                    .andExpect(jsonPath("$.empty").value(s4.empty()))
                    .andExpect(jsonPath("$.percent").value(s4.percent()))
                    .andExpect(jsonPath("$.asserted").value(s4.asserted()))
                    .andExpect(jsonPath("$.whollyEmptyGroups").value(s4.whollyEmptyGroups()));
        } finally {
            // 顺序即删除守则:考点上还挂着记录时 deleteNode 本来就该拒绝。
            if (touch != null) {
                assertNotNull(touches.delete(touch.id()), "清理:那条记录应当还在");
            }
            if (assertedCode != null) {
                assertions.remove(assertedCode);
            }
            if (node != null) {
                syllabus.deleteNode(node.code());
            }
        }
    }
}
