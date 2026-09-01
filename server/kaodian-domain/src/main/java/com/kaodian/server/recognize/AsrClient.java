package com.kaodian.server.recognize;

/**
 * 语音 → 文字。<b>识别接口层的两个出口之一。</b>
 *
 * <h2>为什么是接口 —— 这不是「将来可能要换」的空话</h2>
 *
 * docs/data/识别链路选型.md 坑三:选型推荐的视觉模型标注为<b>实验版本,官方未给出任何稳定性承诺、
 * 下线时间表或迁移说明</b>({@code R-35})。缓解手段就写在那条里:识别调用必须走接口层抽象,
 * <b>保留一个切换点</b>。ASR 同理 —— 首选腾讯云一句话识别(免费额度 5,000 次/月,
 * 覆盖到阶段 3),但换成本地 whisper 或别家,应当只是换一个实现类。
 * <p>
 * docs/technical/INDEX.md §2.1 把这一层画成整个后端<b>唯一允许出现外部模型调用的地方</b>。
 * 别处出现 HTTP 调模型,就是绕开了这个切换点。
 *
 * <h2>🔴 签名上没有 URL、没有 fileId、没有任何存储路径</h2>
 *
 * 入参是音频<b>字节</b>,一次过、用完即弃。docs/technical/INDEX.md §八:服务端不写磁盘、不进对象存储。
 * 只要签名里没有「一个指向已存音频的引用」,「先存起来再处理」这条路就走不通。
 * <p>
 * 时长上限(≤60s,{@code 1.1.1.4})属于调用方的约束,不放在这里 ——
 * 这个接口只负责「一段字节进,一段文字出」。
 *
 * <h2>🔴 转写结果不会落库</h2>
 *
 * 返回的文字只用于识别考点,<b>用完即弃</b>。
 * {@link com.kaodian.server.collect.Touch} 里没有能装下它的字段(决策记录 §2.2 不碰内容),
 * 这不是「不填」,是结构上没有这个位置。
 *
 * <h2>当前还没有调用点,这是有意的</h2>
 *
 * 「文字 → 考点」的闭集匹配属于打标管线({@code 总路线图 §1.2.5}),那一段还没建。
 * docs/technical/INDEX.md §3.1 的原话是「<b>这两个接口的签名是本文档里唯一值得现在就写下来的代码</b>」——
 * 先把出口的形状钉死,是为了模型下线那天只用改一个实现类。
 * 在打标管线建起来之前,语音记的考点仍由用户自己从树里挑
 * ({@link com.kaodian.server.collect.CaptureService#capture}),这条路径不消耗任何模型额度。
 */
public interface AsrClient {

    /**
     * 把一段音频转成文字。
     *
     * @param audio    音频字节。<b>只在内存里过一次</b>,实现类不得落盘、不得进日志
     * @param mimeType 如 {@code audio/wav}、{@code audio/mp4}
     * @return 转写文本。<b>调用方用完即弃,不得落库</b>
     * @throws RecognitionUnavailableException 服务不可用/未配置。<b>失败就抛,不返回半成品</b>(docs/technical/INDEX.md §3.1)
     */
    String transcribe(byte[] audio, String mimeType);
}
