package com.kaodian.server.api.dto.record;

import com.kaodian.server.api.dto.common.ApiError;
import com.kaodian.server.api.dto.common.TimelineItemDto;
import com.kaodian.server.collect.Touch;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 离线队列补传的回执 —— <b>逐条一个结果,不是一个整批的成败</b>。
 *
 * <h2>🔴 部分成功是这个端点的正常状态,不是异常</h2>
 *
 * 最省事的写法是「一条不合法 → 整批回滚 → 400」。在补传这个场景下它是错的:
 * 用户断网记了一整天,回到线上,队列里第 17 条挂着一个他自己后来删掉的考点 ——
 * <b>整批回滚等于那一天白记了</b>,而且客户端拿到一个 400 之后除了重试没有第二个动作,
 * 重试还会再撞同一条,队列永远吐不完。
 * <p>
 * docs/08 §1.3.7.1「记录动作本身永不失败」在这条路上的形态就是:
 * <b>能落的先落,落不下的逐条说清为什么。</b>
 *
 * <h2>为什么不用 207 Multi-Status</h2>
 *
 * 207 是 WebDAV 的东西,前端库对它的处理五花八门,而且它把「这一批里有失败」这件事
 * 塞进了状态码 —— 客户端仍然要读响应体才知道是哪几条。既然非读不可,
 * <b>状态码就该只回答一个问题:这批请求本身处理了没有</b>。
 * 处理了就是 200,哪几条没落在体里说。
 * <p>
 * 整批 400 只留给<b>请求本身不成立</b>的情况:超过 50 条、空批、JSON 解析不了、
 * 出现未定义字段(R-07)。那些不是「某一条数据有问题」,是调用方发错了东西。
 *
 * <h2>三个计数是给客户端清队列用的,不是给人看的</h2>
 *
 * 客户端要决定「本地队列里哪几条可以删了」。{@code stored + duplicated} 是可以删的,
 * {@code failed} 那些不能删 —— 但也<b>不该无脑重试</b>:{@link ItemResult#error()} 里的
 * {@code code} 说的就是重试有没有意义(考点没了 = 重试一万次也一样,要用户处理)。
 *
 * @param submitted  这一批送来几条
 * @param stored     真正新落库几条
 * @param duplicated 命中去重键、原样返回旧记录的有几条。<b>这不是失败</b>
 * @param failed     没落库的有几条
 * @param results    逐条结果,顺序与请求体里的顺序一致
 */
public record BatchCreateRecordsResponse(
        int submitted,
        int stored,
        int duplicated,
        int failed,
        List<ItemResult> results
) {

    /**
     * 一条的下场。
     *
     * <h2>为什么这个枚举没有中文 label</h2>
     *
     * 这个仓库的惯例是「枚举名 + 中文 label 一起给,前端不硬编码中文」
     * (见 {@code NodeDto#stateLabel}、{@code TimelineItemDto#kindLabel})。这里是例外,理由具体:
     * <b>成功与重复在界面上不需要文案</b> —— 补传是后台动作,用户看到的是队列少了几条,
     * 不是一行「已重复」。而失败那条的文案已经在 {@link ItemResult#error()} 的
     * {@code message} 里了,再给一个 label 就是<b>同一句话的第二处措辞</b>,两处迟早对不上。
     */
    public enum Status {
        /** 新落库了。 */
        STORED,
        /** 命中去重键,返回的是<b>之前那条</b>——id 与 occurredAt 都是第一次的。 */
        DUPLICATE,
        /** 没落库,原因见 {@link ItemResult#error()}。 */
        FAILED
    }

    /**
     * 逐条结果。
     *
     * <h2>失败为什么复用 {@link ApiError} 而不是自己造两个字段</h2>
     *
     * 一条补传失败和一次 {@code POST /records} 失败是同一件事,只是被裹在批里。
     * 复用同一个形状,客户端处理错误的那段代码就只有一份;而且 {@code traceId} 跟着来了 ——
     * 用户报「我有几条没传上去」时,那一串是唯一能捞到日志的东西。
     *
     * <p>🔴 {@code message} 的取值只可能是<b>我们自己写的中文字面量</b>
     * ({@code CaptureService.Rejection#label} 或校验注解上的那句话),
     * <b>绝不把客户端送来的值拼进去</b>。这条路是批量的,一次能带 50 段用户输入,
     * 原样回声等于把它们一起写进响应体和访问日志 —— 与 {@code ApiExceptionHandler}
     * 开头那条纪律同源,只是这里的放大倍数是 50。
     *
     * @param index       在请求体 {@code records} 数组里的下标。客户端凭它对回自己队列里的那一条 ——
     *                    <b>不能用 clientToken 对</b>:缺 token 正是失败原因之一,那时它是 null
     * @param clientToken 原样回显,方便客户端核对;缺失时为 {@code null}
     * @param status      落没落
     * @param record      落下的那条(或命中去重键时<b>原来那条</b>);失败时为 {@code null}
     * @param error       失败原因;成功时为 {@code null}
     */
    public record ItemResult(
            int index,

            @Size(max = Touch.MAX_CLIENT_TOKEN_LENGTH)
            String clientToken,

            Status status,
            TimelineItemDto record,
            ApiError error
    ) {

        public static ItemResult stored(int index, String clientToken, TimelineItemDto record) {
            return new ItemResult(index, clientToken, Status.STORED, record, null);
        }

        public static ItemResult duplicate(int index, String clientToken, TimelineItemDto record) {
            return new ItemResult(index, clientToken, Status.DUPLICATE, record, null);
        }

        public static ItemResult failed(int index, String clientToken, ApiError error) {
            return new ItemResult(index, clientToken, Status.FAILED, null, error);
        }
    }

    /** 从逐条结果算三个计数 —— <b>计数不许由调用方自己数</b>,那会出现和 results 对不上的数。 */
    public static BatchCreateRecordsResponse of(List<ItemResult> results) {
        int stored = (int) results.stream().filter(r -> r.status() == Status.STORED).count();
        int duplicated = (int) results.stream().filter(r -> r.status() == Status.DUPLICATE).count();
        int failed = (int) results.stream().filter(r -> r.status() == Status.FAILED).count();
        return new BatchCreateRecordsResponse(results.size(), stored, duplicated, failed, results);
    }
}
