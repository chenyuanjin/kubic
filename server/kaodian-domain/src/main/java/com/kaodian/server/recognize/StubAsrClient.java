package com.kaodian.server.recognize;

import org.springframework.stereotype.Component;

/**
 * 占位实现 —— <b>还没有任何厂商密钥,所以它诚实地失败。</b>
 *
 * <h2>为什么不返回一段假文字</h2>
 *
 * 假装成功比诚实失败危险得多:一段假转写会一路走到打标、走到覆盖度,
 * 最后变成一个看起来正常、其实是编的数。而<b>覆盖度失真的话,这个产品就没有指标了</b>
 * (决策记录 §2.2 宁缺毋滥 / docs/识别链路 坑一)。
 * <p>
 * docs/技术架构 §3.1 对这个接口的注释是「失败抛异常,不返回半成品」。空串、占位符、
 * 「[识别失败]」这类值都是半成品 —— 它们会被下游当成真结果继续用。
 *
 * <h2>它失败了也不会让记录丢掉</h2>
 *
 * docs/总路线图 §1.3.7.1:识别服务不可用时,<b>记录动作本身永不失败</b>。
 * 这个异常在 {@link com.kaodian.server.collect.CaptureService} 里被接住。
 *
 * <p>换成真实实现(腾讯云一句话识别,docs/识别链路 §一)时,只需要多一个 {@link AsrClient}
 * 实现类并让它优先生效 —— 这正是 docs/识别链路 坑三要的那个切换点。
 */
@Component
public class StubAsrClient implements AsrClient {

    @Override
    public String transcribe(byte[] audio, String mimeType) {
        throw new RecognitionUnavailableException(
                "ASR 尚未接入(当前为占位实现)。请自己从考点树里挑一个考点 —— 手动记录永不消耗额度,也永远可用。");
    }
}
