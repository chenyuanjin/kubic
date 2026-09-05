package com.kaodian.server.redline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 R-01 的<b>第二道闸</b> —— 建表脚本里同样没有能装下题干的列(docs/execution/INDEX.md §四 R-01)。
 *
 * <h2>为什么 {@link NoStemFieldTest} 拦不住这个文件</h2>
 *
 * {@link NoStemFieldTest} 扫的是<b>编译后的 Class</b>:{@code ClassPathScanningCandidateComponentProvider}
 * 找类,反射读字段。{@link ImageRetentionTest} 扫的是 {@code .java} 文本。
 * <b>两条路都到不了 {@code .sql}。</b>
 * <p>
 * 2026-09 引入 {@code server/db/schema.sql} 之前这不是问题 —— 仓库里一个 {@code .sql} 都没有,
 * 库的形状只由 Java 类型定义。有了建表脚本之后,{@code stem TEXT} 写在那里,
 * 上面两条断言一条都不会红,{@code .githooks} 与 CI 也没有任何一处提到过 {@code .sql}。
 * <b>「线上库不存在能装下题干的字段」这句话,从那天起有一半没有机器看着。</b>
 * 这个类补的就是那一半。
 *
 * <h2>词表不在这里,在 {@link NoStemFieldTest}</h2>
 *
 * {@link NoStemFieldTest#BANNED_WORDS} / {@link NoStemFieldTest#BANNED_CJK} /
 * {@link NoStemFieldTest#MAX_FREE_TEXT_LENGTH} 三个常量<b>直接引用,一个字都不抄</b>。
 * <p>
 * 抄一份的代价不是重复,是<b>漂移</b>:两张表各自改,半年后没人说得清哪张是准的,
 * 而先被忘掉的那张恰好是新加的这张(它扫的文件只有一个,平时没人想起来)。
 * 引用同一份的副作用是这三个常量必须从 {@code private} 放到<b>包内可见</b> ——
 * 那是为共享它们付的全部代价,{@link NoStemFieldTest} 的逻辑一行没动。
 *
 * <h2>🔴 为什么解析必须先剥注释和 {@code COMMENT} 字符串</h2>
 *
 * {@code schema.sql} 里「题干 / 原文 / 内容 / 正文」出现得比任何一个 {@code .java} 都密 ——
 * 因为<b>那个文件正是在解释这条红线</b>:「这里没有、也不会有能装下题干的列」「任何存题干 / 讲义 /
 * 课程内容的表」。裸着扫全文,这条断言第一天就是红的,然后必然被人加白名单加到失效
 * (与 {@link ImageRetentionTest} 剥 Java 注释是同一个理由)。
 * <p>
 * 判据只认<b>列名</b>,所以剥三样:{@code --} 行注释、{@code /* *}{@code /} 块注释、
 * <b>全部单引号字符串</b>({@code COMMENT '...'} 的内容整段不参与匹配)。
 * <p>
 * <b>顺序不能反</b>:先剥注释,再剥字符串。文件头第 5 行的注释里写着
 * {@code find . -name '*.sql' | wc -l} —— 先剥字符串的话,那个引号会从这里开始
 * 一路吃到下一个引号,把半个文件当成字面量抹掉,而抹掉的部分照样「通过」。
 *
 * <h2>也认 {@code ALTER TABLE},不只认 {@code CREATE TABLE}</h2>
 *
 * {@code schema.sql} 自己的规矩是「表结构的任何改动<b>一律追加到文件末尾</b>」,
 * 也就是说<b>从第二次改动起,新列全部以 {@code ALTER TABLE ... ADD COLUMN} 的形式出现</b>。
 * 只认 {@code CREATE TABLE} 的解析器,今天绿,而下一个加进来的列它一个也看不见 ——
 * 那正是「扫不到东西的断言比没有更糟」。{@code ADD} / {@code MODIFY} / {@code CHANGE}
 * 三个动词都认:{@code MODIFY} 能把 {@code VARCHAR(60)} 就地改成 {@code TEXT},
 * 它引入内容的能力和 {@code ADD} 一样。
 *
 * <h2>⚪ 这个解析器认不出什么</h2>
 * <ol>
 *   <li>{@code #} 开头的 MySQL 行注释(本仓库不用这个写法);</li>
 *   <li>字符串字面量内部出现 {@code --} 或 {@code /*} —— 会被当成注释起点提前剥掉。
 *       今天文件里没有,真写了会<b>少扫</b>而不是误报,所以它是缺口不是噪声;</li>
 *   <li>{@code CREATE TABLE ... LIKE / AS SELECT} —— 没有列定义块,整条语句被跳过;</li>
 *   <li>用变量/存储过程/动态 SQL 拼出来的 DDL。这类东西一旦出现在这个仓库里,
 *       问题比一列 {@code TEXT} 大得多,不在这条断言的射程内。</li>
 * </ol>
 *
 * <h2>没有白名单,一行都没有</h2>
 *
 * 与 {@link NoStemFieldTest#noFieldNameCanHoldAQuestion} 同一个理由:留个口子就等于把
 * 「结构上没有这个位置」降级成「命名规范」。今天 37 列一条都不需要例外
 * ({@code name_key VARCHAR(80)} 是全库最宽的一列,离 200 还有一倍多),
 * <b>而建一个空白名单等于给下一个人开口子</b> —— 空表格是最容易被填的表格。
 */
class NoStemColumnTest {

    /**
     * 建表块里<b>不是列定义</b>的那些行,按首个词认。
     *
     * <p>{@code CONSTRAINT ck_xxx CHECK (id = 1)} 与 {@code FOREIGN KEY (...) REFERENCES ...}
     * 的首个词都在这里 —— 漏掉的话会凭空多出名叫 {@code CONSTRAINT} 的「列」,
     * 让下面那条兜底断言的数字虚高,而虚高的下限守不住任何东西。
     */
    private static final Set<String> NOT_A_COLUMN = Set.of(
            "PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT",
            "FOREIGN", "CHECK", "FULLTEXT", "SPATIAL");

    /** {@code CREATE TABLE [IF NOT EXISTS] 表名 (} —— 括号必须跟上,否则它不是带列定义的建表。 */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?\\s*\\(");

    /** {@code ALTER TABLE 表名} —— 后面的子句逐条看。 */
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "(?is)ALTER\\s+TABLE\\s+`?(\\w+)`?\\s");

    /**
     * 带显式长度的字符/字节类型。{@code VARCHAR} 与 {@code CHAR} 之外还认 {@code VARBINARY} /
     * {@code BINARY}:一段题干存成字节仍然是一段题干,红线管的是「装不装得下」。
     */
    private static final Pattern SIZED_TYPE = Pattern.compile(
            "(?i)(?:VAR)?(?:CHAR|BINARY)\\s*\\(\\s*(\\d+)\\s*\\)");

    /**
     * 压根没有长度这个概念的类型。{@code \\w*TEXT} 一并盖住
     * {@code TINYTEXT / TEXT / MEDIUMTEXT / LONGTEXT},{@code \\w*BLOB} 同理。
     *
     * <p>{@code JSON} 也在这里:它和 {@code TEXT} 一样没有上限,
     * 而「先塞个 JSON 以后再说」是这类列最常见的来路。
     */
    private static final Pattern UNBOUNDED_TYPE = Pattern.compile(
            "(?i)(?:\\w*TEXT|\\w*BLOB|JSON)\\b");

    // ——————————————————— 定位 .sql ———————————————————

    /**
     * 仓库里的 {@code server/} 目录 —— 与 {@link ImageRetentionTest#serverDir()} 同一套取法。
     *
     * <p>不写死绝对路径:Maven 从模块目录跑、从 {@code server/} 跑、从仓库根手动跑,
     * {@code user.dir} 是三个不同的值。认不出就<b>失败而不是扫 0 个文件</b>。
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
     * {@code server/} 下的全部 {@code .sql}。
     *
     * <p>🔴 这里是<b>枚举</b>而不是<b>列举</b>,理由与 {@link ImageRetentionTest#mainJavaRoots()}
     * 逐字相同:今天只有 {@code server/db/schema.sql} 一个,写死它的话,
     * 第二个 {@code .sql}(最可能是某个模块下的 {@code src/main/resources/db/migration/V2__xxx.sql})
     * 就会是一片没人看的盲区 —— 而<b>新脚本恰恰是最容易把 R-01 重新犯一次的地方</b>。
     *
     * <p>跳过 {@code target/}:构建会把 {@code src/main/resources} 原样复制过去,
     * 不跳的话同一个文件扫两遍,报错时两条一模一样的行,读的人第一反应是「这是不是重复了」。
     */
    private static List<Path> schemaFiles() {
        Path server = serverDir();
        try (Stream<Path> s = Files.walk(server)) {
            return s.filter(p -> p.toString().endsWith(".sql"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 报错时给人看的路径:相对仓库根,形如 {@code server/db/schema.sql}。 */
    private static String display(Path file) {
        Path repoRoot = serverDir().getParent();
        return repoRoot == null ? file.toString() : repoRoot.relativize(file).toString();
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ================================================================ 断言

    /** 一个「库里能装东西的位置」。{@code definition} 是列名之后的全部,类型就在它开头。 */
    private record Column(Path file, int line, String table, String name, String definition) {

        String qualified() {
            return table + "." + name;
        }

        String at() {
            return display(file) + ":" + line;
        }
    }

    @Test
    @DisplayName("🔴 R-01 之三:建表脚本里没有任何列的名字在说「题目本身」")
    void noColumnNameCanHoldAQuestion() {
        List<String> violations = new ArrayList<>();
        for (Column c : scan()) {
            String hit = bannedHit(c.name());
            if (hit != null) {
                violations.add("  ✗ " + c.at() + "  " + c.qualified() + "\n"
                        + "      违反第 1 条(列名黑名单):名字里命中「" + hit + "」");
            }
        }
        assertTrue(violations.isEmpty(), () -> """
                🔴 R-01 被破坏 —— 建表脚本里出现了名字在说「题目本身」的列(docs/execution/INDEX.md §四 R-01)。

                %s

                这一条没有白名单,改名字也不算数:R-01 说的是「连预留位都不留」,
                把 stem 改叫 detail 只是把红线降级成命名规范,库的形状一点没变。
                词表在 NoStemFieldTest.BANNED_WORDS / BANNED_CJK,两边共用一份 ——
                真要加这个列,先回 docs/execution/INDEX.md §四把 R-01 改掉,再来动词表。顺序不能反。
                """.formatted(String.join("\n", violations)));
    }

    @Test
    @DisplayName("🔴 R-01 之四:自由文本列必须有长度上限,且不超过 200")
    void everyFreeTextColumnHasACeiling() {
        List<String> violations = new ArrayList<>();
        for (Column c : scan()) {
            // 两条正则都【锚在定义开头】(lookingAt),因为类型就是列名之后的第一个东西。
            // 不先切出「第一个词」再拿去匹配:MySQL 允许 VARCHAR (4000) 这样在类型与括号之间
            // 带空格,按空格切会切成「VARCHAR」,两条正则一条都不匹配 —— 于是一列
            // VARCHAR (4000) 安安静静地通过。整段拿去匹配没有这个缺口。
            String definition = c.definition().trim();
            Matcher unbounded = UNBOUNDED_TYPE.matcher(definition);
            if (unbounded.lookingAt()) {
                violations.add("  ✗ " + c.at() + "  " + c.qualified() + "  (" + unbounded.group() + ")\n"
                        + "      违反第 2 条(自由文本长度):这个类型压根没有上限,装得下一整套卷子");
                continue;
            }
            Matcher sized = SIZED_TYPE.matcher(definition);
            if (sized.lookingAt()) {
                int max = Integer.parseInt(sized.group(1));
                if (max > NoStemFieldTest.MAX_FREE_TEXT_LENGTH) {
                    violations.add("  ✗ " + c.at() + "  " + c.qualified() + "  (" + sized.group() + ")\n"
                            + "      违反第 2 条(自由文本长度):上限 " + max + " 超过 "
                            + NoStemFieldTest.MAX_FREE_TEXT_LENGTH + ",够放一段材料了");
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> """
                🔴 R-01 被破坏 —— 建表脚本里出现了装得下内容的列(docs/execution/INDEX.md §四 R-01,决策记录 §2.2 不碰内容)。

                %s

                上限 %d 与 NoStemFieldTest.MAX_FREE_TEXT_LENGTH 是同一个数,不在这里另写一份。
                它的意思不是「精确」,是把「放个名字」和「放段内容」分在两边:
                全库最宽的三处自由文本是 source_name(60)/ name(40)/ name_key(80),
                而真源在 Java 里 —— CreateRecordRequest.MAX_SOURCE_NAME_LENGTH 与
                FileSyllabusStore.MAX_NAME_LENGTH。库里的数只能比它们宽一点,不能比 200 宽。

                这一条同样没有白名单。定不出上限,说明这一列不该存在。
                """.formatted(String.join("\n", violations), NoStemFieldTest.MAX_FREE_TEXT_LENGTH));
    }

    @Test
    @DisplayName("扫描本身没有落空 —— 一个扫不到列的断言等于没有断言")
    void theScanActuallyFindsSomething() {
        List<Path> files = schemaFiles();
        assertTrue(!files.isEmpty(),
                "在 " + serverDir() + " 下一个 .sql 都没找到 —— 建表脚本被挪走了,还是路径解析坏了?");

        List<Column> columns = scan();
        long tables = columns.stream().map(Column::table).distinct().count();

        // 正则失配、剥注释把整个文件抹平、CREATE TABLE 的写法变了 —— 这几种情况下上面两条
        // 断言会安安静静地通过,而且永远通过。所以这里给一个下限。
        // 2026-09 实测:文件 1 个 / 表 6 张 / 列 37 个。下限压在这之下一点,
        // 是留给删表删列,不是留给「扫不到了」。
        assertTrue(tables >= 5 && columns.size() >= 30, () -> """
                扫到的东西太少,这个测试可能已经空转了:%d 个 .sql / %d 张表 / %d 列。
                先确认 %s 里的 CREATE TABLE 还是不是原来那个写法,再谈别的。
                """.formatted(files.size(), tables, columns.size(),
                files.stream().map(NoStemColumnTest::display).toList()));
    }

    // ================================================================ 黑名单匹配

    /**
     * 命中的那个词;没命中返回 null。
     *
     * <p>与 {@code NoStemFieldTest#bannedHit} 逐字同一口径:英文小写化后抹掉 {@code system}
     * 再做<b>子串</b>匹配({@code raw_text / RAW_TEXT / question_stem} 一起覆盖),
     * 中文直接子串匹配。列名是 {@code snake_case} 而字段名是 {@code camelCase},
     * 但两边都只看小写化之后的字符串,下划线不参与判断,所以口径能对得上。
     */
    private static String bannedHit(String columnName) {
        String flat = columnName.toLowerCase(Locale.ROOT).replace("system", "");
        for (String word : NoStemFieldTest.BANNED_WORDS) {
            if (flat.contains(word)) {
                return word;
            }
        }
        for (String word : NoStemFieldTest.BANNED_CJK) {
            if (columnName.contains(word)) {
                return word;
            }
        }
        return null;
    }

    // ================================================================ 解析

    private static List<Column> scan() {
        List<Column> columns = new ArrayList<>();
        for (Path file : schemaFiles()) {
            String effective = stripNoise(read(file));
            columns.addAll(createdColumns(file, effective));
            columns.addAll(alteredColumns(file, effective));
        }
        return columns;
    }

    /** {@code CREATE TABLE 表名 ( … )} 括号里,逗号切开的每一项。 */
    private static List<Column> createdColumns(Path file, String effective) {
        List<Column> columns = new ArrayList<>();
        Matcher m = CREATE_TABLE.matcher(effective);
        while (m.find()) {
            int bodyStart = m.end();
            int bodyEnd = matchingParen(effective, bodyStart);
            for (int[] span : splitTopLevel(effective, bodyStart, bodyEnd)) {
                // 列定义的第一个标识符就是列名,不跳任何词。
                addColumn(columns, file, effective, span, m.group(1), 0);
            }
        }
        return columns;
    }

    /**
     * {@code ALTER TABLE 表名 ADD/MODIFY/CHANGE …} 里引入或改写的列。
     *
     * <p>三个动词的列名位置不同,所以这里数词:{@code ADD [COLUMN] 名},
     * {@code MODIFY [COLUMN] 名},{@code CHANGE [COLUMN] 旧名 新名}。
     * {@code DROP} / {@code RENAME} 不引入能装内容的列,略过。
     */
    private static List<Column> alteredColumns(Path file, String effective) {
        List<Column> columns = new ArrayList<>();
        Matcher m = ALTER_TABLE.matcher(effective);
        while (m.find()) {
            int end = effective.indexOf(';', m.end());
            if (end < 0) {
                end = effective.length();
            }
            for (int[] span : splitTopLevel(effective, m.end(), end)) {
                String[] words = effective.substring(span[0], span[1]).trim().split("\\s+");
                String verb = words[0].toUpperCase(Locale.ROOT);
                if (!verb.equals("ADD") && !verb.equals("MODIFY") && !verb.equals("CHANGE")) {
                    continue;
                }
                int at = 1;
                if (at < words.length && words[at].equalsIgnoreCase("COLUMN")) {
                    at++;
                }
                if (verb.equals("CHANGE")) {
                    at++;  // 旧名,新名才是这一列往后叫什么
                }
                addColumn(columns, file, effective, span, m.group(1), at);
            }
        }
        return columns;
    }

    /** 把一段子句变成一列 —— 除非它压根不是列定义({@code PRIMARY KEY} / {@code ADD INDEX} …)。 */
    private static void addColumn(List<Column> columns, Path file, String effective,
                                  int[] span, String table, int nameIndex) {
        String clause = effective.substring(span[0], span[1]).trim();
        if (clause.isEmpty()) {
            return;
        }
        String[] words = clause.split("\\s+");
        if (nameIndex >= words.length) {
            return;
        }
        String name = words[nameIndex].replace("`", "");
        if (NOT_A_COLUMN.contains(name.toUpperCase(Locale.ROOT))) {
            return;
        }
        String definition = String.join(" ", List.of(words).subList(nameIndex + 1, words.length));
        // 行号要从【列名那个词】上取,不能用 span[0]:切分后每一段是从上一个逗号【之后】开始的,
        // 而那个位置还在上一行的行尾,报出来的行号会整体差一行 —— 一个恰好错一行的行号
        // 比没有行号更耗人,因为它看上去是对的。
        int nameAt = span[0];
        while (nameAt < span[1] && Character.isWhitespace(effective.charAt(nameAt))) {
            nameAt++;
        }
        columns.add(new Column(file, lineAt(effective, nameAt), table, name, definition));
    }

    /** 从 {@code open}(左括号之后的第一个字符)找到配对的右括号,返回它的下标。 */
    private static int matchingParen(String s, int open) {
        int depth = 1;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '(') {
                depth++;
            } else if (s.charAt(i) == ')' && --depth == 0) {
                return i;
            }
        }
        return s.length();
    }

    /**
     * 按<b>括号深度为 0 的逗号</b>切,返回每一段的 {@code [起, 止)}。
     *
     * <p>按行切是不行的:{@code CONSTRAINT fk_… FOREIGN KEY (group_code)} 与下一行的
     * {@code REFERENCES syllabus_group (code)} 是同一项,按行切会把 {@code REFERENCES}
     * 当成一个列名 —— 一个不存在的列,专门用来把兜底断言的数字撑起来。
     * 同理 {@code DECIMAL(4,3)} 里的逗号在深度 1 上,不是分隔符。
     */
    private static List<int[]> splitTopLevel(String s, int from, int to) {
        List<int[]> spans = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                spans.add(new int[]{start, i});
                start = i + 1;
            }
        }
        spans.add(new int[]{start, to});
        return spans;
    }

    private static int lineAt(String s, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 把注释和字符串字面量抹成空格,<b>保留换行</b>(行号才对得上)。
     *
     * <p>抹的是三样:{@code --} 行注释、{@code /* *}{@code /} 块注释、单引号字符串。
     * 顺序见类注释 —— 先注释后字符串,反了会被文件头那句
     * {@code find . -name '*.sql'} 里的引号带偏。
     * <p>
     * 与 {@link ImageRetentionTest#stripComments} 的区别在于<b>字符串也要抹掉</b>:
     * 那边留字符串是因为要在里面找域名,这边留字符串就等于让每一句
     * {@code COMMENT '…题干…'} 都判自己红。
     */
    static String stripNoise(String sql) {
        char[] out = sql.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            if (out[i] == '-' && i + 1 < n && out[i + 1] == '-') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (out[i] == '/' && i + 1 < n && out[i + 1] == '*') {
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    blank(out, i++);
                }
                for (int k = 0; k < 2 && i < n; k++) {
                    out[i++] = ' ';
                }
            } else if (out[i] == '\'') {
                out[i++] = ' ';
                while (i < n) {
                    // '' 是 SQL 里的转义单引号,它不结束字符串。
                    if (out[i] == '\'' && i + 1 < n && out[i + 1] == '\'') {
                        out[i++] = ' ';
                        out[i++] = ' ';
                        continue;
                    }
                    if (out[i] == '\'') {
                        out[i++] = ' ';
                        break;
                    }
                    blank(out, i++);
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static void blank(char[] out, int i) {
        if (out[i] != '\n') {
            out[i] = ' ';
        }
    }
}
