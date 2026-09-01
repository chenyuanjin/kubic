package com.kaodian.server.api.dto.record;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * {@code POST /records/{id}/tags/suggest} 的请求体 —— <b>一个字段都没有,这就是它的全部内容。</b>
 *
 * <h2>🔴 「请求体不接受调用方指定标签文本」是靠没有字段实现的</h2>
 *
 * docs/technical/INDEX.md §6.3 对这个端点的约束原文:「<b>请求体不接受调用方指定标签文本。</b>
 * 候选由服务端召回,响应是 {@code nodeId + confidence} 或 {@code NO_MATCH}」。
 * <p>
 * 把这句话写成校验(「如果传了 name 就报错」)是最自然的做法,也是最弱的:
 * 校验会被绕过、被放宽、被某次重构删掉,而且删掉的那次提交看起来只是「清理没用的检查」。
 * <b>写成「这个 record 没有分量」则连绕的对象都不存在</b> —— 与
 * {@code RecognitionResult} 那句「返回类型里根本没有 String label 字段」是同一条思路,
 * 只是这次在入口侧。
 *
 * <h2>为什么还要有这个类,而不是干脆不收 body</h2>
 *
 * 不收 body 的话,{@code {"name":"我自己起的考点"}} 会被 Spring 直接忽略,端点返回 200,
 * <b>调用方以为它生效了</b>。那正是 {@code CreateRecordRequest} 用
 * {@link #rejectUnknownField} 挡掉的同一种失守 —— 静默忽略比报错危险,
 * 因为双方都以为红线没被碰过。
 * <p>
 * 所以这里收一个空壳:body 可以整个不传,但只要传了、且里面有任何一个键,就是 400。
 */
public record SuggestTagRequest() {

    /**
     * 🔴 任何键都拒 —— 与 {@code CreateRecordRequest.rejectUnknownField} 同一道锁,
     * 而且<b>与 ObjectMapper 怎么配置无关</b>。
     *
     * <p><b>参数 {@code value} 收下就丢,一个字都不许流出去</b>:它是用户送来的原文,
     * 可能就是一整段题干。异常里只带字段名(决策记录 §2.2 不碰内容)。
     */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
