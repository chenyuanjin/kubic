package com.kaodian.server.recognize;

/**
 * 一次识别的结果 —— <b>要么是考点树里的一个 code,要么是「不匹配」。没有第三种。</b>
 *
 * <h2>🔴 这个 record 里没有 {@code label}、没有 {@code tag}、没有 {@code text}</h2>
 *
 * docs/09 坑一:模型是<b>分类器</b>,不是标签生成器。放开自由生成会同时踩两条线 ——
 * 编造出树里不存在的考点(踩「宁缺毋滥」,而错标会让覆盖度失真,那个指标就是整个产品),
 * 以及原样吐出机构的既有措辞(踩 R-07 标签自行命名)。
 * <p>
 * docs/10 §3.1 把解法写成一句话:<b>「返回类型里根本没有 {@code String label} 字段」</b>。
 * 所以这里的 {@link #nodeCode} 只可能是调用方传进去的候选集里的某一个 —— 由
 * {@link VisionTagger#enforceClosedSet} 在出口处再核一遍。
 *
 * <h2>为什么 {@code nodeCode} 为空时仍然保留 {@code confidence}</h2>
 *
 * 「什么都没认出来」和「认出来了但只有 0.42 分,按宁缺毋滥丢掉」是两件事:
 * 前者说明图糊了或根本不是题,后者说明候选召回可能没覆盖到。二者对产品的含义不同,
 * 所以三个分量都留着,不做压缩。
 *
 * @param nodeCode       考点树里的 code;{@code null} 表示 NO_MATCH
 * @param confidence     模型自报的置信度,0~1。<b>它只用来过阈值,不参与任何学科判断</b>
 * @param aboveThreshold 是否达到 {@link #MIN_CONFIDENCE}。达到才可能有 {@code nodeCode}
 */
public record RecognitionResult(String nodeCode, double confidence, boolean aboveThreshold) {

    /**
     * 置信度阈值 —— <b>低于它一律 NO_MATCH,不硬凑最接近的考点。</b>
     *
     * <p>这是 01 §2.2「宁缺毋滥」在类型层的落点,也是 08 §三 能力边界表里
     * 「低于阈值就丢弃 / 不硬凑一个最接近的考点」那一行。
     *
     * <p><b>0.75 这个数字本身是占位的,重要的是「有一条线、低于线一律丢」这个形状。</b>
     * 真正的取值要等 {@code 1.2.5.2} 用评测集标定后回填;在那之前把它调低,
     * 等于用覆盖度的准确性换录入的顺手 —— 而覆盖度失真的话,这个产品就没有指标了。
     */
    public static final double MIN_CONFIDENCE = 0.75;

    public RecognitionResult {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("置信度必须在 0~1:" + confidence);
        }
        if (nodeCode != null && nodeCode.isBlank()) {
            throw new IllegalArgumentException("nodeCode 要么是树里的 code,要么是 null,不能是空串");
        }
        // 不变式:有 code ⇔ 过了阈值。把它钉在构造器上,是为了让「低置信度还带着 code 返回」
        // 这种写法根本构造不出来 —— 不是靠调用方自觉检查。
        if (aboveThreshold != (nodeCode != null)) {
            throw new IllegalArgumentException("aboveThreshold 必须与 nodeCode 是否存在一致");
        }
        if (aboveThreshold && confidence < MIN_CONFIDENCE) {
            throw new IllegalArgumentException("低于阈值不允许带出 nodeCode(宁缺毋滥):" + confidence);
        }
    }

    /** 什么都没认出来。 */
    public static RecognitionResult noMatch() {
        return new RecognitionResult(null, 0.0, false);
    }

    /** 认出来了,但只有这么点置信度 —— 按宁缺毋滥丢掉,置信度留着供排查。 */
    public static RecognitionResult noMatch(double confidence) {
        return new RecognitionResult(null, confidence, false);
    }

    /**
     * 唯一的「命中」构造入口。
     *
     * <p><b>阈值裁决在这里发生,而不是在每个实现类里。</b> 换厂商换的是实现类,
     * 换不掉这条线 —— docs/09 坑三要的「切换点」不能顺带把红线也切换掉。
     */
    public static RecognitionResult of(String nodeCode, double confidence) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return noMatch(confidence);
        }
        return confidence < MIN_CONFIDENCE ? noMatch(confidence) : new RecognitionResult(nodeCode, confidence, true);
    }

    /** 是否挂得上一个考点。 */
    public boolean matched() {
        return nodeCode != null;
    }
}
