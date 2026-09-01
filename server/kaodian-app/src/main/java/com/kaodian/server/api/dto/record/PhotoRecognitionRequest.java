package com.kaodian.server.api.dto.record;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /records/{id}/image} 的请求体 —— <b>除了图片字节,一个字段都没有。</b>
 *
 * <h2>🔴 为什么这个端点是 JSON + base64,而音频那个是 multipart(两者形态不同是刻意的)</h2>
 *
 * docs/technical/INDEX.md §6.2 对这两行的原文<b>逐字不同</b>:
 * <ul>
 *   <li>音频:「multipart,≤60s;转写完成后<b>服务端不留存音频</b>;失败提示重录」</li>
 *   <li>图片:「🔴 <b>JSON body,base64 内联,不是 multipart 落盘。</b>
 *       单次 ≤6 张(连拍合并)。见 §八」</li>
 * </ul>
 * 「不是 multipart <b>落盘</b>」这五个字就是全部理由。servlet 容器处理 multipart 的默认行为是
 * <b>把每个 part 写成一个临时文件</b>({@code file-size-threshold} 默认 0 = 一律落盘),
 * 而 docs/technical/INDEX.md §8.1 禁令 2 是「服务端不写磁盘、不进对象存储、不建图片桶」——
 * <b>选 multipart 等于把红线的成立与否交给一个容器默认值</b>。
 * JSON body 走的是 Jackson 的字符流,没有任何一层会替你把它写到盘上。
 * <p>
 * 音频那边之所以还能用 multipart,是因为契约就是那么写的,而它靠的是
 * <b>显式把 {@code spring.servlet.multipart.file-size-threshold} 抬到上限之上</b>
 * (见 {@code application.properties} 与 {@code AudioRetentionTest})——
 * 那是一条<b>配置</b>层的保证,比这里的<b>形态</b>层保证弱一档。
 * <p>
 * ⚠️ <b>所以千万不要「统一」这两个端点的形态。</b> 把图片改成 multipart 是最自然的重构
 * (「两个上传接口为什么长得不一样?」),而它正好会踩 R-04 ——
 * 那条红线在 docs/execution/INDEX.md §四 上标着「<b>第一天不定就改不回来</b>」。
 * 反过来把音频改成 JSON+base64 倒是安全的,只是契约没那么写。
 *
 * <h2>🔴 base64 在这里<b>不是</b>一个 {@code String} 字段,这一点是被红线逼出来的</h2>
 *
 * 最自然的写法是 {@code List<String> photos} 再自己 {@code Base64.getDecoder().decode(...)}。
 * 那样写有两个问题,而第二个是致命的:
 * <ol>
 *   <li>base64 字符串<b>天然是超长文本</b>,而 {@code NoStemFieldTest} 要求
 *       {@code api.dto} 下的每一个 {@code String} 位置要么有 {@code @Size(max ≤ 200)},
 *       要么在白名单里挑一个理由。一张图的 base64 是几十万字符,
 *       <b>两条路都走不通</b> —— 唯一的出路是往 {@code KNOWN_GAP} 里塞一行,
 *       而那一档明确写着「不接受新增」</li>
 *   <li>更要紧的:一个能装下几十万字符的 {@code String} 字段,<b>就是 R-01 说的那种「预留位」</b>。
 *       它今天装 base64,明天装什么没有任何结构拦得住</li>
 * </ol>
 * 声明成 {@code byte[]} 之后,base64 的解码由 Jackson 在<b>反序列化时一次完成</b>
 * (JDK/Jackson 自带,不引入任何依赖),进程里从来不存在一个属于我们的 base64 {@code String} 对象。
 * <b>R-01 的那条断言因此一个字都不用改</b>:{@code byte[]} 不是文本,它压根不进那条扫描。
 *
 * <h2>🔴 还有第三条,而且是实测出来的:{@code String} 会让 <b>Spring 自己</b>把 base64 打进日志</h2>
 *
 * {@code RequestResponseBodyMethodProcessor} 在 <b>TRACE</b> 级别会打一句
 * {@code Read "application/json" to [<反序列化出来的对象>]} —— 也就是把整个请求体对象
 * {@code toString()} 出来。
 * <ul>
 *   <li>{@code byte[]}:record 的 {@code toString()} 打出来的是 {@code [B@4860627a},
 *       <b>一个身份哈希,零字节内容</b></li>
 *   <li>{@code String}:<b>整段 base64 原样进日志</b>。2026-08-27 把这个字段临时改成
 *       {@code List<String>} 实测过一次,{@code RecognitionApiTest#imageBytesNeverReachAnyLogLevel}
 *       当场红,报的 logger 就是上面那个</li>
 * </ul>
 * 这条最要紧的地方在于:<b>它不在我们写的任何一行代码里</b>。
 * docs/technical/INDEX.md §8.1 禁令 3 说的是「不把 base64 打进日志的任何级别」,而这一次打日志的是框架 ——
 * 源码扫描扫不到,code review 看不见,只有把级别调到 TRACE 跑一次才看得出来。
 * <b>选 {@code byte[]} 不是风格偏好,是它让这句框架日志无害。</b>
 *
 * <h2>而这个选择是被钉住的,不是靠这段注释维持</h2>
 *
 * 哪天有人把它改回 {@code List<String>},<b>两条断言同时红</b>:
 * {@code NoStemFieldTest#everyFreeTextFieldHasACeiling}(那个位置既没有 {@code @Size},
 * 也不在白名单里)与 {@code RecognitionApiTest#imageBytesNeverReachAnyLogLevel}(上面那句框架日志)。
 *
 * <h2>为什么还有一个 {@code @JsonAnySetter}</h2>
 *
 * 与 {@link SuggestTagRequest} / {@link MountTagRequest} 同一道锁:请求体里<b>不接受任何自由文本标签</b>
 * (R-07)。少了它,{@code {"photos":[...],"tag":"我自己起的考点"}} 在
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} 被人关掉的那天会静默通过。
 *
 * @param photos 每个元素是一张图的<b>原始字节</b>(线上是一段纯 base64,不带 {@code data:} 前缀)。
 *               单次 ≤ {@link #MAX_PHOTOS} 张 —— docs/technical/INDEX.md §6.2「单次 ≤6 张(连拍合并,{@code 1.1.2.3})」
 */
public record PhotoRecognitionRequest(

        @NotEmpty(message = "至少要有一张图")
        @Size(max = MAX_PHOTOS, message = "单次最多 6 张 —— 连拍合并成一条记录(1.1.2.3)")
        List<
                @NotNull(message = "图片不能是 null")
                @Size(max = MAX_PHOTO_BYTES, message = "单张图最大 4 MiB")
                byte[]> photos
) {

    /**
     * 单次最多几张 —— docs/technical/INDEX.md §6.2 逐字:「单次 ≤6 张(连拍合并,{@code 1.1.2.3})」。
     *
     * <p>{@code 1.1.2.3} 的场景是「听课连续截图」:6 张是<b>同一份材料的多张</b>,
     * 合并成<b>一条</b>记录,不是 6 条。上限的作用不只是省钱 ——
     * base64 内联下每多一张,请求体就多几百 KB,而 docs/technical/INDEX.md §8.1 禁令 5 说得很清楚:
     * <b>反代可能背着你把超出缓冲区的请求体落盘</b>。请求体越小,那条最隐蔽的破口越窄。
     */
    public static final int MAX_PHOTOS = 6;

    /**
     * 单张图的字节上限。
     *
     * <p>4 MiB 装得下任何一张手机截图或拍照(压缩后通常 0.5–2 MB)。
     * 它拦的不是「图太大」,是<b>「有人拿这个端点传一个别的东西」</b> ——
     * 一个装得下任意大小字节的入口,和一个对象存储桶之间只差一个 {@code Files.write}。
     */
    public static final int MAX_PHOTO_BYTES = 4 * 1024 * 1024;

    /**
     * 六张加起来的字节上限。
     *
     * <p>单张上限乘六是 24 MiB,而实际上 6 张都顶满是不会发生的场景。
     * 12 MiB 是<b>一次请求允许在服务端内存里同时存在的原图总量</b> ——
     * 这些字节<b>只在内存里过一次</b>,所以它同时也是这个端点的内存预算。
     * base64 会让线上请求体再涨约 4/3。
     */
    public static final int MAX_TOTAL_BYTES = 12 * 1024 * 1024;

    /**
     * 六张加起来别超预算。
     *
     * <p>单张上限管不住这件事:6 × 4 MiB 每一张都合法,加起来 24 MiB。
     * 校验放在请求体上而不是控制器里,理由与 {@code CreateRecordRequest#isDrillPairComplete} 相同 ——
     * <b>规则只写在形状上一处</b>,控制器不再抄一遍。
     */
    @AssertTrue(message = "六张图加起来最大 12 MiB —— 原图只在内存里过一次,这也是这次请求的内存预算")
    public boolean isWithinTotalBudget() {
        if (photos == null) {
            return true;        // 空的那件事由 @NotEmpty 说,不在这里说第二遍
        }
        long total = 0;
        for (byte[] photo : photos) {
            if (photo != null) {
                total += photo.length;
            }
        }
        return total <= MAX_TOTAL_BYTES;
    }

    /**
     * 🔴 R-07 的第二道锁 —— 未定义字段一律拒绝,<b>与 ObjectMapper 怎么配置无关</b>。
     *
     * <p><b>{@code value} 收下就丢</b>:它是用户送来的原文,可能就是一整段题干,
     * 也可能就是那张图的 base64。异常里只带字段名(决策记录 §2.2 不碰内容 / docs/technical/INDEX.md §8.1 禁令 3)。
     */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
