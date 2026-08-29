package com.kaodian.server.api.dto;

import jakarta.validation.constraints.Size;

/**
 * {@code POST /records/{id}/audio} 的答复 —— <b>里面没有转写文本,而且永远不会有。</b>
 *
 * <h2>🔴 为什么这个 record 里没有 {@code transcript} / {@code text} / {@code summary}</h2>
 *
 * docs/10 §5.2「不建的表」逐字写着:「<b>任何音频表 —— {@code 1.1.1.5}:ASR 失败提示重录,
 * 不留存音频</b>」;§5.1 写着「OCR/ASR 的完整长文本<b>只在内存里过一次</b>,用于打标」。
 * <p>
 * 「不留存」如果只兑现在<b>库</b>这一侧,是守不住的:一个把转写文本原样吐回响应体的端点,
 * 会让那段文字进访问日志、进前端的本地缓存、进任何一个把响应体存下来的中间层。
 * 所以这里的做法与 {@code RecognitionResult}「返回类型里根本没有 {@code String label} 字段」
 * 完全同构 —— <b>结构上没有能装下它的位置</b>。
 * <p>
 * 顺带,{@code NoStemFieldTest} 的字段名黑名单里就有 {@code transcript} / {@code text} /
 * {@code content},而那条断言<b>没有白名单</b>:「改名字也不算数」。
 * 也就是说,想把转写文本塞进响应体的人得先去改 R-01 —— 这正是那条断言存在的意义。
 *
 * <h2>为什么答复这么薄</h2>
 *
 * 这个端点<b>今天不产生任何可见的状态变化</b>(见 {@code RecognitionController#transcribe} 的
 * ⚪ 那一段:「文字 → 考点」的闭集匹配还没建)。所以它不返回标签列表、不返回覆盖度概览 ——
 * 返回一份「跟你请求之前一模一样」的数据,只会让调用方以为发生过什么。
 * <b>薄是诚实,不是没写完。</b>
 *
 * @param outcome 机器读的结局码,取值见 {@code RecognitionController.AudioOutcome}
 * @param message 界面可以直接显示的那句话。契约里「<b>失败提示重录</b>」就落在这上面
 */
public record AudioRecognitionResponse(

        @Size(max = 32)
        String outcome,

        @Size(max = 120)
        String message
) {
}
