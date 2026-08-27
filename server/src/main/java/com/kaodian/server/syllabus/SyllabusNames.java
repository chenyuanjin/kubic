package com.kaodian.server.syllabus;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 考点名 / 题型名的<b>比较口径</b> —— 「这两个名字算不算同一个」只由这里说了算。
 *
 * <h2>为什么名字必须唯一,而且要按规范化后的形状比</h2>
 *
 * 前端是<b>按名字</b>从命令面板挑考点的,面板上只显示名字与状态,<b>不显示题型</b>。
 * 于是两个「看起来一样」的考点在用户眼里就是同一个,记录会被劈到两个语义相同的 code 上 ——
 * 覆盖率的分子被稀释、「整块空白」跟着失真,而覆盖率是这个产品唯一的那个数(01 §2.2 宁缺毋滥)。
 * <p>
 * 「看起来一样」不等于「字节一样」:前后空格、内部多打的空格、全角「ＧＤＰ」与半角「GDP」、
 * 英文大小写,渲染出来分不出差别。所以比较用的是规范化之后的 key,不是原字符串。
 *
 * <h2>🔴 规范化只用于比较,<b>不用于存储</b></h2>
 *
 * 存的是用户输入的原样(只做 {@code strip()})。规范化是一次有损变换 ——
 * 把用户写的「ＧＤＰ 增长率」存成「gdp 增长率」等于替他改了名字,
 * 而阶段 1 的任务恰恰是「人工校正命名」(docs/04 §1.2):名字是用户的判断,不是我们的。
 *
 * <h2>这个类<b>不抛异常</b></h2>
 *
 * 合不合法是 {@code FileSyllabusStore#validName} 的事(空、超长、控制字符、看不见的字符)。
 * 这里是一个纯函数,{@link FileSyllabusStore}(写入时查重)与 {@link SyllabusLoader}
 * (载入时查重)共用它 —— <b>两处用同一个口径,不变式才在一处成立、到处成立</b>。
 */
public final class SyllabusNames {

    /**
     * 内部空白折叠用。{@code UNICODE_CHARACTER_CLASS} 不能省:默认的 {@code \s} 只认 ASCII,
     * 而 NFKC 之后仍可能留下别的空白(如 NBSP 被折成 U+0020 之前的那一步)。
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private SyllabusNames() {
    }

    /**
     * 名字 → 比较用的 key。<b>两个名字的 key 相等,就是同一个名字。</b>
     *
     * <p>五步,顺序有讲究:
     * <ol>
     *   <li>{@code strip()} —— 前后空白不构成区别</li>
     *   <li><b>NFKC</b> —— 让全角「ＡＢＣ」与半角「ABC」相撞。放在折叠空白之前,
     *       因为全角空格 U+3000、不换行空格 U+00A0 正是在这一步才变成普通空格</li>
     *   <li><b>剥掉所有不可见码点</b>({@link #isInvisible}) —— 见下面那一段,
     *       这一步是不变式的兜底。放在 NFKC <b>之后</b>,因为 NFKC 会把半角谚文填充符 U+FFA0
     *       变成 U+1160、把 U+3164 变成 U+1160,先归一再剥,一遍就够</li>
     *   <li>内部连续空白折叠成一个空格 —— 「增长量  计算」与「增长量 计算」是同一个名字</li>
     *   <li>{@code toLowerCase(Locale.ROOT)} —— 让 {@code GDP} / {@code gdp} 相撞。
     *       用 {@code ROOT} 而不是默认 locale:土耳其语环境下 {@code "I".toLowerCase()} 是「ı」,
     *       那会让同一棵树在不同机器上得出不同的重名判断</li>
     * </ol>
     * 第 2 步之后再 {@code strip()} 一次:NBSP 打头的名字在第 1 步是 strip 不掉的
     * ({@code Character.isWhitespace(U+00A0)} 为 {@code false}),NFKC 之后它才变成普通空格。
     *
     * <h2>🔴 为什么这里还要剥一遍不可见码点,明明 {@code validName} 已经拒了它们</h2>
     *
     * 因为 {@code validName} <b>故意放行变体选择符</b>(U+FE0F 这一批,理由见 {@link #isVariationSelector})。
     * 放行的代价必须由这一步兜住:否则「增长量计算」后面缀一个 U+FE0F 就是一个新名字,
     * 渲染出来一模一样,而这正是整条约束要防的东西。
     * <p>
     * 还有第二个理由,和 {@code validName} 无关:<b>这个函数也跑在载入路径上</b>
     * ({@link SyllabusLoader})。磁盘上的文件可以是手工编辑的、可以是从导出文件恢复回来的,
     * 它<b>没有经过 {@code validName}</b>。把剥离放在这里,
     * 「文件里藏着一对肉眼分不出的重名」就会在启动时被查出来,而不是安静地带进内存。
     */
    public static String nameKey(String name) {
        if (name == null) {
            return "";
        }
        String normalized = Normalizer.normalize(name.strip(), Normalizer.Form.NFKC);
        String visible = stripInvisible(normalized);
        return WHITESPACE.matcher(visible).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    /** 去掉一个字符串里所有 {@link #isInvisible} 的码点。逐<b>码点</b>迭代,辅助平面不能被拆成代理项。 */
    public static String stripInvisible(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!isInvisible(cp)) {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /**
     * 这个码点<b>渲染不出任何东西</b>吗?
     *
     * <h2>🔴 为什么不能只判 {@code Character.getType(cp) == Character.FORMAT}</h2>
     *
     * Cf(格式字符)只是「看不见的字符」的一部分,而且不是最好用的那部分。
     * 实测能绕过纯 Cf 判定、并且造出一个肉眼分不出的重名的,至少有这些:
     * <ul>
     *   <li>U+FE00–FE0F / U+E0100–E01EF <b>变体选择符</b> —— 类别是 Mn,不是 Cf</li>
     *   <li>U+034F 组合字素连接符(Mn)</li>
     *   <li>U+3164 谚文填充符、U+FFA0 半角谚文填充符、U+115F/U+1160 谚文初声/中声填充符 ——
     *       类别是 <b>Lo(字母)</b>。「造一个看不见的空位」正是网上那些「隐形字符生成器」的主力</li>
     *   <li>U+2800 盲文空点 —— 类别是 So(符号),在任何字体里都渲染成一格空白</li>
     *   <li>U+17B4/U+17B5 高棉固有元音、U+180B–180F 蒙古自由变体选择符(Mn)</li>
     * </ul>
     * 所以这里取的口径是 Unicode 的 <b>{@code Default_Ignorable_Code_Point}</b> 属性
     * (「渲染引擎应当当它不存在」),再补上 U+2800。JDK 没有暴露这个属性、也不能为它引依赖,
     * 于是把非 Cf 的那部分按 {@code DerivedCoreProperties} 逐段列在下面 ——
     * <b>列出来的东西是可以被审的,靠一个类别判断「大概覆盖了」的东西不能。</b>
     *
     * <h2>为什么不顺手把「未分配码点」(Cn)也算进来</h2>
     *
     * 那会<b>误伤中文</b>:Java 21 的 Unicode 表停在 15.0,更晚的标准新收的 CJK 扩展汉字
     * 在这里一律是 Cn。一个用户手打的生僻字被判成「看不见的字符」,比漏掉一个隐形字符糟得多。
     * 只有 {@code Default_Ignorable} 里那几段<b>被永久保留为「可忽略」</b>的 Cn 才列进来
     * (U+2065、U+FFF0–FFF8、Plane 14),它们不会将来变成字。
     */
    public static boolean isInvisible(int cp) {
        if (Character.getType(cp) == Character.FORMAT) {
            return true;                                       // Cf:U+200B–200D、U+FEFF、U+00AD、U+2060…
        }
        return cp == 0x034F                                    // 组合字素连接符
                || (cp >= 0x115F && cp <= 0x1160)              // 谚文初声/中声填充符
                || (cp >= 0x17B4 && cp <= 0x17B5)              // 高棉固有元音
                || (cp >= 0x180B && cp <= 0x180F)              // 蒙古自由变体选择符
                || cp == 0x2065                                // 永久保留的可忽略码点
                || cp == 0x2800                                // 盲文空点:渲染成一格空白
                || cp == 0x3164                                // 谚文填充符
                || (cp >= 0xFE00 && cp <= 0xFE0F)              // 变体选择符 1–16
                || cp == 0xFFA0                                // 半角谚文填充符
                || (cp >= 0xFFF0 && cp <= 0xFFF8)              // 永久保留的可忽略码点
                || (cp >= 0xE0000 && cp <= 0xE0FFF);           // 语言标记 + 变体选择符 17–256
    }

    /**
     * 这个码点是<b>变体选择符</b>吗 —— 也就是「不可见,但依附于前一个字符」的那一类。
     *
     * <p>它被单拎出来只为一件事:{@code FileSyllabusStore#validName} <b>拒绝其它不可见字符,
     * 但放行它</b>。理由是这两类的来路完全不同:
     * <ul>
     *   <li>填充符、零宽空格、盲文空点是<b>独立成字</b>的 —— 它们在一个考点名里没有任何正当用途,
     *       出现即异常,拒绝并说清楚是对的。</li>
     *   <li>变体选择符<b>永远跟在一个看得见的字符后面</b>,只改那个字符的字形。
     *       最常见的来路是 emoji:「❤️」就是 U+2764 U+FE0F。
     *       拒绝它等于告诉一个刚给考点起名「增长量计算❤️」的用户「名称里不能有看不见的字符」——
     *       他看着屏幕上那颗心,只会觉得这条报错在胡说。<b>误伤比漏放更糟。</b></li>
     * </ul>
     * 放行的代价由 {@link #nameKey} 兜住:它照样被剥掉,所以缀一个变体选择符<b>造不出第二个名字</b>,
     * 只会得到一句说得清的 409「这个名字已经有人叫了」。
     */
    public static boolean isVariationSelector(int cp) {
        return (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF);
    }
}
