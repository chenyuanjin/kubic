package com.kaodian.server.api.profile;

// 🔴 注解留在 com.fasterxml.jackson.annotation,不在 tools.jackson:
// Jackson 3 的 databind 是 tools.jackson.databind,但注解仍在 Jackson 2.x 的 group id 下
// (逐字理由见 com.kaodian.server.api.dto.common.ApiError 开头那段)。
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.kaodian.server.profile.ExamProfile;

/**
 * {@code GET / PUT /api/v1/profile/exam} 的<b>同一副形状</b> —— 请求体与响应体是同一个 record。
 *
 * <h2>为什么请求与响应共用一个类型,而不是各写一个</h2>
 *
 * 契约里那两行本来就是同一副形状(`接口契约` §12.9.1),而 {@code PUT} 的语义是
 * <b>全量覆盖</b> —— 「发出去什么,读回来就是什么」。拆成两个 record 的写法,
 * 会给「响应里多一个字段」留一个谁都不会注意的入口,而这个端点上最需要防住的
 * 恰恰就是<b>多出来的那个字段</b>({@code U3.8} §2.4:任何派生天数)。
 * <p>
 * 共用之后,加字段这件事在两侧同时发生、同时被 review,躲不掉。
 *
 * <h2>🔴 {@code @JsonInclude(NON_NULL)} 不是风格选择</h2>
 *
 * 它是 §1.1 空值规则在这个端点上的<b>执行装置</b>:「没设过时<b>整个 key 不出现</b>,
 * 不返回 {@code null} / {@code "unknown"}」(§八)。
 * <p>
 * 这条在这里比在别处更硬,因为端上判「该不该出档案屏」的判定式逐字是
 * 「{@code GET /profile/exam} 的响应体是<b>空对象</b>」(§5.4)。
 * 回一个 <code>{"examType":null,"examDate":null}</code> 不是空对象 ——
 * 端会写出 {@code if ('examType' in body)} 然后永远为真,于是<b>那一屏再也不出现</b>,
 * 而服务端一切正常、日志一行都没有。
 *
 * <h2>🔴 这个 record 里没有第三个字段,尤其没有任何天数</h2>
 *
 * 两个字段,一个不多。{@code U3.8} §2.4 的第一道防线就在这里:
 * 服务端只给绝对日期,<b>不给「还有 N 天」这类由日期派生出来的量</b>。
 * 一个这样的量一旦上了屏,能和它搭配的只可能是复习提醒或紧迫感文案,两样都在能力边界之外。
 *
 * @param examType 闭集:{@code national} 或省级行政区代码(两位数字)。
 *                 没设过 / 已清空时<b>整个 key 不出现</b>
 * @param examDate {@code YYYY-MM-DD}。🔴 <b>{@code date} 不是 {@code datetime}</b>,
 *                 所以出口是 {@link java.time.LocalDate#toString()} 而不是一个时刻 ——
 *                 用 {@code Instant} 装它,响应里就会多出一个没人定义过的时区与时分秒。
 *                 同样,没设过则 key 不出现
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExamProfileDto(String examType, String examDate) {

    /** 空对象 —— 没设过。{@code @JsonInclude(NON_NULL)} 把它渲染成 <code>{}</code>。 */
    static final ExamProfileDto EMPTY = new ExamProfileDto(null, null);

    /**
     * 领域对象 → 出口形状。{@code null}(从没设过)与「两格皆空」渲染成同一个空对象 ——
     * §5.4 逐字写了这两者「不分,本来就是同一件事」。
     */
    static ExamProfileDto of(ExamProfile profile) {
        if (profile == null) {
            return EMPTY;
        }
        return new ExamProfileDto(
                profile.examType(),
                profile.examDate() == null ? null : profile.examDate().toString());
    }

    /**
     * 🔴 未定义字段一律拒绝 —— 与 {@code AssertionRequest} 同一道锁,<b>不依赖任何配置</b>。
     *
     * <p>在这个端点上它挡的是一类很具体的东西:有人给 {@code PUT} 顺手加一个
     * 一个「已跳过」/「已提示过」之类的标记位。§5.4 逐字裁定
     * <b>契约上没有「已跳过」这个状态位</b>(「跳过」根本不调 {@code PUT}),
     * 而少了这道锁,那个键会被静默忽略然后返回 200,<b>两边都以为它生效了</b>。
     *
     * <p><b>{@code value} 收下就丢</b>:它是用户送来的原文,异常里只带字段名。
     */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
