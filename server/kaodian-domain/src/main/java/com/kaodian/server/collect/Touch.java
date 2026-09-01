package com.kaodian.server.collect;

import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 行为层的一条触达记录 —— 「你碰过这个考点」。
 *
 * <h2>🔴 这个类里没有、也永远不会有存放学习内容的字段</h2>
 *
 * 没有 {@code content}、没有 {@code text}、没有 {@code question}、没有 {@code transcript}、
 * 没有 {@code imageUrl}。这不是「暂时不填」,是<b>结构上没有这个位置</b>。
 * <p>
 * 依据 决策记录 §2.2「不碰内容」:机构的课程内容一概不存,只记<b>来源名与时间戳</b>。
 * 只要这条记录的形状里没有能装下内容的字段,即便以后有人想存也无处可放 —— 这是
 * docs/technical/INDEX.md §5.1「不是不填,是不建这个列」在代码层的形态。
 * <p>
 * 语音转写的原文与拍照的原图都<b>不进入</b>这条记录:转写文本只用于识别考点,
 * 用完即弃;原图 base64 内联送识别一次后即删,不做云端存储(决策记录 §2.3 / docs/data/识别链路选型.md 坑二)。
 * 识别的产物只有一个 —— {@link #nodeCode()},即考点树里的一个节点。
 *
 * <h2>{@code clientToken} 为什么在这条记录上,而不只在请求体上</h2>
 *
 * 它是<b>去重键</b>,而去重要在落地那一侧才算数(见 {@link TouchStore#append})。
 * 只写在请求体上、落库时丢掉,等于「这一次请求没重复」——而离线队列补传
 * (docs/execution/INDEX.md {@code R-32} 的防线)重发的是<b>另一次请求</b>:进程重启、换台设备、
 * 队列里那一条又轮到一次,都会绕过任何只存在于内存里的判重。
 * <p>
 * 🔴 它是<b>标识,不是内容</b>。上限见 {@link #MAX_CLIENT_TOKEN_LENGTH}。
 *
 * @param id          记录 id
 * @param nodeCode    挂到哪个考点。🔴 只接受考点树里已存在的 code,不接受自由文本标签(R-07)
 * @param sourceName  来源名,如「粉笔 · 资料分析系统班 L12」。只是个名字,不含该来源的任何内容
 * @param kind        这一笔是怎么记的
 * @param occurredAt  发生时间 —— 「多久前」的唯一依据
 * @param drill       做题记录;非做题类记录为 null
 * @param clientToken 客户端生成的去重键;<b>在线直接记时可以为 null</b>(见六参构造器)
 */
public record Touch(
        String id,
        String nodeCode,
        String sourceName,
        TouchKind kind,
        Instant occurredAt,
        Drill drill,

        @Size(max = Touch.MAX_CLIENT_TOKEN_LENGTH)
        String clientToken
) {

    /**
     * 去重键的长度上限 —— <b>整个仓库只有这一个数</b>。
     *
     * <h2>为什么常量放在这里,而不是放在请求体那一侧</h2>
     *
     * {@code CreateRecordRequest.sourceName} 的 60、{@code nodeCode} 的 64 都写在请求体上,
     * 因为它们的上限属于<b>边界</b>({@code NoStemFieldTest.Reason.BOUNDED_UPSTREAM}:
     * 「在下游再写一遍上限,会出现两个数,而两个数迟早对不上」)。
     * <p>
     * 去重键不一样:它<b>同时是请求体的字段、领域记录的字段、和落盘 JSON 的一个键</b>,
     * 三处都得说得出上限。所以这里不是「写了三遍」,是<b>三处引用同一个常量</b> ——
     * 编译期就保证只有一个数,想改只能改这一行。
     *
     * <h2>为什么是 64</h2>
     *
     * 客户端能生成的去重键无非 UUID(36)、时间戳+序号、或一段 hash(SHA-256 hex 是 64)。
     * 64 装得下其中最长的那个,而<b>装不下任何一道题的题干</b>(R-01)——
     * 这个数字的作用不是精确,是把「放个 id」和「放段内容」分在两边。
     */
    public static final int MAX_CLIENT_TOKEN_LENGTH = 64;

    /**
     * 没有去重键的那条路 —— <b>在线直接记</b>。
     *
     * <p>留这个构造器不是图省事:在线记一笔时客户端<b>不需要</b>去重键(请求成功与否当场就知道),
     * 强迫它编一个只会让「这个字段可以是任何东西」变成事实。
     * 需要去重键的只有一条路 —— 断网时进了本地队列、之后要补传的那些记录。
     */
    public Touch(String id, String nodeCode, String sourceName,
                 TouchKind kind, Instant occurredAt, Drill drill) {
        this(id, nodeCode, sourceName, kind, occurredAt, drill, null);
    }

    /**
     * 做题记录 —— 练了几道、对了几道。
     *
     * <h2>这两个数是用户自己填的,不是产品判的</h2>
     *
     * 决策记录 §2.2 的能力边界是「只说有没有、几次、多久前,不判断对不对」。
     * 记录一个<b>用户自己输入的数字</b>属于「几次」;
     * 而去判断某道题答得对不对,属于「对不对」—— 后者本产品永不做。
     * <p>
     * 所以这里既没有判题逻辑,也没有标准答案,更没有任何模型参与。
     * 就是把用户敲进来的两个整数存下来。
     *
     * @param practiced 练了几道
     * @param correct   用户自己说对了几道
     */
    public record Drill(int practiced, int correct) {
        public Drill {
            if (practiced < 0 || correct < 0) {
                throw new IllegalArgumentException("题数不能为负");
            }
            if (correct > practiced) {
                throw new IllegalArgumentException("对的题数不能多于练的题数:" + correct + " > " + practiced);
            }
        }
    }

    public Touch {
        if (nodeCode == null || nodeCode.isBlank()) {
            throw new IllegalArgumentException("必须挂到一个考点上");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("必须有时间戳 —— 「多久前」全靠它");
        }

        // 🔴 空白串必须当成「没有去重键」,不能原样留着。
        // 留着的话,两条本来毫不相干、都没填去重键的记录会在 TouchStore#append 里互相判重 ——
        // 后一条被当成前一条的重传,直接不落库。判重的失败方向只能是「多一条」,不能是「少一条」:
        // 多一条用户看得见、删得掉,少一条是他记了却没记上,而他不会知道。
        clientToken = clientToken == null || clientToken.isBlank() ? null : clientToken;

        // @Size 在这条记录上不参与校验(Touch 从不过 Validator),真正生效的是这一句。
        // 两者引用同一个常量,所以不会出现「注解说 64、代码放 200」这种对不上的情况。
        if (clientToken != null && clientToken.length() > MAX_CLIENT_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "去重键最长 " + MAX_CLIENT_TOKEN_LENGTH + " 个字符 —— 它是个 id,不是放内容的地方");
        }
    }

    /** 这一笔是否包含做题。仅接触(听课/看讲义)没有做题数据。 */
    public boolean hasDrill() {
        return drill != null && drill.practiced() > 0;
    }
}
