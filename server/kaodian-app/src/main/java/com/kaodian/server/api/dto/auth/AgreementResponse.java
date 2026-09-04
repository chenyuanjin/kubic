package com.kaodian.server.api.dto.auth;

import jakarta.validation.constraints.Size;

/**
 * {@code GET /api/auth/agreements/current} 的答复 —— {@code B0} §十二 / {@code 接口契约} §7.1。
 *
 * <h2>🔴 两个字段,一个都不多;尤其没有 {@code agreed}</h2>
 *
 * {@code U5.2} 要三档:<b>有新版本 / 没有新版本 / 正文拉不到</b>,而第三档
 * <b>不许被当成「已同意」</b>。这里的做法是<b>靠结构挡,不靠规则挡</b> ——
 * 这个端点<b>不表达任何「已同意」语义</b>,同意由「提交时携带版本号」表达:
 * 拉不到 → 端填不出 {@code agreedVersion} → 提交必然被拒。
 * <b>第三档在结构上不可能变成已同意</b>,不需要任何人记得别把它当成同意。
 *
 * <h2>🔴 也没有任何用户数据</h2>
 *
 * 它是白名单第 4 行,<b>匿名</b>(协议版本必须在登录<b>前</b>拿得到,
 * 否则 {@code agreedVersion} 填不出、登录门点不动)。
 * 一个匿名端点只要返回任何与调用者有关的东西,它就成了一个<b>不需要令牌的用户数据出口</b>。
 *
 * <p>⚠️ 正文本身在 {@code L-A5} 律师稿里。这里只有版本号与地址,
 * 不定版本号格式之外的任何东西。
 *
 * <h2>两个 {@code @Size} 是 {@code R-01} 的落点,不是防御性编程</h2>
 *
 * 上限不是给合法输入留余量,是<b>把「放个版本号 / 一个地址」和「放段内容」分在两边</b>。
 * 这两个值来自配置,而配置是人手填的 —— 一个没有上限的「协议正文地址」
 * 就是一个能装下整篇正文的字段({@code NoStemFieldTest} 守着这一条)。
 */
public record AgreementResponse(

        @Size(max = 32)
        String version,

        @Size(max = 200)
        String url) {
}
