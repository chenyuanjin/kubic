package com.kaodian.server.api.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/billing/orders/{outTradeNo}/receipt/verify} 的请求体(§4.5)。
 *
 * <p>🔴 <b>只有一个字段,而且带长度上限</b>:收据是端送来的自由文本(base64),
 * 没有上限的 {@code String} 在这个仓库里是一条红线({@code R-01})——
 * 一个能装下一整段题干的字段,不分它本来打算装什么。
 * <p>
 * Apple 收据实际长度在几 KB 量级,{@code 16384} 给了余量;超了直接 400,不进任何业务。
 *
 * <p>🔴 <b>没有 {@code amountFen}、没有 {@code transactionId}</b> ——
 * 那两个值只能从上游校验的结果里来,接受端送的等于让端自己说「我付了多少」。
 */
public record ReceiptVerifyRequest(@NotBlank @Size(max = 16384) String receipt) {
}
