package com.kaodian.server.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchKind;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 「记一笔」的请求体。<b>整个系统里唯一一处用户能往里写东西的地方</b>,所以红线全压在这五个字段上。
 *
 * <h2>🔴 一:只接受 nodeCode,没有 name / label / tag</h2>
 *
 * 这是 R-07 在接口层的实现(docs/10 §6.3):<b>只要 API 上没有传入自由文本标签的通道,
 * 自由生成的考点就进不了库 —— 无论模型输出什么。</b>
 * 光是「不定义这些字段」还不够,默认配置下 Jackson 会安静地忽略多余字段,
 * 于是 {@code {"tag":"我自己想的考点"}} 也返回 200,双方都以为它生效了。
 * <p>
 * 所以配了 {@code FAIL_ON_UNKNOWN_PROPERTIES=true}。但那是<b>一行配置</b>,
 * 而红线不能只有一行配置撑着:任何人以「统一 JSON 行为」为由关掉它,这条线就没了,
 * 而且没有任何测试会红。{@code @JsonIgnoreProperties(ignoreUnknown = false)} 顶不上这一位 ——
 * 它是<b>默认值,等于什么都没写</b>:「不忽略」之后要不要失败,仍旧由那行配置决定。
 * <p>
 * 真正的第二道锁是下面的 {@link #rejectUnknownField} —— 它<b>与 ObjectMapper 怎么配置无关</b>,
 * 未定义字段一律抛 {@link UnknownFieldException}。冗余是有意的:一道锁失效不该导致整条线失守。
 *
 * <h2>🔴 二:没有任何能装下课程内容的字段</h2>
 *
 * 没有 {@code content}、{@code text}、{@code question}、{@code transcript}、{@code imageUrl}。
 * {@link com.kaodian.server.collect.Touch} 结构上就没有这些位置,请求体自然也不能有 ——
 * 否则就变成「接口收了但存不下」,而下一步一定是有人去给 Touch 加字段。
 *
 * <h2>🔴 三:{@code sourceName} 有长度上限,这个上限是防内容夹带的</h2>
 *
 * 「粉笔 · 资料分析系统班 L12」二十来个字。留 60 个字符是宽裕的,
 * 而它同时挡住了把一整段题干塞进「来源名」这条最省事的绕路。
 *
 * <h2>为什么没有 occurredAt</h2>
 *
 * 时间戳由服务端按 {@code Clock} 打。「多久前」是五态里唯一的时间依据,
 * 让客户端自报会让「生疏」变成一个可以被随手改掉的状态。补录历史记录是另一件事,
 * 要做的时候单开端点,不在这里开口子。
 *
 * <h2>{@code clientToken} 是第六个字段,而它没有破上面那三道锁</h2>
 *
 * 它是<b>客户端生成的去重键</b>,docs/10 §6.2 的「{@code client_token} 幂等」。
 * 加一个字段进来必须先回答「它会不会变成放内容的地方」,答案是不会 ——
 * 它有上限({@link Touch#MAX_CLIENT_TOKEN_LENGTH} = 64),而 64 装不下任何一道题的题干。
 * <b>这正是 R-01 想要的形状:不是靠约定它只放 id,是靠它放不下别的。</b>
 * <p>
 * 可空,因为在线直接记的那条路不需要它:请求成败当场就知道。
 * 需要它的只有断网时进了本地队列、之后走 {@code POST /records/batch} 补传的那些
 * ——那条路上它是<b>必填</b>(见 {@link BatchCreateRecordsRequest})。
 *
 * @param kind        怎么记的。手动三种永远可用 —— 额度用尽 ≠ 记不了(docs/11 §二)
 * @param sourceName  来源名。<b>只是个名字,不含该来源的任何内容</b>
 * @param nodeCode    挂到哪个考点。必须是骨架树里已存在的 code
 * @param practiced   练了几道,可空。<b>用户自己填的数</b>
 * @param correct     对了几道,可空。同上 —— 产品从不判题(01 §2.2)
 * @param clientToken 去重键,可空。同一个键重复提交<b>返回原来那条,不新建</b>
 */
public record CreateRecordRequest(

        @NotNull(message = "必须说明这一笔是怎么记的")
        TouchKind kind,

        @NotBlank(message = "必须给出来源名")
        @Size(max = 60, message = "来源名最长 60 个字符 —— 它是个名字,不是放内容的地方")
        String sourceName,

        @NotBlank(message = "必须挂到一个考点上")
        @Size(max = 64, message = "考点 code 最长 64 个字符")
        String nodeCode,

        @Min(value = 0, message = "题数不能为负")
        @Max(value = 1000, message = "单条记录的题数不超过 1000")
        Integer practiced,

        @Min(value = 0, message = "题数不能为负")
        @Max(value = 1000, message = "单条记录的题数不超过 1000")
        Integer correct,

        @Size(max = Touch.MAX_CLIENT_TOKEN_LENGTH,
                message = "去重键最长 64 个字符 —— 它是个 id,不是放内容的地方")
        String clientToken
) {

    /**
     * 两个数要么都给,要么都不给。
     *
     * <p>只给 {@code practiced} 不给 {@code correct} 时,把 correct 默认成 0 会凭空造出一个
     * 「全错」的记录,而那正好会把考点判成「弱」—— <b>产品替用户填了一个它没资格填的数</b>。
     * 宁可拒绝这次请求。
     */
    @AssertTrue(message = "practiced 与 correct 必须同时给出或同时省略")
    public boolean isDrillPairComplete() {
        return (practiced == null) == (correct == null);
    }

    /** 与 {@code Touch.Drill} 的构造器同一条规则,前移到校验层只是为了给出更清楚的报错。 */
    @AssertTrue(message = "对的题数不能多于练的题数")
    public boolean isCorrectWithinPracticed() {
        return practiced == null || correct == null || correct <= practiced;
    }

    /**
     * 🔴 R-07 的第二道锁 —— <b>未定义字段一律拒绝,而且不依赖任何配置。</b>
     *
     * <p>Jackson 把所有对不上号的键都送到这里。原来这一位是
     * {@code @JsonIgnoreProperties(ignoreUnknown = false)},但那是默认值、是个空操作:
     * 关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES} 之后 {@code {"tag":"我自己想的考点"}}
     * 会照样返回 201。换成这个方法之后,那条路无论配置怎么改都是 400。
     *
     * <p><b>参数 {@code value} 收下就丢,一个字都不许流出去</b> ——
     * 它是用户送来的原文,可能就是一整段题干。异常里只带字段名(01 §2.2 不碰内容)。
     */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
