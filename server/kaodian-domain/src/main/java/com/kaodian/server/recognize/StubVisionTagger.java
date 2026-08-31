package com.kaodian.server.recognize;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 占位实现 —— <b>还没有任何厂商密钥,所以它一律返回 NO_MATCH。</b>
 *
 * <h2>为什么不随便挑一个候选返回</h2>
 *
 * 「反正是占位,先返回第一个候选让链路跑通」是最自然的写法,也是最危险的:
 * 它会让覆盖度悄悄变成一个编出来的数,而<b>覆盖度就是整个产品</b>。
 * docs/识别链路 坑一:「OCR 出错是漏字,LLM 出错是编造一个不存在的考点。而错标会让覆盖度失真,
 * 那个指标就是整个产品」。占位实现乱挑,和模型幻觉的后果一模一样。
 * <p>
 * 总路线图 §三 的能力边界表里那一行也是这个意思:<b>低于阈值就丢弃,不硬凑一个最接近的考点</b>。
 * 没有模型 = 没有置信度 = 永远低于阈值 = NO_MATCH。
 *
 * <h2>返回 NO_MATCH 而不是抛异常,是因为这不是「服务挂了」</h2>
 *
 * 服务不可用抛 {@link RecognitionUnavailableException}(界面说「稍后重试」);
 * 没接入是产品的既定状态,不是故障(界面说「自己从树里挑一个」)。
 * 两者在 {@link com.kaodian.server.collect.CaptureService} 的返回里是不同的拒绝理由。
 *
 * <p>换成真实实现(多模态一步到位,docs/识别链路 §一)时,只需要多一个 {@link VisionTagger}
 * 实现类:内部把 {@code image} 转 base64 <b>内联</b>发送(🔴 禁用厂商 Files API,docs/识别链路 坑二),
 * 把 {@code candidates} 拼成闭集分类的 prompt,结果一律经
 * {@link RecognitionResult#of} 过阈值、经 {@link VisionTagger#enforceClosedSet} 过候选集。
 */
@Component
public class StubVisionTagger implements VisionTagger {

    @Override
    public RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates) {
        // 🔴 刻意不碰 image:不落盘、不进日志、不做任何缓存。占位实现也守同一条线,
        //    否则「等接真模型时再补」永远不会发生。
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("闭集分类必须给候选集 —— 没有候选就没有『集』可闭");
        }
        return RecognitionResult.noMatch();
    }
}
