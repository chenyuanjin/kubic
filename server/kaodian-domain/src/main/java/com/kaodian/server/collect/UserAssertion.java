package com.kaodian.server.collect;

import java.time.Instant;

/**
 * 「我已掌握」—— docs/技术架构 §5.2 的 {@code user_assertion} 表。
 *
 * <h2>🔴 它<b>不</b>进覆盖度的分子。这是这个 record 存在的全部理由</h2>
 *
 * 决策记录 §5.2 的原话是:「录入完整度决定诊断质量……<b>『我已掌握』按钮是补丁不是解法</b>。」
 * 那一节说的是一个没解决的问题:用户在抖音看了半小时没记,产品说「你没碰过」,
 * 于是他认为工具不准。这个按钮是给他一个当场消音的办法 —— <b>它治的是「被冤枉了」这个感受,
 * 不是「录入不完整」这个病</b>。
 * <p>
 * 所以它一旦计进覆盖度,就把补丁伪装成了疗效:那个百分比会因为<b>点按钮</b>而上升,
 * 而这个产品唯一的那个数字必须只由「碰过」推出来(决策记录 §2.2 能力边界:有没有、几次、多久前)。
 * 一个能靠自我声明刷高的覆盖率,和一个没有覆盖率的产品,价值是一样的。
 * <p>
 * 落到代码上就是三件事,写在 {@code CoverageService} 里:
 * <ol>
 *   <li>覆盖率的<b>分子不变</b> —— 断言不让任何考点变成「碰过」</li>
 *   <li>盲区榜<b>排除</b>它 —— 用户不想再被提醒,这是他按下按钮时要的东西(docs/技术架构 §6.4)</li>
 *   <li>概览里<b>单列一格</b> —— 「你声明掌握了 N 个」,与覆盖率并排但不相加(docs/技术架构 §6.4)</li>
 * </ol>
 *
 * <h2>⚠️ 断言不是归档。两者是同一类问题的两个不同答案,不要混成一个概念</h2>
 *
 * <table border="1">
 *   <caption>归档与断言的差别</caption>
 *   <tr><th></th><th>归档({@code POST /syllabus/nodes/{code}/archive})</th><th>断言(这里)</th></tr>
 *   <tr><td>分母</td><td><b>拿掉</b>({@code Group#activeNodes})</td><td><b>留着</b></td></tr>
 *   <tr><td>分子</td><td>跟着一起拿掉</td><td>不进</td></tr>
 *   <tr><td>那个百分比</td><td>比值仍然诚实(上下同时少一)</td><td><b>一个字都不动</b></td></tr>
 *   <tr><td>说的是什么</td><td>「这个考点<b>与我无关</b>」—— 骨架层的裁剪</td>
 *       <td>「这个考点我会了,只是<b>没记</b>」—— 行为层的补丁</td></tr>
 * </table>
 * 归档那一侧的风险有单独一条记录({@code docs/总路线图} §四 {@code R-49}:「归档可无声刷高覆盖率」,
 * 对策是归档计数常驻 + 导出带完整归档清单 + 归档区永远可见可找回)。
 * <b>断言这一侧没有那条风险,因为它根本不动那个比值</b> —— 代价是它必须在别处被看见,
 * 否则用户会以为自己按了个没反应的按钮。所以概览单列一格、树上每个考点带 {@code assertedAt}。
 *
 * <h2>为什么没有 {@code userId}</h2>
 *
 * 与 {@link Touch} 逐字同理:阶段 0/1 是单进程单用户的本地文件({@code FileTouchStore} 类注释),
 * 行为层三张表里没有一张带 {@code user_id}。到 {@code 1.2.4} 落库那天一起加,
 * 现在先造一个恒等于同一个值的字段,只是让每一行都多一个不会被读的键。
 *
 * <h2>为什么没有「取消时刻」这类字段</h2>
 *
 * 取消就是<b>删掉这一行</b>({@code AssertionStore#remove}),不是置一个标志位。
 * 这和标签的 {@code discarded} 不一样,那里要留住「我曾经把它标成 A」这件事,
 * 因为它是<b>准确率口径的分母</b>({@code 1.2.5.2});断言没有任何口径要拿它当分母,
 * 留一行「取消了的声明」除了让文件变长以外不产生任何答案。
 *
 * @param nodeCode  声明掌握了哪个考点。🔴 只接受考点树里已存在的 code(R-07 与 {@link Touch} 同)
 * @param assertedAt 按下按钮的时刻。重复断言<b>不刷新</b>它 —— 见 {@link AssertionStore#put}
 */
public record UserAssertion(String nodeCode, Instant assertedAt) {

    public UserAssertion {
        if (nodeCode == null || nodeCode.isBlank()) {
            throw new IllegalArgumentException("必须指向一个考点");
        }
        if (assertedAt == null) {
            throw new IllegalArgumentException("必须有时间戳 —— 「什么时候声明的」是这一行仅有的事实");
        }
    }
}
