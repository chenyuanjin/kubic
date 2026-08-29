package com.kaodian.server.recognize;

import java.util.List;

/**
 * 图片 + 候选考点 → 候选里的一个 code,或「不匹配」。<b>识别接口层的两个出口之一。</b>
 *
 * <h2>🔴 闭集分类的形状长在方法签名上,不是写在注释里</h2>
 *
 * docs/09 坑一(标注为「这条最重要」):<b>模型是分类器,不是标签生成器。</b>
 * 解法是「输入 = 图 + 自己的考点树候选,输出 = 树里的节点 ID 或『不匹配』」。
 * <p>
 * 这条约束在这里有三个抓手,少一个都能被绕过:
 * <ol>
 *   <li>{@code candidates} 是<b>必填入参</b> —— 不给候选集就没法调用,闭集不是可选项</li>
 *   <li>返回类型是 {@link RecognitionResult},里面<b>没有 String label 字段</b>(docs/10 §3.1)</li>
 *   <li>{@link #enforceClosedSet} 在出口再核一遍:code 不在候选里就丢掉 ——
 *       docs/08 {@code 1.2.5.1.6} 说得很清楚,<b>「不是靠 prompt 里写一句『不要讲解』,是在输出侧检」</b></li>
 * </ol>
 *
 * <h2>🔴 签名上没有 URL、没有 fileId、没有 bucket、没有任何存储路径</h2>
 *
 * docs/09 坑二把厂商 Files API 列为红线项:它免费、看起来像白送的优化,
 * 但它与 01 §2.3 的原图红线正面冲突 ——「原图只做本地缓存或短期留存,不做长期云端存储和任何形式的共享」,
 * 而这条<b>第一天不定,后面改不回来</b>。
 * <p>
 * 所以入参是 {@code byte[]}:base64 内联编码在实现类内部完成,<b>接口不暴露任何 fileId 概念</b>
 * (docs/10 §3.1)。签名里没有能装下「一个指向已存图片的引用」的位置,
 * 「传上去拿个 file_id 复用」这条路就不存在。
 * <p>
 * 实现类的三条附带禁令(docs/10 §8.1):不写磁盘、不进对象存储、
 * <b>不把 base64 打进任何级别的日志</b>——一次 {@code log.debug(request)} 就等于把原图落了盘。
 */
public interface VisionTagger {

    /**
     * 一个候选考点。<b>只有 code 与名称</b>,没有讲解、没有例题 ——
     * 它是送进 prompt 的东西,而 prompt 里出现的每一个字都会变成模型的可用素材。
     *
     * @param code 考点树里的 code,也是模型唯一被允许输出的东西
     * @param name 考点名。<b>自行归纳的命名</b>,不沿用机构既有体系与措辞(R-07 / docs/04 §1.2)
     */
    record Candidate(String code, String name) {
        public Candidate {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("候选必须有 code");
            }
        }
    }

    /**
     * 闭集分类:从 {@code candidates} 里挑一个,或者说不匹配。
     *
     * @param image      原图字节。<b>只在内存里过一次</b>,实现类内部转 base64 内联发送,
     *                   调用完即释放;不落盘、不进对象存储、不进日志(docs/10 §8.1)
     * @param mimeType   如 {@code image/jpeg}、{@code image/png}
     * @param candidates 候选考点,<b>不能为空</b>。模型只能在这里面挑
     * @return 命中候选里的某个 code,或 {@link RecognitionResult#noMatch()}
     * @throws RecognitionUnavailableException 服务不可用/未配置。
     *         <b>抛这个异常不等于记录失败</b> —— 见 docs/08 §1.3.7.1
     */
    RecognitionResult classify(byte[] image, String mimeType, List<Candidate> candidates);

    /**
     * 输出侧自检:结果里的 code 必须真的在候选集里,否则一律降级为 NO_MATCH。
     *
     * <h2>为什么不信任实现类</h2>
     *
     * 实现类里是一次网络调用加一段字符串解析,模型完全可能吐回一个候选集里没有的 code
     * (幻觉,或者干脆把机构的标准表述当 code 吐出来)。docs/09 坑一说的
     * 「LLM 出错是<b>编造一个不存在的考点</b>」就是这个 —— 而错标会让覆盖度失真,
     * 覆盖度就是整个产品。
     *
     * <h2>为什么是丢弃而不是抛异常</h2>
     *
     * 模型胡说不该让用户的记录动作失败(docs/08 §1.3.7.1)。按 01 §2.2「宁缺毋滥」
     * 丢掉这次识别、请用户自己从树里挑一个,是代价最小的处理。
     * 置信度留着不清零,便于事后排查「是候选召回没覆盖,还是模型在乱答」。
     */
    static RecognitionResult enforceClosedSet(RecognitionResult result, List<Candidate> candidates) {
        if (!result.matched()) {
            return result;
        }
        boolean inSet = candidates != null
                && candidates.stream().anyMatch(c -> c.code().equals(result.nodeCode()));
        return inSet ? result : RecognitionResult.noMatch(result.confidence());
    }
}
