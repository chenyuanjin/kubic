package com.kaodian.server.syllabus;

/**
 * 骨架数据文件本身不合法 —— 而不是这次请求不合法。
 *
 * <h2>为什么单独立一个类型:那句写得很仔细的话得让人看见</h2>
 *
 * {@link SyllabusLoader} 在发现「考点 code 重复」「考点名重复」这类问题时,
 * 会给出一句**能直接照着改**的话,比如
 * 「考点名重复:{@code growth-amount} 与 {@code n-4c2c23b2} 都叫「增长量计算」…改掉其中一个的 name」。
 * <p>
 * 但它原本抛的是 {@link IllegalStateException},落进 {@code ApiExceptionHandler} 的兜底段,
 * 被换成「服务器内部错误,请把 traceId 报给我们」—— <b>那句话只到了日志,到不了任何人眼前</b>。
 * 对一个跑在本机、只有你一个用户的工具来说,「去翻日志」约等于没有提示。
 *
 * <h2>为什么把原文透出来是安全的</h2>
 *
 * 通用 500 之所以不回显原始消息,是怕泄漏内部实现(类名、路径、堆栈)。这里不存在那个风险:
 * 消息里只有<b>用户自己敲进去的考点 code 与名字</b>,以及一句中文的修改建议。
 * 堆栈仍然只进日志。
 *
 * <h2>它不是 4xx</h2>
 *
 * 这不是「你这次请求写错了」,是「磁盘上那份数据现在读不了」。
 * 请求本身完全正确,换个请求也一样失败,所以仍然是 5xx —— 只是一个<b>说得出原因</b>的 5xx。
 *
 * <p>触发路径是真实存在的:导出的骨架 JSON 可以手工改完放回 {@code ~/.kaodian/syllabus.json},
 * 改错了就会走到这里。
 */
/*
 * 继承 IllegalStateException 而不是 RuntimeException,是为了不改动既有测试的断言 ——
 * 那些测试断言的是「坏文件必须炸,不能静默当成空树」,这个断言一个字都不该改。
 * 新类型只是在同一个行为上加了一层「说得出原因」,是加法,不是替换。
 */
public class SyllabusDataException extends IllegalStateException {

    public SyllabusDataException(String message) {
        super(message);
    }

    public SyllabusDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
