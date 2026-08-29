package com.kaodian.server.api;

import com.kaodian.server.collect.CandidateRecall;
import com.kaodian.server.collect.TouchLedger;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.coverage.CoverageService;
import com.kaodian.server.syllabus.NodeRecordLedger;
import com.kaodian.server.syllabus.Syllabus;
import com.kaodian.server.syllabus.SyllabusStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 领域对象的装配点。
 *
 * <h2>为什么差集服务在这里被声明成 bean,而不是在它自己的类上打注解</h2>
 *
 * {@link CoverageService} 是<b>纯领域对象</b> —— 它只做计数、比时间、两个整数相除。
 * 让它认识 Spring 没有任何好处,反而会把 docs/10 §2.2「包之间只通过接口调用」的边界弄脏:
 * {@code coverage} 包应该能在没有容器的情况下被直接 new 出来测试
 * ({@code CoverageServiceTest} 现在就是这么做的)。
 * <p>
 * 所以装配这件事留在最外层的 {@code api} 包里 —— <b>谁组装,谁依赖框架</b>。
 *
 * <h2>🔴 这里<b>没有</b>一个 {@code Syllabus} bean,这是有意的</h2>
 *
 * 骨架层从这一版起可写({@link SyllabusStore}),而 {@link Syllabus} 是不可变的 record。
 * 把它声明成单例 bean 注入下去,拿到的就是<b>进程启动那一刻的快照</b> ——
 * 用户新增一个考点之后覆盖率的分母不动、新考点挂不上记录,<b>而且全程不报错</b>。
 * <p>
 * 所以全进程只有一个骨架来源:{@code FileSyllabusStore}(它是 {@code @Component})。
 * 需要读树的地方一律注入 {@code SyllabusSource},每次现问。
 * <b>两处持有同一棵树就一定会持有两棵不同的树</b> —— 与 {@link CoverageReader}
 * 开头那句「两处算同一个数就一定会算出两个数」是同一条。
 */
@Configuration
public class ApiBeans {

    /** 差集服务。无状态,单例。 */
    @Bean
    public CoverageService coverageService() {
        return new CoverageService();
    }

    /**
     * 候选召回 —— 打标管线的第 ① 段(docs/13 §1.3)。
     *
     * <p>与 {@link CoverageService} 同一个理由放在这里而不是打 {@code @Component}:
     * 它是<b>纯领域对象</b>(一棵树 + 一个字符串进,一组候选出),没有任何状态、
     * 不认识 Spring;{@code collect} 包应该能在没有容器的情况下被直接 new 出来测试。
     * <b>谁组装,谁依赖框架。</b>
     */
    @Bean
    public CandidateRecall candidateRecall() {
        return new CandidateRecall();
    }

    /**
     * 骨架层看行为层的那扇小窗 —— <b>删除守则靠它兑现</b>。
     *
     * <h2>为什么是在这里用一个 lambda 接起来,而不是让 {@code syllabus} 包直接依赖 {@code collect}</h2>
     *
     * docs/10 §2.2:包之间只通过接口调用。{@code syllabus} 包不认识 {@code Touch},
     * 它只需要两件事 —— 「这个考点上挂着几条记录」和「把它们搬到另一个考点去」。
     * 接口定义在 {@code syllabus} 侧({@link NodeRecordLedger}),实现由这里拼装,
     * 于是依赖方向始终是<b>行为层被骨架层使用,而不是骨架层认识行为层的数据结构</b>。
     *
     * @see com.kaodian.server.syllabus.SyllabusStore#deleteNode
     */
    @Bean
    public NodeRecordLedger nodeRecordLedger(TouchStore touches) {
        return new TouchLedger(touches);
    }

    /**
     * 判定基准时刻的来源。
     *
     * <p>「生疏」是纯时间推出来的({@link com.kaodian.server.coverage.NodeState#RUSTY}),
     * 所以「现在几点」必须是一个<b>可替换的依赖</b>而不是散落在代码里的 {@code Instant.now()} ——
     * 否则接口层就没法在测试里回放「32 天前练过」这种场景。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
