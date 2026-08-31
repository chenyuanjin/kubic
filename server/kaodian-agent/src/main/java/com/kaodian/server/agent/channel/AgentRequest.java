package com.kaodian.server.agent.channel;

import java.util.List;

/**
 * 入站契约 —— 所有通道(今天只有 SSE over HTTP)归一成这一个 record 后交给编排层。
 *
 * <p>比 truman-ai 的同名 record 少了七个字段(sceneEntity / imageUrls / userContentTypes /
 * control / locale / channel …)。那些字段每一个都对应它那边一条真实的业务线,
 * <b>而我们一条都还没有</b>。瘦入参不是简化,是不预支。
 *
 * <p>🔴 <b>刻意没有 {@code imageUrls}</b>:truman-ai 那边图片有两份(base64 给模型 / URL 给工具),
 * 因为它有对象存储。我们没有,也不打算有 —— 原图有 URL 就意味着它在某个 bucket 里(R-04)。
 * 这里只有字节,没有任何「指向已存图片的引用」的位置。
 *
 * @param userId    发起人
 * @param sessionId 会话 id;为 null 时按单轮对话处理(不读历史,也不写会话锚点)
 * @param message   用户这一轮说的话
 * @param images    用户这一轮附的图片<b>原始字节</b>。
 *                  🔴 这些字节<b>只在内存里活到发给模型为止</b>:不落盘、不进 messages.ndjson、
 *                  不打进任何级别的日志、不走任何厂商的文件暂存 API(R-04 / R-52)。
 *                  落盘时只记「这一轮带了几张图」这个事实,见 {@code Orchestrator}
 */
public record AgentRequest(long userId, String sessionId, String message, List<byte[]> images) {

    /** 单轮最多几张图。与 {@code RecognitionController} 的采集端点同档(docs/技术架构 §6.2「单次 ≤6 张」)。 */
    public static final int MAX_IMAGES = 6;

    /** 兼容无图调用点(单轮文字提问)。 */
    public AgentRequest(long userId, String sessionId, String message) {
        this(userId, sessionId, message, List.of());
    }

    public AgentRequest {
        images = images == null ? List.of() : List.copyOf(images);
        if (images.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("一次最多 " + MAX_IMAGES + " 张图,收到 " + images.size());
        }
        // 带图时允许文字为空(「就这张图,帮我看看」是合理的问法);纯文字轮次仍然必须有话。
        if ((message == null || message.isBlank()) && images.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        if (message == null) {
            message = "";
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "单轮消息最长 " + MAX_MESSAGE_LENGTH + " 个字符 —— 这是个提问框,不是放内容的地方");
        }
    }

    /**
     * 单轮提问的长度上限。
     *
     * <p>2000 的理由与 {@code Touch.MAX_CLIENT_TOKEN_LENGTH} 同源:<b>把「问一句话」和
     * 「贴一段材料」分在两边</b>。资料分析一道题的材料就上千字,让它能整段贴进来,
     * 就等于给了一条「把真题内容送进模型」的路 —— 而 数据线 §二 说的是线上库里不能有装题干的字段,
     * 请求体同样不该成为那个字段的替身。
     */
    public static final int MAX_MESSAGE_LENGTH = 2000;
}
