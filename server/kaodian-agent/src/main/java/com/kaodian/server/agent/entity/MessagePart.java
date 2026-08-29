package com.kaodian.server.agent.entity;

/**
 * 一条 {@link AgentMessage} 由若干有序的 part 组成,粒度与 SSE 事件流对齐:
 * {@code TextPart} 对应 token 帧,{@code ToolCallPart} 对应 tool-call 帧,以此类推。
 *
 * <p>🔴 <b>这里没有、也不会有装内容的 part。</b>没有 ImagePart、没有 AudioPart、没有原文 part。
 * R-04 的形态是「原图只在内存里活到抽取完成」,而落盘的 part 数组是最顺手的一个反例位置 ——
 * 加一个 {@code ImagePart(String base64)} 不会报任何错,只会让每一轮对话都在磁盘上留一份图。
 * truman-ai 那边的 {@code ImagePart} 存的是 URL(它有对象存储,我们没有,也不打算有)。
 *
 * <p>新增 part 类型时注意:全仓消费点都是 {@code switch} 模式匹配(sealed 接口会强制穷尽),
 * 加一个类型编译器就会点出所有该改的地方。
 *
 * <h2>为什么没有 Jackson 的 {@code @JsonTypeInfo}</h2>
 *
 * 落盘由 {@code FileRunRepository} <b>逐字段手写</b>,不走自动序列化 ——
 * 与 {@code FileTouchStore} 同一条纪律(「文件里能出现哪些键,由 toNode 显式列出」)。
 * 自动序列化省下的那点代码,换来的是「将来 part 上多一个字段,它就自动流进了每一个文件」;
 * 而这一层将来最可能多出来的字段,恰恰是装内容的那种。
 */
public sealed interface MessagePart {

    /** 模型说的话,或者用户问的话。 */
    record TextPart(String text) implements MessagePart {}

    /**
     * 模型要求调一个工具。
     *
     * @param id        LLM 协议侧的 call id(如 {@code call_abc123}),用于和结果配对
     * @param name      工具英文 slug
     * @param arguments 模型给的入参 JSON 原文。<b>原样存</b>:模型把参数写歪了是排查的主要线索,
     *                  规整过再存就把证据洗掉了
     */
    record ToolCallPart(String id, String name, String arguments) implements MessagePart {}

    /**
     * 工具的返回。
     *
     * @param error 工具是否失败。失败也要落 —— 「模型问了但没拿到」和「模型没问」是两回事
     */
    record ToolResultPart(String id, String name, String result, boolean error) implements MessagePart {}
}
