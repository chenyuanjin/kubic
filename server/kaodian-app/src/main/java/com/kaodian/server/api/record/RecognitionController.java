package com.kaodian.server.api.record;

import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.dto.record.AudioRecognitionResponse;
import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.record.PhotoRecognitionRequest;
import com.kaodian.server.api.dto.record.SuggestTagResponse;
import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.api.dto.record.TagDto;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.api.dto.record.AudioRecognitionResponse;
import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.record.PhotoRecognitionRequest;
import com.kaodian.server.api.dto.record.SuggestTagResponse;
import com.kaodian.server.api.dto.common.SummaryDto;
import com.kaodian.server.api.dto.record.TagDto;
import com.kaodian.server.collect.RecordTag;
import com.kaodian.server.collect.TaggingService;
import com.kaodian.server.collect.TaggingService.Outcome;
import com.kaodian.server.collect.TaggingService.Suggestion;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import com.kaodian.server.recognize.AsrClient;
import com.kaodian.server.recognize.RecognitionUnavailableException;
import com.kaodian.server.syllabus.Syllabus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 两个上传端点 —— docs/technical/INDEX.md §6.2 采集那张表最后缺的两行。
 *
 * <h2>🔴 这两个端点是 R-04 的落点,功能是顺带的</h2>
 *
 * R-04 在 docs/execution/INDEX.md §四 上标着「<b>第一天不定就改不回来</b>」。这里所有看起来啰嗦的地方
 * ——形态不统一、不打一行日志、宁可拒收也不宽容——都是为了那一条,不是为了这两个功能好用。
 *
 * <h2>🔴 两个端点<b>形态不同,而且是刻意的</b></h2>
 *
 * <table border="1">
 *   <caption>docs/technical/INDEX.md §6.2 的原文,逐字不同</caption>
 *   <tr><th>端点</th><th>契约原文</th><th>落地形态</th></tr>
 *   <tr><td>{@code POST /records/{id}/audio}</td>
 *       <td>「multipart,<b>≤60s</b>({@code 1.1.1.4});转写完成后<b>服务端不留存音频</b>;失败提示重录」</td>
 *       <td>multipart,{@link MultipartFile}</td></tr>
 *   <tr><td>{@code POST /records/{id}/image}</td>
 *       <td>「🔴 <b>JSON body,base64 内联,不是 multipart 落盘。</b>
 *           单次 ≤6 张(连拍合并,{@code 1.1.2.3})。见 §八」</td>
 *       <td>JSON body,{@code byte[]}(base64 由 Jackson 在反序列化时解掉)</td></tr>
 * </table>
 *
 * <b>「不是 multipart <b>落盘</b>」这五个字是全部理由。</b> servlet 容器处理 multipart 的默认行为是
 * 把每个 part 写成一个临时文件({@code spring.servlet.multipart.file-size-threshold} 默认 {@code 0}
 * = 一律落盘),而 docs/technical/INDEX.md §8.1 禁令 2 是「<b>服务端不写磁盘、不进对象存储、不建图片桶</b>」。
 * 图片走 multipart 就等于把 R-04 的成立与否<b>交给一个容器默认值</b> ——
 * 它不在你写的代码里,不会报错,也不会出现在任何 review 里(§8.1 禁令 5 说的是同一类破口)。
 * <p>
 * 音频这一侧还能用 multipart,是因为契约就是那么写的;它靠的是
 * {@code application.properties} 里<b>显式把 {@code file-size-threshold} 抬到上限之上</b>,
 * 由 {@code AudioRetentionTest} 钉住。那是<b>配置层</b>的保证,比图片那一侧的<b>形态层</b>保证弱一档
 * (docs/technical/后端系统设计与组件接入.md §七 的分级表)。⚠️ <b>不要把两者「统一」成同一种</b> ——
 * 统一的方向如果是「图片也改成 multipart」,那一次重构会直接踩线。
 *
 * <h2>🔴 这个文件里一行日志都没有</h2>
 *
 * docs/technical/INDEX.md §8.1 禁令 3:「<b>不把 base64 打进日志的任何级别</b> —— 一次
 * {@code log.debug(request)} 就等于把原图落了盘,而且落在最不容易想到的地方。」
 * 所以这里连一个 {@code Logger} 字段都不声明:没有那个字段,就没有那一行。
 * {@code ImageRetentionTest#byteHandlingCallersNeverPersistBytes} 把这件事做成了机械校验 ——
 * 它连 {@code Path}、{@code OutputStream} 这类词都不放行。
 *
 * <h2>🔴 声明的 {@code Content-Type} 一概不信,判据是文件头本身</h2>
 *
 * 两条路上的格式判定都由服务端自己从字节里认({@link #imageMimeOf} / {@link #wavSeconds}),
 * 客户端声明的类型<b>连读都不读</b>。理由很直接:这两个端点的全部要点就是
 * <b>不靠客户端说的话</b> —— ≤60s 如果只信客户端自报的时长,那它就不是一条服务端校验。
 *
 * <h2>识别失败 ≠ 记录失败</h2>
 *
 * docs/technical/后端系统设计与组件接入.md §1.5:「<b>降级方向是『少功能』,不是『少记录』</b>」。
 * 这两个端点作用在一条<b>已经落地的记录</b>上,所以它们无论怎么失败,
 * 那条记录都完好、都还能手动挂考点({@code POST /records/{id}/tags})。
 * 于是识别侧的全部结局都是 200 —— 与 {@code TagController#suggest} 同一条理由:
 * 回 503 会让前端把它当成一次失败去重试,而它没有失败。
 * <p>
 * 只有<b>请求本身不合法</b>(图太多、不是 WAV、超过 60 秒)才是 4xx:那是调用方发错了东西,
 * 重发同样的内容还是错。
 */
@RestController
@RequestMapping("/api/records/{id}")
public class RecognitionController {

    private final TaggingService tagging;
    private final CoverageReader reader;

    /**
     * 语音出口。<b>这里注入的是接口,不是某一家厂商</b> —— docs/data/识别链路选型.md 坑三要的那个切换点。
     *
     * <p>默认装配到 {@code StubAsrClient},它诚实地抛
     * {@link RecognitionUnavailableException}(「还没接入」),于是这个端点今天唯一走得通的
     * 结局就是契约里写的那句「<b>失败提示重录</b>」。
     */
    private final AsrClient asr;

    public RecognitionController(TaggingService tagging, CoverageReader reader, AsrClient asr) {
        this.tagging = tagging;
        this.reader = reader;
        this.asr = asr;
    }

    // ================================================================ 图片

    /**
     * 上传图片识别 —— docs/technical/INDEX.md §6.2「🔴 JSON body,base64 内联,不是 multipart 落盘。单次 ≤6 张」。
     *
     * <h2>🔴 这里不重写管线,只是把字节递进去</h2>
     *
     * 打标的四段(候选召回 → 闭集分类 → 阈值裁决 → 出口自检)全在
     * {@link TaggingService#suggest} 里,而阈值裁决与出口自检又是<b>接口层的静态方法</b>
     * ({@code RecognitionResult.of} / {@code VisionTagger.enforceClosedSet})。
     * 在这一层补任何一句「顺手放宽一点」的判断,等于把红线从接口层搬到控制器里。
     * <p>
     * {@code TaggingService.suggest} 的类注释里那句「带着字节走完四段的路<b>是实现好的</b>,
     * 只是还没有 HTTP 入口」—— <b>这个方法就是那个入口</b>。
     *
     * <h2>连拍多张怎么送:命中即停</h2>
     *
     * {@code 1.1.2.3} 的场景是「听课连续截图」——6 张是<b>同一份材料的多张</b>,
     * 合并成<b>一条</b>记录。所以这里不是「6 张各挂一个考点」,而是逐张送、<b>挂上一个就停</b>:
     * <table border="1">
     *   <caption>逐张送到哪一步就停</caption>
     *   <tr><th>这一张的结局</th><th>继续送下一张吗</th><th>为什么</th></tr>
     *   <tr><td>{@code SUGGESTED} / {@code ALREADY_TAGGED}</td><td>不</td>
     *       <td>已经挂上考点了。再送只是<b>再花一次模型钱</b>,还可能给同一条记录挂上第二个考点</td></tr>
     *   <tr><td>{@code NO_MATCH}</td><td><b>继续</b></td>
     *       <td>这张没认出来,下一张可能拍得更清楚 —— 连拍的全部意义就在这</td></tr>
     *   <tr><td>{@code NOT_RECALLED}</td><td>不</td>
     *       <td>召回只看来源名,与图无关。<b>换张图答案一模一样</b>,而且它压根没调模型</td></tr>
     *   <tr><td>{@code NO_MATERIAL}</td><td>不</td>
     *       <td>同上,与图无关(而且这一支在这条路上不该出现:我们手里明明有字节)</td></tr>
     *   <tr><td>{@code UNAVAILABLE}</td><td>不</td>
     *       <td>厂商挂了。再试 5 次只是再挨 5 次超时,用户多等 5 倍时间拿到同一句话</td></tr>
     * </table>
     *
     * <h2>🔴 字节的生命周期:进来、送一次、方法返回即释放</h2>
     *
     * 不落盘、不进对象存储、不建图片桶、不调厂商的 Files API、<b>不进任何级别的日志</b>
     * (docs/technical/INDEX.md §8.1 五条禁令 / docs/data/识别链路选型.md 坑二)。这个方法里没有任何请求日志,也没有任何
     * 把 {@code photos} 存到字段、缓存、静态 Map 里的写法 —— 它们全都是「短期留存」的另一种拼法。
     */
    @PostMapping("/image")
    public SuggestTagResponse recognizePhotos(@PathVariable String id,
                                             @Valid @RequestBody PhotoRecognitionRequest req) {
        Touch touch = requireRecord(id);
        List<byte[]> photos = req.photos();

        // 🔴 先把每一张都认一遍格式,再开始送模型。
        //    边认边送的话,第 3 张是个 zip 时前两张的模型钱已经花出去了 —— 而这次请求整个是 400。
        List<String> mimeTypes = new ArrayList<>(photos.size());
        for (int i = 0; i < photos.size(); i++) {
            mimeTypes.add(requireImageMime(photos.get(i), i));
        }

        Suggestion suggestion = null;
        for (int i = 0; i < photos.size(); i++) {
            suggestion = tagging.suggest(touch, photos.get(i), mimeTypes.get(i));
            if (suggestion.outcome() != Outcome.NO_MATCH) {
                break;      // 见上表:只有「这张没认出来」才值得换下一张
            }
        }
        return responseFor(touch, suggestion);
    }

    // ================================================================ 音频

    /**
     * 上传音频转写 —— docs/technical/INDEX.md §6.2「multipart,<b>≤60s</b>;转写完成后<b>服务端不留存音频</b>;失败提示重录」。
     *
     * <h2>🔴 「不留存音频」在这里有三道,少一道都不成立</h2>
     * <ol>
     *   <li><b>库里没有位置</b> —— docs/technical/INDEX.md §5.2「不建的表」逐字:「任何音频表 —— {@code 1.1.1.5}:
     *       ASR 失败提示重录,<b>不留存音频</b>」。{@link Touch} 里没有能装下它的字段</li>
     *   <li><b>容器不落临时文件</b> —— multipart 的默认行为是把 part 写成临时文件。
     *       {@code application.properties} 把 {@code file-size-threshold} 抬到 {@code max-file-size}
     *       之上,于是它整段留在内存里。<b>这一道是配置,所以由 {@code AudioRetentionTest} 钉住</b></li>
     *   <li><b>本方法不写任何地方</b> —— 字节读出来、送一次 ASR、方法返回即释放;
     *       转写文本同样用完即弃,<b>连响应体里都没有它的位置</b>
     *       ({@link AudioRecognitionResponse} 的类注释)</li>
     * </ol>
     *
     * <h2>🔴 ≤60s 是<b>服务端算出来的</b>,不是客户端自报的</h2>
     *
     * 「客户端传一个 {@code durationMillis} 上来,服务端校验 ≤60000」是最自然的写法,
     * 而它<b>不是一条服务端校验</b> —— 那个数是客户端说的,改一个字段就绕过去了。
     * <p>
     * 真正算得出时长的唯一办法是读格式头。所以这个端点<b>只收 PCM WAV</b>:
     * 它的 {@code fmt } 块里有 {@code byteRate},{@code data} 块里有字节数,
     * 两者一除就是<b>精确到毫秒的时长</b>,而且伪造不了 —— 改小了会让 ASR 读不出声音。
     * <p>
     * ⚠️ <b>代价是真实的,而且需要人裁一次</b>:压缩格式(m4a / mp3 / opus)算时长要解码,
     * 而解码要引入一个依赖(本轮硬约束:{@code pom.xml} 一个字不改)。
     * 于是压缩格式今天一律拒收,60 秒的 WAV 约 1.9 MB —— 移动网络下这不是免费的。
     * 两条出路(引一个解码依赖 / 接受「时长只能按字节数近似」)都得有人选,
     * <b>本轮选了保守的那条并报出来,没有替人做决定</b>。
     *
     * <h2>转写成功之后会发生什么:今天什么都不会发生 ⚪</h2>
     *
     * 「文字 → 考点」的闭集匹配属于打标管线({@code 总路线图 §1.2.5}),<b>那一段还没建</b> ——
     * 这句话是 {@link AsrClient} 的类注释里写着的,不是这里的推断。
     * {@code TaggingService.suggest} 的第 ② 段是 {@code VisionTagger}(图 → 考点),
     * 把一段音频或一段文字塞给它是<b>另一条管线</b>,不是这个端点该顺手建的东西。
     * <p>
     * 所以转写成功时的结局是 {@link AudioOutcome#NO_TEXT_TAGGER}:诚实地说明
     * 「转写好了,但这一段还没建,请自己从树里挑一个考点」,而<b>不是伪装成一次识别失败</b>。
     * 伪装的代价见 {@code StubAsrClient} 的类注释:假装成功比诚实失败危险得多。
     *
     * @param part 音频 part,名字是 {@code audio}。可以整个不传 —— 那时是一句明确的 400,
     *             而不是一个来自 Spring 的、没有错误码的通用拒绝
     */
    @PostMapping("/audio")
    public AudioRecognitionResponse transcribe(@PathVariable String id,
                                               @RequestPart(name = "audio", required = false)
                                               MultipartFile part) {
        requireRecord(id);      // 记录不在了就没必要读那段音频,更没必要送出去

        byte[] clip = requireAudioBytes(part);
        requireAtMostSixtySeconds(clip);

        String spoken;
        try {
            // 🔴 mimeType 用我们自己的常量,不是 part.getContentType() —— 那是客户端说的话,
            //    而上面那道校验刚刚亲自确认过这段字节就是 PCM WAV。
            spoken = asr.transcribe(clip, WAV_MIME);
        } catch (RecognitionUnavailableException e) {
            // 🔴 识别不可用 ≠ 记录失败(docs/execution/INDEX.md §1.3.7.1)。记录早就落地了,
            //    这里什么都不写、什么都不删。契约那句「失败提示重录」就落在这个分支上。
            //    异常本身不进日志:它的 message 里没有音频,但这个文件的纪律是一行日志都不写。
            return new AudioRecognitionResponse(AudioOutcome.UNAVAILABLE.name(),
                    "转写没跑成 —— 可以重录一次,也可以自己从树里挑一个考点。");
        }

        // 🔴 spoken 到此为止:不落库、不进响应体、不进日志。这三处都没有能装下它的位置,
        //    不是「记得别填」。下面这一行是它唯一被使用的地方 —— 只问「有没有内容」。
        if (spoken == null || spoken.isBlank()) {
            return new AudioRecognitionResponse(AudioOutcome.NOTHING_HEARD.name(),
                    "这段录音里没听出文字 —— 请重录一次。");
        }
        return new AudioRecognitionResponse(AudioOutcome.NO_TEXT_TAGGER.name(),
                "已转写,但「文字 → 考点」这一段还没建 —— 请自己从树里挑一个考点。转写文本不留存。");
    }

    /** 音频端点的三种结局。<b>三种在界面上该说的下一步完全不同,所以不能合成一个。</b> */
    public enum AudioOutcome {

        /** ASR 压根没跑成(没配密钥 / 超时 / 限流)。界面:<b>提示重录</b>,或自己从树里挑一个。 */
        UNAVAILABLE,

        /** 跑成了,但一个字都没转出来。界面:<b>提示重录</b> —— 与上面分开是因为重录能解决这个,解决不了上面那个。 */
        NOTHING_HEARD,

        /**
         * ⚪ 转写出来了,但「文字 → 考点」的闭集匹配那一段还没建({@code 总路线图 §1.2.5})。
         *
         * <p>界面:自己从树里挑一个。这一支<b>今天只有真实 ASR 接入后才走得到</b> ——
         * 它摆在这里是为了让那一天有人看见这句话,而不是让转写结果被悄悄丢掉。
         */
        NO_TEXT_TAGGER
    }

    // ================================================================ 图片:格式自认

    /** 送进模型的图片类型 —— <b>由服务端从字节里认出来</b>,不是客户端声明的。 */
    private static final String JPEG_MIME = "image/jpeg";
    private static final String PNG_MIME = "image/png";
    private static final String WEBP_MIME = "image/webp";

    /**
     * 从字节里认出这是张什么图;认不出返回 {@code null}。
     *
     * <h2>为什么不用客户端声明的类型</h2>
     *
     * 它是一个字符串,谁都能写。而这个值会被原样拼进送给厂商的请求里 ——
     * 一个不受控的字符串流向外部调用,是最不值得留的那种口子。
     * 认字节则不同:{@code image/jpeg} 是我们自己的常量,客户端影响不了它。
     *
     * <h2>为什么只认这三种</h2>
     *
     * JPEG / PNG / WebP 是多模态厂商普遍接受的三种。<b>iOS 默认的 HEIC 不在其中</b> ——
     * 那是有意的:与其把一段厂商多半读不懂的字节送出去(花了钱、拿回 NO_MATCH、
     * 而用户以为是自己拍糊了),不如当场说清楚「请在客户端转码后再传」。
     * 这与「宁缺毋滥」是同一条:不确定的东西不硬送。
     */
    private static String imageMimeOf(byte[] photo) {
        if (photo == null) {
            return null;
        }
        if (startsWith(photo, 0xFF, 0xD8, 0xFF)) {
            return JPEG_MIME;
        }
        if (startsWith(photo, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG_MIME;
        }
        if (photo.length >= 12 && ascii(photo, 0, "RIFF") && ascii(photo, 8, "WEBP")) {
            return WEBP_MIME;
        }
        return null;
    }

    /**
     * 认不出来就整个请求 400。
     *
     * <p>🔴 报错里带的是<b>第几张</b>,不是那张图的任何内容 —— 连前几个字节的十六进制都不带。
     * 与 {@code ApiExceptionHandler} 开头那条纪律同源:报错消息会同时进响应体和服务端日志,
     * 而这里手上正好拿着一整张图。
     */
    private static String requireImageMime(byte[] photo, int index) {
        String mime = imageMimeOf(photo);
        if (mime == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_IMAGE_FORMAT",
                    "第 " + (index + 1) + " 张图认不出格式 —— 只支持 JPEG / PNG / WebP"
                            + "(iOS 的 HEIC 请在客户端转码后再传)。");
        }
        return mime;
    }

    // ================================================================ 音频:时长自算

    /** 送进 ASR 的类型 —— 与图片同一条:<b>我们自己的常量</b>,不是客户端声明的。 */
    private static final String WAV_MIME = "audio/wav";

    /** 单条时长上限 —— {@code 1.1.1.4} 的「建议 60s」。 */
    private static final int MAX_AUDIO_SECONDS = 60;

    /**
     * 单条音频的字节上限。
     *
     * <h2>它<b>不是</b>时长上限的替代品,两条都要</h2>
     *
     * 字节上限拦得住「一段十分钟的录音」,拦不住「一段压缩得很狠的五分钟录音」——
     * 所以真正管 60 秒的是 {@link #requireAtMostSixtySeconds},字节上限管的是另一件事:
     * <b>在解析任何东西之前,先给内存划一条线</b>。
     *
     * <h2>这个数是怎么来的</h2>
     *
     * 本端点接受的最宽格式是 48 kHz / 16 bit / 单声道 = 96,000 B/s;
     * 60 秒 = 5.76 MB。取 6 MiB,余量留给 WAV 头与各种附加块。
     * <p>
     * 🔴 它必须 ≤ {@code spring.servlet.multipart.max-file-size},
     * 而那个又必须 ≤ {@code file-size-threshold}(否则容器会落临时文件)。
     * 三个数的关系由 {@code AudioRetentionTest} 钉住 —— <b>散在两个文件里的三个数迟早对不上</b>。
     */
    static final int MAX_AUDIO_BYTES = 6 * 1024 * 1024;

    /** 允许的采样率区间。低于 8 kHz 的语音 ASR 认不出,高于 48 kHz 只是白花流量。 */
    private static final int MIN_SAMPLE_RATE = 8_000;
    private static final int MAX_SAMPLE_RATE = 48_000;

    /** 🔴 只收单声道:ASR 只需要单声道,立体声只是把同一段话传两遍,而它会让上面那个字节上限翻倍。 */
    private static final int REQUIRED_CHANNELS = 1;

    /** WAV 的 {@code fmt } 块里 {@code 1} 表示未压缩 PCM。压缩过的 WAV 算不出准确时长。 */
    private static final int PCM_FORMAT_TAG = 1;

    /** 把 part 变成字节,顺便把「没传」和「太大」两件事说清楚。 */
    private static byte[] requireAudioBytes(MultipartFile part) {
        if (part == null || part.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_AUDIO",
                    "请求里没有音频 —— multipart 的 part 名字是 audio。");
        }
        if (part.getSize() > MAX_AUDIO_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "AUDIO_TOO_LARGE",
                    "这段音频超过 6 MiB —— 单条上限 " + MAX_AUDIO_SECONDS + " 秒,请重录一段短的。");
        }
        try {
            return part.getBytes();
        } catch (IOException e) {
            // 🔴 不带 e.getMessage():它里面可能有容器的临时路径,而这个端点的全部主张
            //    就是那条路径不该存在。异常本身也不进日志(见类注释)。
            throw new UncheckedIOException("音频读不出来", e);
        }
    }

    /**
     * 🔴 服务端自己算时长,超过 60 秒当场拒。
     *
     * <p>拒绝的方式是 400 而不是一个「结局码」:超过 60 秒是<b>调用方发错了东西</b>,
     * 重发同样的内容还是错 —— 与「模型没认出来」完全不是一类。契约里「失败提示重录」
     * 指的是转写失败那一支,不是这一支;这一支该说的是「这段太长了,切短一点」。
     */
    private static void requireAtMostSixtySeconds(byte[] clip) {
        double seconds = wavSeconds(clip);
        if (seconds > MAX_AUDIO_SECONDS) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "AUDIO_TOO_LONG",
                    "单条录音最长 " + MAX_AUDIO_SECONDS + " 秒 —— 这一段是 "
                            + Math.round(seconds) + " 秒,请切短后重录。");
        }
    }

    /**
     * 读 WAV 头算时长(秒)。<b>算不出来就抛 400,不猜、不放行。</b>
     *
     * <h2>为什么「算不出来」必须是拒绝,而不是「那就不校验时长了」</h2>
     *
     * 后者是这段代码里最容易写出来的一句降级,而它<b>正好把这条服务端校验变成一句空话</b>:
     * 只要送一段容器认不出的字节,时长检查就自动关掉了。
     * 与「宁缺毋滥」同一条 —— 不确定的时候丢弃,不硬凑。
     *
     * <p>结构:{@code RIFF} + 总长 + {@code WAVE},其后是一串 {@code <4 字节块名><4 字节长度><数据>}
     * 的块。要的是 {@code fmt } 里的 {@code byteRate} 和 {@code data} 的字节数,两者一除即秒数。
     * 块长是奇数时后面补一个填充字节 —— 漏掉这条会把之后所有块读偏。
     */
    private static double wavSeconds(byte[] clip) {
        if (clip.length < 44 || !ascii(clip, 0, "RIFF") || !ascii(clip, 8, "WAVE")) {
            throw badAudio("这段字节不是 PCM WAV —— 本端点只收 WAV,"
                    + "因为只有它能让服务端自己算出时长(m4a / mp3 请在客户端转成 WAV)。");
        }

        long byteRate = -1;
        long dataBytes = -1;

        int cursor = 12;
        while (cursor + 8 <= clip.length) {
            long declared = u32(clip, cursor + 4);
            int payload = cursor + 8;
            int available = clip.length - payload;
            // 🔴 声明的块长可能比实际字节还大(流式录制的 WAV 常写 0xFFFFFFFF)。
            //    照着它去索引就是一次越界;按实际可用长度封顶,少读不多读。
            int usable = (int) Math.min(declared, Math.max(available, 0));

            if (ascii(clip, cursor, "fmt ")) {
                if (usable < 16) {
                    throw badAudio("WAV 的 fmt 块不完整,算不出时长 —— 请重录。");
                }
                byteRate = requirePcmFormat(clip, payload);
            } else if (ascii(clip, cursor, "data")) {
                dataBytes = usable;
            }

            // 奇数块长后面补一个填充字节 —— 漏掉这条会把之后所有块读偏。
            // 每一轮至少推进那 8 个字节的块头,所以不会在空块上死循环。
            cursor = payload + usable + (usable % 2);
        }

        if (byteRate <= 0 || dataBytes < 0) {
            throw badAudio("这段 WAV 里没有能算出时长的信息(缺 fmt 或 data 块)—— 请重录。");
        }
        return (double) dataBytes / byteRate;
    }

    /**
     * 校验 {@code fmt } 块并取出 {@code byteRate}。
     *
     * <p>为什么连声道数和位深都要管:{@code byteRate} 是块里自报的一个数,
     * 而<b>自报的数要能被别的数验一遍才算数</b>。{@code sampleRate × channels × bits/8}
     * 必须等于它,对不上说明这个头是编的 —— 那样算出来的「时长」也就是编的。
     */
    private static long requirePcmFormat(byte[] clip, int payload) {
        int formatTag = u16(clip, payload);
        int channels = u16(clip, payload + 2);
        long sampleRate = u32(clip, payload + 4);
        long byteRate = u32(clip, payload + 8);
        int bits = u16(clip, payload + 14);

        if (formatTag != PCM_FORMAT_TAG) {
            throw badAudio("这段 WAV 是压缩过的,服务端算不出准确时长 —— 请用未压缩的 PCM WAV。");
        }
        if (channels != REQUIRED_CHANNELS) {
            throw badAudio("只收单声道 —— 语音识别用不上第二个声道,它只会让上传大一倍。");
        }
        if (bits != 8 && bits != 16) {
            throw badAudio("只收 8 位或 16 位的 PCM WAV。");
        }
        if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) {
            throw badAudio("采样率要在 " + MIN_SAMPLE_RATE + " 到 " + MAX_SAMPLE_RATE + " Hz 之间。");
        }
        if (byteRate != sampleRate * channels * (bits / 8L)) {
            throw badAudio("这段 WAV 的头自相矛盾,算出来的时长不可信 —— 请重录。");
        }
        return byteRate;
    }

    /** 🔴 报错里只有「这段字节不合规」,没有任何一段音频内容、也没有任何长度以外的数。 */
    private static ApiException badAudio(String message) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_AUDIO_FORMAT", message);
    }

    // ================================================================ 字节小工具

    /** 小端无符号 16 位。 */
    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8);
    }

    /** 小端无符号 32 位。<b>返回 long</b> —— 用 int 接的话 4 GB 的块长会变成负数。 */
    private static long u32(byte[] bytes, int at) {
        return (bytes[at] & 0xFFL)
                | ((bytes[at + 1] & 0xFFL) << 8)
                | ((bytes[at + 2] & 0xFFL) << 16)
                | ((bytes[at + 3] & 0xFFL) << 24);
    }

    /** 从 {@code at} 起的几个字节是不是这几个 ASCII 字符。 */
    private static boolean ascii(byte[] bytes, int at, String tag) {
        if (at < 0 || at + tag.length() > bytes.length) {
            return false;
        }
        for (int i = 0; i < tag.length(); i++) {
            if (bytes[at + i] != (byte) tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** 开头是不是这几个字节(魔数用)。 */
    private static boolean startsWith(byte[] bytes, int... magic) {
        if (bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((bytes[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    // ================================================================ 内部

    /**
     * 🔴 404 的消息里不带那个 id。
     *
     * <p>路径变量没有任何长度上限,而报错消息会同时进响应体和服务端日志 ——
     * 与 {@code TagController} / {@code RecordController#delete} 是同一条纪律,
     * <b>措辞也刻意逐字相同</b>:同一件事在两个端点上说两句不一样的话,前端就得写两条分支。
     */
    private Touch requireRecord(String id) {
        Touch touch = tagging.findRecord(id);
        if (touch == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RECORD_NOT_FOUND",
                    "找不到这条记录 —— 它可能已经被删掉了。");
        }
        return touch;
    }

    /**
     * 把一次补标的结果摊成答复 —— 形状与 {@code POST /tags/suggest} 完全一样。
     *
     * <p>共用 {@link SuggestTagResponse} 不是为了省一个类:这两个端点走的是<b>同一条管线</b>,
     * 答复形状不同的话,前端就得为「同一件事」写两套渲染,而其中一套迟早跟不上另一套。
     */
    private SuggestTagResponse responseFor(Touch touch, Suggestion suggestion) {
        List<RecordTag> tags = tagging.tagsOf(touch);
        CoverageReader.Snapshot snapshot = reader.read();
        Syllabus tree = snapshot.syllabus();
        RecordTag tag = suggestion.tag();

        return new SuggestTagResponse(
                suggestion.outcome().name(),
                SuggestTagResponse.messageFor(suggestion.outcome()),
                suggestion.confidence(),
                suggestion.candidateCount(),
                tag == null ? null : TagDto.from(tag, tree),
                tags.stream().map(t -> TagDto.from(t, tree)).toList(),
                tag == null ? null : nodeDetail(snapshot, tag.nodeCode()),
                SummaryDto.from(reader.summarize(snapshot)));
    }

    /** 考点已不在树里(被删了)时返回 {@code null} —— 那不该让这次请求 500。 */
    private static NodeDetailDto nodeDetail(CoverageReader.Snapshot snapshot, String nodeCode) {
        NodeCoverage node = snapshot.node(nodeCode);
        return node == null ? null : NodeDetailDto.from(node);
    }
}
