package com.kaodian.server.redline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>R-04:原图绝不上云、不共享 —— 含厂商图片暂存 API。</b>
 *
 * <h2>为什么这条要用源码扫描,而不是只靠接口签名</h2>
 *
 * {@link com.kaodian.server.recognize.VisionTagger} 已经把这条钉在方法签名上了:入参是
 * {@code byte[]},签名里没有 URL / fileId / bucket。{@code CaptureServiceTest} 用反射守着那个形状。
 * <p>
 * 但签名只管得住<b>接口</b>。真正会破线的是将来某个实现类里的一行 ——
 * 一个 {@code X-DashScope-OssResourceResolve} 头、一次 {@code /files/upload}、
 * 一句 {@code log.debug(request)}。这些东西<b>不改任何签名</b>,反射看不见,
 * 只有把生效代码当文本扫一遍才拦得住。
 *
 * <h2>为什么剥注释</h2>
 *
 * 这个仓库的注释密度极高,而且大量注释<b>正是在说明「禁止什么」</b> ——
 * {@code VisionTagger} 的类注释里就写着 {@code file_id}。
 * 判据必须是<b>生效代码</b>而不是注释,否则这条断言从第一天起就是红的,
 * 然后被人加白名单加到失效。做法与 {@code server/build.sh} 校验 Maven 镜像时
 * 「先剥 XML 注释再取 &lt;url&gt;」是同一个思路。
 *
 * <h2>R-52:范围是「所有厂商的文件暂存」,不是「DeepSeek Files API」</h2>
 *
 * docs/execution/INDEX.md §四 {@code R-52} 把这条显式扩展了:百炼返回的 {@code oss://dashscope-instant/...}
 * (48h 有效)与已禁用的 Files API <b>等价</b>,容易被当成「不是 Files API 所以能用」。
 * 所以下面拦的是<b>行为</b>(把图交给厂商存起来再引用),不是某个厂商的名字。
 *
 * <h2>四组断言各自守什么</h2>
 * <ol>
 *   <li>{@link #noVendorFileStagingKeywords} —— 厂商暂存关键字。已经有名字的那些路</li>
 *   <li>{@link #outboundHostsAreWhitelisted} —— 出站 host 白名单。还没有名字的那些路</li>
 *   <li>{@link #recognizePackageNeverPersistsBytes} —— tripwire,守<b>模型出口</b>。
 *       <b>今天必然通过</b>,见该方法注释</li>
 *   <li>{@link #byteHandlingCallersNeverPersistBytes} —— 同一条 tripwire,守<b>上传入口</b>。
 *       2026-08-27 两个上传端点落地后加的:字节在进模型之前先经过几个别的类,
 *       而「收一个上传就存下来」在那几个类里比在 {@code recognize} 里更自然</li>
 * </ol>
 */
class ImageRetentionTest {

    // ——————————————————— 定位源码树 ———————————————————

    /**
     * 仓库里的 {@code server/} 目录。
     *
     * <p>不写死绝对路径:Maven 从模块目录跑时 {@code user.dir} 是 {@code server/kaodian-app/},
     * 从 {@code server/} 跑时是 {@code server/},从仓库根手动跑时是仓库根。三种都要认,
     * 认不出就<b>失败而不是扫 0 个文件</b> —— 一条扫不到东西的断言会永远绿,那比没有更糟。
     */
    private static Path serverDir() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = cursor; p != null; p = p.getParent()) {
            for (Path candidate : List.of(p, p.resolve("server"))) {
                if (Files.isDirectory(candidate.resolve("kaodian-app/src/main/java/com/kaodian/server"))) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("找不到 server/(user.dir=" + cursor + ")");
    }

    /**
     * 全部模块的 {@code src/main/java}。
     *
     * <p><b>2026-08-28 拆多模块后,由「一个根」变成「一组根」。</b>原先生效代码只在
     * {@code server/src/main/java} 一处;现在分散在四个 {@code server/kaodian-模块名/src/main/java}。
     * (这里不写通配符形式 —— 那个星号加斜杠会当场闭合本段 javadoc,编译期就炸。)
     *
     * <p>🔴 这里是<b>枚举</b>而不是<b>列举</b>:扫 {@code server/} 下所有带 {@code src/main/java}
     * 的子目录,新加一个模块自动进扫描范围。写死四个模块名的话,第五个模块就会是一片没人看的盲区 ——
     * 而新模块恰恰是最容易把 R-04 重新犯一次的地方(那里的代码还没人读过第二遍)。
     * <b>断言的价值等于它扫过的范围</b>,所以范围本身不能靠人记得去维护。
     */
    private static List<Path> mainJavaRoots() {
        Path server = serverDir();
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> children = Files.list(server)) {
            children.filter(Files::isDirectory)
                    .map(module -> module.resolve("src/main/java"))
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(roots::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (roots.isEmpty()) {
            throw new IllegalStateException("在 " + server + " 下一个 src/main/java 都没找到 —— 目录结构变了?");
        }
        return roots;
    }

    /** 全部模块里的全部 .java。 */
    private static List<Path> allSources() {
        List<Path> all = new ArrayList<>();
        for (Path root : mainJavaRoots()) {
            all.addAll(sources(root));
        }
        return all;
    }

    /** 在任一模块根下解析一个包相对路径;找不到返回 null(由调用方决定这算不算失败)。 */
    private static Path findInRoots(String relative) {
        for (Path root : mainJavaRoots()) {
            Path hit = root.resolve(relative);
            if (Files.exists(hit)) {
                return hit;
            }
        }
        return null;
    }

    /** 报错时给人看的路径:相对仓库根,形如 {@code server/kaodian-domain/src/main/java/...}。 */
    private static String display(Path file) {
        Path repoRoot = serverDir().getParent();
        return repoRoot == null ? file.toString() : repoRoot.relativize(file).toString();
    }

    private static List<Path> sources(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ——————————————————— ① 厂商暂存关键字 ———————————————————

    /**
     * 一条被禁的关键字。
     *
     * @param token      要找的文本
     * @param identifier 是否要求两侧是<b>标识符边界</b>。{@code fileId} 必须要求 ——
     *                   否则 {@code profileId} 这种正常命名会被误伤,一次误伤就足以让人把整条断言删掉
     * @param why        报错时打出来的理由。<b>不写「禁止 X」,写「为什么禁止」</b>
     */
    private record Forbidden(String token, boolean identifier, String why) {
    }

    private static final List<Forbidden> VENDOR_STAGING = List.of(
            new Forbidden("X-DashScope-OssResourceResolve", false,
                    "R-52 点名的那个头 —— 它就是百炼版的 Files API"),
            new Forbidden("dashscope-instant", false,
                    "百炼临时文件存储的 bucket。48h 有效不等于没存,平台侧已经有副本了"),
            new Forbidden("oss://", false,
                    "对象存储 URI。原图一旦有 URI 就意味着它在某个 bucket 里(R-04 / docs/execution/INDEX.md 2.1.4.2)"),
            new Forbidden("dashscope", false,
                    "R-52:范围是所有厂商的文件暂存,不限 DeepSeek。百炼整个入口先拦下来"),
            new Forbidden("files.deepseek", false,
                    "DeepSeek Files API 的域名 —— docs/data/识别链路选型.md 坑二直接点名的红线项"),
            new Forbidden("/files/upload", false,
                    "「先传上去拿个引用」的通用形状,与厂商无关"),
            new Forbidden("file_id", true,
                    "厂商暂存返回的句柄。代码里出现它,就说明图已经在别人那儿了"),
            new Forbidden("fileId", true,
                    "同上,驼峰写法。VisionTagger 的签名里刻意没有这个概念"),
            new Forbidden("purpose=", false,
                    "OpenAI 风格 Files API 的上传参数(purpose=file-extract 之类)"));

    @Test
    @DisplayName("🔴 R-04/R-52:生效代码里不出现任何厂商图片暂存的痕迹")
    void noVendorFileStagingKeywords() {
        List<String> hits = new ArrayList<>();
        int scanned = 0;

        for (Path file : allSources()) {
            scanned++;
            String[] lines = stripComments(read(file)).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                for (Forbidden f : VENDOR_STAGING) {
                    if (contains(lines[i], f)) {
                        hits.add(display(file) + ":" + (i + 1)
                                + "  违反 R-04/R-52 —— 出现「" + f.token() + "」;" + f.why()
                                + "\n      >>> " + lines[i].strip());
                    }
                }
            }
        }

        // 扫 0 个文件也会「通过」。这里把路径解析本身也变成断言的一部分。
        // 拆多模块后还多守一件事:少扫【一整个模块】同样会让这个数掉下来。
        assertTrue(scanned >= 50, "只扫到 " + scanned + " 个源文件,源码树定位坏了(roots=" + mainJavaRoots() + ")");
        assertTrue(hits.isEmpty(),
                "🔴 原图绝不上云、不共享,含厂商图片暂存 API(docs/execution/INDEX.md §四 R-04、R-52)。"
                        + "\n这条第一天不定就改不回来 —— 不要加白名单,改代码。\n" + String.join("\n", hits));
    }

    /** 标识符边界:前后一个字符都不能是标识符字符,{@code profileId} 才不会被当成 {@code fileId}。 */
    private static boolean contains(String line, Forbidden f) {
        String haystack = f.identifier() ? line : line.toLowerCase(Locale.ROOT);
        String needle = f.identifier() ? f.token() : f.token().toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            if (!f.identifier()) {
                return true;
            }
            boolean leftOk = at == 0 || !isIdent(haystack.charAt(at - 1));
            int end = at + needle.length();
            boolean rightOk = end >= haystack.length() || !isIdent(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    // ——————————————————— ② 出站 host 白名单 ———————————————————

    /**
     * 允许出站的域名 —— <b>显式列举,每一项写明用途</b>。
     *
     * <p>不用宽松正则(比如「{@code *.qq.com} 都放行」)。宽松正则挡不住
     * {@code dashscope.aliyuncs.com} 那类新增,而新增一个出站厂商本来就该被人看一眼:
     * <b>宁可拦下来逼人加白名单,也不要它悄悄通过。</b>
     * <p>
     * 这四个的共同点,也是它们能进白名单的<b>唯一理由</b>:
     * 它们收发的是 openid / 手机号 / 验证码票据,<b>没有一个会经手图片字节</b>。
     * 哪天有人往这里加一个识别厂商的域名,那次 code review 就是 R-04 的最后一道关。
     */
    private static final Map<String, String> ALLOWED_HOSTS = new LinkedHashMap<>(Map.of(
            "api.weixin.qq.com",
            "微信服务端接口:jscode2session / oauth2.access_token / sns.userinfo / "
                    + "cgi-bin.stable_token / wxa.business.getuserphonenumber(HttpWeChatClient)",
            "open.weixin.qq.com",
            "微信授权页拼接:connect/oauth2/authorize 与 connect/qrconnect —— 只生成给浏览器跳转的 URL"
                    + "(HttpWeChatClient#buildAuthorizeUrl)",
            "sms.tencentcloudapi.com",
            "腾讯云短信 SendSms —— 发验证码(TencentCloudSmsSender)",
            "captcha.tencentcloudapi.com",
            "腾讯云验证码 DescribeCaptchaResult —— 校验前端滑块票据(TencentCaptchaVerifier)"));

    /**
     * host 由变量拼出来、扫不到字面量的地方。
     *
     * <p>只有一处:{@code TencentCloudApi#call} 写的是 {@code "https://" + host + "/"}。
     * 放它过去<b>不是因为它安全,是因为它的 host 全部来自字面量常量</b> ——
     * {@code sms.tencentcloudapi.com} 与 {@code captcha.tencentcloudapi.com},
     * 两个都被下面的「裸域名字面量」扫描逮住并对着白名单核过。
     * 换句话说这里没有漏洞,只是检查点挪到了调用方。
     */
    private static final Map<String, String> CONCAT_HOST_EXEMPT = Map.of(
            "TencentCloudApi.java",
            "host 是入参,两个调用方传的都是字面量常量,已被裸域名扫描覆盖");

    /** 只认这些顶级域,免得把 {@code touches.json} 之类当成域名。 */
    private static final Set<String> TLDS = Set.of(
            "com", "cn", "net", "org", "io", "ai", "co", "dev", "app", "cloud", "top", "xyz", "me", "tech");

    @Test
    @DisplayName("🔴 出站域名必须在显式白名单里 —— 新增一个厂商就该被拦一次")
    void outboundHostsAreWhitelisted() {
        List<String> hits = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        for (Path file : allSources()) {
            String effective = stripComments(read(file));
            String[] lines = effective.split("\n", -1);

            // (a) 直接写在代码里的 https:// URL
            for (int i = 0; i < lines.length; i++) {
                int from = 0;
                while (true) {
                    int at = lines[i].indexOf("https://", from);
                    if (at < 0) {
                        break;
                    }
                    from = at + 8;
                    String host = hostAfter(lines[i], from);
                    if (host.isEmpty()) {
                        // "https://" + host —— host 不是字面量,只能按文件豁免
                        String reason = CONCAT_HOST_EXEMPT.get(file.getFileName().toString());
                        if (reason == null) {
                            hits.add(display(file) + ":" + (i + 1)
                                    + "  拼接出来的出站 URL,host 不可见,无法核白名单;" + "\n      >>> "
                                    + lines[i].strip());
                        }
                        continue;
                    }
                    seen.add(host);
                    if (!ALLOWED_HOSTS.containsKey(host)) {
                        hits.add(display(file) + ":" + (i + 1) + "  出站 host「" + host + "」不在白名单里"
                                + "\n      >>> " + lines[i].strip());
                    }
                }
            }

            // (b) 裸域名字面量。TencentCloudApi 那种 "https://" + host 的写法,
            //     真正的域名是以 "sms.tencentcloudapi.com" 这种常量形式存在的 —— 这里补上。
            for (Literal lit : stringLiterals(effective)) {
                if (!looksLikeHost(lit.value())) {
                    continue;
                }
                seen.add(lit.value());
                if (!ALLOWED_HOSTS.containsKey(lit.value())) {
                    hits.add(display(file) + ":" + lit.line() + "  域名字面量「" + lit.value()
                            + "」不在白名单里");
                }
            }
        }

        // 扫描链路(定位 + 剥注释 + 字面量提取)自检:这四个是今天确实存在的,
        // 一个都没扫到就说明扫描本身坏了,而不是代码干净了。
        for (String must : ALLOWED_HOSTS.keySet()) {
            assertTrue(seen.contains(must),
                    "白名单里的「" + must + "」在源码里一次都没扫到 —— 扫描链路坏了,或该项已过期该删");
        }
        assertTrue(hits.isEmpty(),
                "🔴 出站域名白名单(R-04)。加白名单前先回答一个问题:这个域名会不会经手图片字节?"
                        + "\n会 —— 那它违反 R-04,不能加。不会 —— 在 ALLOWED_HOSTS 里补上用途说明。\n"
                        + String.join("\n", hits));
    }

    /** 取 {@code https://} 之后到分隔符为止的 host;紧跟着引号或 {@code +} 说明是拼接,返回空串。 */
    private static String hostAfter(String line, int start) {
        int i = start;
        StringBuilder sb = new StringBuilder();
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '/' || c == '"' || c == '\'' || c == '?' || c == '#' || c == ':'
                    || c == ' ' || c == '+' || c == ')' || c == ',') {
                break;
            }
            sb.append(c);
            i++;
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeHost(String s) {
        if (!s.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")) {
            return false;
        }
        String[] labels = s.split("\\.");
        return labels.length >= 2 && TLDS.contains(labels[labels.length - 1]);
    }

    // ——————————————————— ③ 图片不落盘 tripwire ———————————————————

    /**
     * {@code recognize} 包里不许出现「把字节写出去」或「把字节打进日志」的形状。
     *
     * <h2>🔴 这条今天必然通过,那正是它存在的理由</h2>
     *
     * 现在 {@code recognize} 包下只有两个接口和两个 Stub,压根没有 IO ——
     * 所以这不是一条「发现了问题」的断言,是一条 <b>tripwire</b>:
     * 它要响的是<b>将来接真实厂商实现的那一天</b>。
     * <p>
     * 那天的现场大概是这样:调 DeepSeek / 百炼要拼 multipart,顺手 {@code Files.write} 存个临时文件;
     * 或者联调不通,加一句 {@code log.debug("req={}", body)} ——
     * 而 body 里就是那张图的 base64。两个动作都不改任何签名、不改任何接口,
     * code review 里看起来都像「调试代码」。docs/technical/INDEX.md §8.1 的三条附带禁令
     * (不写磁盘、不进对象存储、不把 base64 打进任何级别的日志)拦的就是这两下。
     * <p>
     * 所以拦得宽是故意的:连 {@code ByteArrayOutputStream}、连 {@code Path} 都不放行。
     * 真到了非要不可的那天,红一次、停下来重读一遍 R-04,就是这条断言的全部产出。
     * <b>要放行请写明理由,不要直接把行删掉。</b>
     */
    private static final List<Forbidden> NO_PERSIST = List.of(
            new Forbidden("Files.write", false, "直接写磁盘"),
            new Forbidden("Files.newOutputStream", false, "直接写磁盘"),
            new Forbidden("Files.copy", false, "复制到磁盘"),
            new Forbidden("Files.createTempFile", false, "临时文件也是文件,进程崩了就留下了"),
            new Forbidden("FileOutputStream", false, "直接写磁盘"),
            new Forbidden("OutputStream", false, "任何输出流都是一条出口,包括看着无害的 ByteArrayOutputStream"),
            new Forbidden("RandomAccessFile", false, "直接写磁盘"),
            new Forbidden("FileChannel", false, "直接写磁盘"),
            new Forbidden("toFile", false, "把 Path 变成 File,下一步就是写"),
            new Forbidden("ofFile", false, "BodyPublishers.ofFile —— 从磁盘往外发"),
            new Forbidden("java.io.File", false, "识别链路里不该出现文件概念"),
            new Forbidden("Path", true, "识别链路里不该出现路径概念(VisionTagger 签名里就没有)"),
            new Forbidden("Paths", true, "同上"),
            new Forbidden("bucket", true, "对象存储 —— 原图不进云(docs/execution/INDEX.md 2.1.4.2)"));

    /** 出现在日志语句里就等于把图打进了日志。 */
    private static final List<String> RISKY_IN_LOG = List.of(
            "image", "audio", "bytes", "base64", "payload", "body", "request", "req", "content", "data");

    @Test
    @DisplayName("🔴 tripwire:recognize 包不许把图片字节落盘、外发或打进日志(今天必然通过)")
    void recognizePackageNeverPersistsBytes() {
        // recognize 现在住在 kaodian-domain 里。不写死是哪个模块:它将来还可能再搬一次,
        // 而这条断言关心的是「那个包里的代码不落盘」,不是「那个包在哪个模块」。
        Path pkg = findInRoots("com/kaodian/server/recognize");
        assertTrue(pkg != null && Files.isDirectory(pkg),
                "recognize 包在任何模块里都找不到 —— 是被挪走了还是路径解析坏了?");

        List<Path> files = sources(pkg);
        assertTrue(files.size() >= 4, "recognize 包只扫到 " + files.size() + " 个文件,不对劲");

        // 判据与 ④ 共用一段(persistenceHitsIn):两条断言的区别只在扫哪些文件,不在判什么。
        List<String> hits = new ArrayList<>();
        for (Path file : files) {
            hits.addAll(persistenceHitsIn(file));
        }

        assertTrue(hits.isEmpty(),
                "🔴 tripwire 响了。这大概率意味着有人正在接真实的识别厂商实现 —— "
                        + "停一下,重读 docs/execution/INDEX.md §四 R-04 与 docs/technical/INDEX.md §8.1:"
                        + "\n原图只在内存里过一次,不写磁盘、不进对象存储、不进任何级别的日志。\n"
                        + String.join("\n", hits));
    }

    // ——————————————————— ④ 调用方也不许落盘 ———————————————————

    /**
     * 手里真的握着原始字节的<b>调用方</b>。
     *
     * <h2>为什么范围要从 {@code recognize} 包扩出来</h2>
     *
     * ③ 那条守的是「模型出口」。但从 2026-08-27 起,原图与音频<b>在进出口之前先经过几个别的类</b>:
     * HTTP 端点把 base64 解成 {@code byte[]}、把 multipart 读成 {@code byte[]},再递给打标管线。
     * <b>「先落个临时文件再处理」这句话在这几个类里比在 {@code recognize} 里更自然</b> ——
     * 那里是「拼一次请求」,这里是「收一个上传」,而收上传的标准写法就是存下来。
     * <p>
     * ③ 的注释说它「今天必然通过,那正是它存在的理由」。这一条同样如此。
     *
     * <h2>为什么是一份点名清单,而不是整个 {@code api} 包</h2>
     *
     * 因为整个 {@code api} 包扫不了,而且不该扫:{@code auth} 那边的
     * {@code FileTokenStore} / {@code FileSignupLedger} <b>本来就该写磁盘</b>
     * (令牌与注册流水是要落盘的数据),把它们一起拦下来只会逼人给这条断言加白名单,
     * 而<b>一条被加过白名单的红线断言,和一条被注释掉的断言,外观是一样的</b>。
     * <p>
     * 点名清单的代价是「有人新增一个经手字节的类时得记得加一行」——这是真的缺口,
     * 不藏着。缓解手段是下面那句存在性断言:清单里任何一项被改名或挪走,
     * <b>这条断言当场红</b>,而不是安安静静地少扫一个文件。
     */
    private static final List<String> BYTE_HANDLING_CALLERS = List.of(
            // POST /records/{id}/image 与 /audio 的落点:base64 解出来的原图、multipart 读出来的音频
            "com/kaodian/server/api/record/RecognitionController.java",
            // 图片请求体本身 —— byte[] 就在这个 record 里
            "com/kaodian/server/api/dto/record/PhotoRecognitionRequest.java",
            // 音频端点的答复:它的全部主张就是「里面没有转写文本」
            "com/kaodian/server/api/dto/record/AudioRecognitionResponse.java",
            // 打标管线的调用方:material 那个 byte[] 从这里走到模型出口
            "com/kaodian/server/collect/TaggingService.java",
            // 拍照采集:image 那个 byte[] 同上
            "com/kaodian/server/collect/CaptureService.java");

    @Test
    @DisplayName("🔴 tripwire:握着原图/音频字节的调用方,同样不许落盘、外发或打进日志")
    void byteHandlingCallersNeverPersistBytes() {
        List<String> hits = new ArrayList<>();

        for (String relative : BYTE_HANDLING_CALLERS) {
            Path file = findInRoots(relative);
            // 清单腐烂的唯一防线:改名或挪走当场红,而不是少扫一个文件还照样绿。
            // 跨模块搬家【不】算腐烂 —— findInRoots 在所有模块里找,所以只有真的改名/删除才会红。
            assertTrue(file != null && Files.isRegularFile(file),
                    "点名清单里的「" + relative + "」不存在了 —— 它是被改名、被挪走,还是被删了?"
                            + "\n如果那段经手字节的代码搬到了别处,把清单跟着改;"
                            + "如果它真的没了,把这一行删掉。不要留一行扫不到东西的清单。");
            hits.addAll(persistenceHitsIn(file));
        }

        assertTrue(hits.isEmpty(),
                "🔴 tripwire 响了 —— 有人在原图/音频的【入口侧】写下了一条落盘或日志的路。"
                        + "\n重读 docs/execution/INDEX.md §四 R-04 与 docs/technical/INDEX.md §8.1:原图只在内存里过一次,"
                        + "不写磁盘、不进对象存储、不进任何级别的日志;音频同理(docs/technical/INDEX.md §5.2「不建的表」)。\n"
                        + String.join("\n", hits));
    }

    /**
     * ③ 与 ④ 共用的那段判定 —— <b>规则只写一处</b>。
     *
     * <p>两条断言的区别只在扫哪些文件:一个是「模型出口」,一个是「上传入口」。
     * 判据抄两遍的话,迟早只有一份跟着 {@link #NO_PERSIST} 一起更新。
     */
    private static List<String> persistenceHitsIn(Path file) {
        List<String> hits = new ArrayList<>();
        String[] lines = stripComments(read(file)).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            for (Forbidden f : NO_PERSIST) {
                if (contains(line, f)) {
                    hits.add(display(file) + ":" + (i + 1) + "  违反 R-04(不落盘)—— 「"
                            + f.token() + "」:" + f.why() + "\n      >>> " + line.strip());
                }
            }

            if (line.contains("System.out") || line.contains("System.err")
                    || line.contains("printStackTrace")) {
                hits.add(display(file) + ":" + (i + 1)
                        + "  违反 R-04(不进日志)—— 标准输出不受日志级别控制,一开就全打出来"
                        + "\n      >>> " + line.strip());
                continue;
            }

            if (!isLogCall(line)) {
                continue;
            }
            String statement = statementFrom(lines, i).toLowerCase(Locale.ROOT);
            for (String risky : RISKY_IN_LOG) {
                if (statement.contains(risky)) {
                    hits.add(display(file) + ":" + (i + 1)
                            + "  违反 R-04(不进日志)—— 日志语句里出现「" + risky
                            + "」;一次 log.debug 就等于把原图落了盘(docs/technical/INDEX.md §8.1)"
                            + "\n      >>> " + line.strip());
                    break;
                }
            }
        }
        return hits;
    }

    private static boolean isLogCall(String line) {
        return line.matches(".*\\b(log|logger|LOG|LOGGER)\\s*\\.\\s*"
                + "(trace|debug|info|warn|error)\\s*\\(.*");
    }

    /** 日志调用常常跨行。从这一行起拼到分号为止,最多 6 行,避免只看首行漏掉参数。 */
    private static String statementFrom(String[] lines, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length && i < start + 6; i++) {
            sb.append(lines[i]).append(' ');
            if (lines[i].contains(";")) {
                break;
            }
        }
        return sb.toString();
    }

    // ——————————————————— 剥注释 ———————————————————

    /**
     * 把注释抹成空格(保留换行,行号才对得上),字符串字面量原样留下。
     *
     * <p>顺序很重要:先判注释,但字符串/字符/文本块一旦进入就整段跳过 ——
     * 否则 {@code "https://a.com/b"} 里的 {@code //} 会被当成行注释,把后半行吃掉。
     */
    static String stripComments(String src) {
        char[] out = src.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i] = ' ';
                out[i + 1] = ' ';
                i += 2;
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i] = ' ';
                    if (i + 1 < n) {
                        out[i + 1] = ' ';
                    }
                    i += 2;
                }
            } else if (c == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"') {
                i += 3;
                while (i + 2 < n && !(out[i] == '"' && out[i + 1] == '"' && out[i + 2] == '"')) {
                    if (out[i] == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(n, i + 3);
            } else if (c == '"') {
                i++;
                while (i < n && out[i] != '"' && out[i] != '\n') {
                    if (out[i] == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
            } else if (c == '\'') {
                i++;
                while (i < n && out[i] != '\'' && out[i] != '\n') {
                    if (out[i] == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private record Literal(String value, int line) {
    }

    /** 从<b>已剥注释</b>的源码里取出所有普通字符串字面量(文本块不参与:它不是拿来写域名的)。 */
    private static List<Literal> stringLiterals(String effective) {
        List<Literal> found = new ArrayList<>();
        int line = 1;
        int n = effective.length();
        for (int i = 0; i < n; i++) {
            char c = effective.charAt(i);
            if (c == '\n') {
                line++;
            } else if (c == '"' && i + 2 < n && effective.charAt(i + 1) == '"'
                    && effective.charAt(i + 2) == '"') {
                i += 2;
            } else if (c == '"') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < n && effective.charAt(j) != '"' && effective.charAt(j) != '\n') {
                    if (effective.charAt(j) == '\\') {
                        j++;
                    }
                    if (j < n) {
                        sb.append(effective.charAt(j));
                    }
                    j++;
                }
                found.add(new Literal(sb.toString(), line));
                i = j;
            }
        }
        return found;
    }
}
