package com.kaodian.server.api.dto;

/**
 * 请求体里出现了 DTO 没有定义的字段。
 *
 * <h2>🔴 为什么这个类必须存在,而不是靠一行配置</h2>
 *
 * R-07 在接口层号称有两道锁:一是「DTO 里没有 {@code name / label / tag} 这类字段」,
 * 二是「未定义字段一律 400」。但第二道锁原先<b>整个压在 {@code application.properties} 里的一行}</b>
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES=true})上,而 {@code @JsonIgnoreProperties(ignoreUnknown = false)}
 * <b>是个空操作</b> —— 它只是「不忽略」,至于「不忽略之后要不要失败」仍然由那一行配置说了算。
 * <p>
 * 实测:把那一行注释掉,{@code {"tag":"我自己想的考点"}} 立刻返回 <b>201 Created</b>,
 * 正是那份 javadoc 自己描述的「双方都以为它生效了」。也就是说,原来的两道锁其实是<b>同一道</b>,
 * 而它可以被任何一个人以「统一 JSON 配置」为由顺手关掉,并且<b>不会有任何测试变红</b>。
 * <p>
 * 这个异常把第二道锁搬回代码里:{@code CreateRecordRequest} 上的 {@code @JsonAnySetter}
 * 会接住每一个未定义字段并抛出它,<b>与 ObjectMapper 怎么配置无关</b>。
 * 由 {@code ApiContractTest} 用一个显式关掉该开关的 mapper 钉住。
 *
 * <h2>只带字段名,不带值</h2>
 *
 * 值是用户送来的原文,可能就是一整段题干。它不进消息、不进日志、不进响应体 ——
 * 见 {@code ApiExceptionHandler} 开头那条纪律。字段名回声前还要再过一次截断。
 */
public class UnknownFieldException extends RuntimeException {

    private final String fieldName;

    public UnknownFieldException(String fieldName) {
        // 🔴 只有字段名。构造消息时绝不带上那个字段的值。
        super("请求体不接受未定义字段:" + fieldName);
        this.fieldName = fieldName;
    }

    public String fieldName() {
        return fieldName;
    }
}
