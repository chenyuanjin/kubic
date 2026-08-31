package com.kaodian.server.api.dto.auth;

/**
 * 登录请求体上那两个自由文本字段的上限 —— <b>一个数,三个入口共用</b>(docs/总路线图 §四 R-73)。
 *
 * <h2>为什么不是各写各的</h2>
 *
 * {@code deviceLabel} 与 {@code referrer} 各出现在三个登录请求体里。三处各写一个字面量,
 * 就是六个数,而六个数迟早对不上 —— 到那时候真正生效的是最小的那个,没人说得清是哪个。
 * {@code SessionDto#deviceLabel} 之所以能只当「下游投影」处理、不再写一遍上限,
 * 靠的就是<b>上游只有这一个数</b>。
 *
 * <h2>⚠️ 这里只是「入口拒」,还差「落盘截断」</h2>
 *
 * 完整的收口是两半:<b>入口拒</b>挡住「有人把这个字段当内容位用」,
 * <b>落盘截断</b>兜住「认不出设备不能成为登不进去的理由」({@code TokenService#issue} 的原话)。
 * 后半在 {@code auth} 包里,{@code TokenService#issue} 今天只做「空 → 未知设备」,不截断;
 * {@code SignupLedger.Entry} 同理。<b>那一半没落,这一行注释就不能删。</b>
 *
 * <p>这两个常量最终该搬进 {@code AccessToken} / {@code SignupLedger.Entry} ——
 * 跟 {@code Touch.MAX_CLIENT_TOKEN_LENGTH} 一样住在落盘的那个 record 上。
 * 放在 dto 层是<b>当下能做到的那一半</b>,不是它该待的地方。
 */
final class LoginFieldLimits {

    /**
     * 设备名 40 字符 —— 与骨架节点名({@code FileSyllabusStore.MAX_NAME_LENGTH})同一档,
     * 因为两者是同一种东西:<b>一个给人看的名字</b>。
     *
     * <p>「chenyj 的 iPhone」「iPhone · Safari」十来个字,40 是宽裕的。
     * <b>不给「客户端拼了一整条 User-Agent」留余量是有意的</b> ——
     * 这个字段可空,拼不出短标签的正确出路是不传(服务端会写「未知设备」),
     * 而不是传一个一百多字的串。把上限抬到装得下 UA,这个位置就变成了一个
     * 能放段落的自由文本位,那正是 R-01 要挡的形状。
     */
    static final int MAX_DEVICE_LABEL = 40;

    /**
     * 渠道标识 64 字符 —— 与 {@code nodeCode}、{@code clientToken} 同一档,
     * 因为它跟那两个一样<b>是机器读的标识符,不是给人看的名字</b>,如 {@code xhs_2026w34_a}。
     *
     * <p>它只在建号那一次写进 {@code signups.json},作用是给「陌生 vs 熟人」的人工判定留一条线索
     * ({@code SignupLedger})。<b>一条线索不需要一段话。</b>
     * 之所以不放宽到能装下带 UTM 参数的整条落地页 URL:那时它就不是渠道码了,
     * 而一个能装任意 URL 的字段等于一个能装任意文本的字段。
     */
    static final int MAX_REFERRER = 64;

    private LoginFieldLimits() {
    }
}
