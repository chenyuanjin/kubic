package com.kaodian.server.api.dto;

/**
 * 删除成功之后的答复:删掉的是哪个 code + 整棵树的新覆盖概览。
 *
 * <p>删除只在<b>确认没有任何记录挂着</b>之后才会走到这里(见
 * {@code SyllabusStore#deleteNode} 的删除守则),所以这个响应体不需要
 * 「顺便删掉了 N 条记录」这样的字段 —— <b>那个数永远是 0</b>,
 * 而一个恒为 0 的字段迟早会被人改成非 0。
 *
 * @param code    被删掉的考点或题型 code
 * @param summary 删完之后整棵树的覆盖概览(分母会少一个)
 */
public record DeletedResponse(
        String code,
        SummaryDto summary
) {
}
