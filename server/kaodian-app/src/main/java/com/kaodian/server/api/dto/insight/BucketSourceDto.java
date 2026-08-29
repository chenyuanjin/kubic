package com.kaodian.server.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 一格里的一个来源 —— 「这一天/这一周,你从这个来源记了几笔」。
 *
 * <h2>只有名字和一个整数</h2>
 *
 * 「粉笔 · 资料分析系统班 L12」是一个<b>字符串</b>,不是那节课的任何内容
 * (01 §2.2 不碰内容:机构的课程内容一概不存,只记来源名与时间戳)。
 * 上游的 {@link com.kaodian.server.collect.Touch} 结构上就没有内容字段,这里也长不出来。
 *
 * <h2>🔴 这里<b>没有</b>占比,只有条数</h2>
 *
 * 「粉笔占 62%」看着只是个除法,但它是一句<b>关于来源的判断</b> ——
 * 而下一步必然是「你太依赖粉笔了」。产品只报「有没有、几次、多久前」(01 §2.2)。
 * 想画饼图的话分母就在同一个桶的 {@code touchCount} 里,除法交给前端,
 * <b>服务端不替用户下这个结论</b>。
 *
 * @param sourceName 来源名。上限引用写入侧那个常量,<b>不在这里另写一个数</b> ——
 *                   见 {@link CreateRecordRequest#MAX_SOURCE_NAME_LENGTH}
 * @param touchCount 这一格里来自这个来源的记录条数
 */
public record BucketSourceDto(

        @Size(max = CreateRecordRequest.MAX_SOURCE_NAME_LENGTH)
        String sourceName,

        int touchCount
) {
}
