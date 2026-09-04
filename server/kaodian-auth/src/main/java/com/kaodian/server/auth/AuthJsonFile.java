package com.kaodian.server.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.function.Function;

/**
 * 「文件当库」的那套动作,写一遍。
 *
 * <h2>为什么单独抽出来,而 {@code collect}/{@code syllabus} 各写各的</h2>
 *
 * 那两个包各只有一个 store。{@code auth} 这一层有四个(账号、令牌、验证码、频控计数),
 * 四份「先写 tmp → fsync → 原子 rename」的代码意味着<b>四个各自会写错的地方</b>,
 * 而写错的后果是用户的账号数据坏掉。
 * <p>
 * 抽出来的边界刻意很窄:它只管<b>怎么把一个 JSON 根节点安全地落到盘上</b>,
 * 不认识账号、令牌、验证码里的任何一个字段。谁的文件里能出现哪些键,
 * 仍然由各自的 store 逐字段列举 —— 那条纪律不下放(见 {@code FileTouchStore} 的同名段落)。
 *
 * <h2>🔴 认不出来就吵着失败,绝不当成空</h2>
 *
 * {@link #read} 在文件存在但内容不合法时抛异常,而不是回一个空节点。
 * 理由与 {@code FileTouchStore#parse} 完全一样:下一次写入是<b>全量重写</b>,
 * 「坏文件 → 空数据 → 覆盖」这条链走完,用户丢的是账号本身。
 */
final class AuthJsonFile {

    private static final String TMP_SUFFIX = ".tmp";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    AuthJsonFile(Path file) {
        this.file = file.toAbsolutePath();
    }

    Path path() {
        return file;
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 读一份。文件不存在时用 {@code ifAbsent} 造一个空的。
     *
     * @param parse 把根节点翻成内存结构;它抛出的任何异常都会被包成 {@link IllegalStateException}
     */
    <T> T read(Function<JsonNode, T> parse, java.util.function.Supplier<T> ifAbsent) {
        if (!Files.exists(file)) {
            return ifAbsent.get();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return parse.apply(MAPPER.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("鉴权数据文件读取失败:" + file, e);
        } catch (LegacyUserIdException e) {
            // 🔴 原样往上抛,不包成「内容不合法」—— 它的消息里写着确切的出路,
            // 而被包一层之后,启动日志第一眼看到的是一句和原因无关的话。
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "鉴权数据文件内容不合法:" + file
                            + " —— 宁可在这里失败,也不能当成空数据,否则下一次写入会把它整个盖掉", e);
        }
    }

    /** 先写临时文件 → fsync → 原子 rename。中途断电最坏结果是这次写入没发生。 */
    void write(ObjectNode root) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            Files.write(tmp, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            tighten(tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("鉴权数据写入失败:" + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不该盖掉真正的错误
            }
        }
    }

    /**
     * 读一个账号 id —— <b>{@code B0-2} 的启动期守卫就在这一个方法里</b>({@code M5} §一)。
     *
     * <h2>为什么是守卫而不是迁移器</h2>
     *
     * {@code u_}+UUID → {@code long} 是一次不可逆的重编号:{@code u_3f2a…} 里没有任何信息
     * 能算出它该变成 {@code 10001} 还是 {@code 10002},只能<b>按某个顺序重新发一遍号</b>。
     * 而那要求同时改四个文件里的每一处引用(账号 / 身份 / 令牌 / 注册流水),
     * 中途失败就是一份谁也登不进去的数据。
     * <p>
     * 🔴 <b>本产品此刻没有真实用户</b>({@code CLAUDE.md}:「零真实用户反馈」),
     * 所以迁移窗口为零 —— 写一个只会被跑零次、而且没有真数据可验的迁移器,
     * 比拒绝启动更危险。<b>拒绝启动是响亮的,静默迁错是不响亮的。</b>
     *
     * @throws LegacyUserIdException 读到 {@code u_} 开头的老 id
     */
    static long userId(JsonNode n, String field) {
        String raw = n.path(field).asString("");
        if (raw.isEmpty()) {
            throw new IllegalStateException("记录缺少必填字段:" + field);
        }
        if (raw.startsWith("u_")) {
            throw new LegacyUserIdException(field, raw);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "账号 id 必须是 int64 的十进制串(B0 §3.2),实得 " + field + "=" + raw, e);
        }
    }

    /** 账号 id 一律以<b>字符串</b>写进 JSON —— int64 在 JS 里过不了 {@code Number} 那一关。 */
    static String userIdString(long userId) {
        return Long.toString(userId);
    }

    /** 读到了废止的 {@code u_}+UUID 形态的账号 id。<b>拒绝启动。</b> */
    static final class LegacyUserIdException extends IllegalStateException {

        LegacyUserIdException(String field, String raw) {
            super("""
                    拒绝启动:鉴权数据里还是废止的 u_ 形态账号 id(%s=%s)。
                    B0 §3.2 已把全仓账号 id 统一为 long(int64,起始 %d),而这份数据是统一之前写的。
                    🔴 没有迁移器,而且不会有 —— u_+UUID 里没有信息能算出它该变成哪个号,
                       只能重新发一遍号,那要同时改四个文件里的每一处引用,中途失败即数据报废。
                    出路二选一:
                      ① 本机没有要保的账号 → 删掉 ~/.kaodian/auth-*.json 重新开始(这是当前唯一的真实情形);
                      ② 有要保的账号      → 停在这里,不要启动,先把这批数据导出来再谈。
                    """.formatted(field, raw, AppUser.FIRST_USER_ID));
        }
    }

    ObjectNode newRoot(String... comments) {
        ObjectNode root = MAPPER.createObjectNode();
        var arr = root.putArray("_comment");
        for (String c : comments) {
            arr.add(c);
        }
        return root;
    }

    /**
     * 600。
     *
     * <p>这一层的四个文件都装着凭证性质的东西(令牌哈希、手机号密文、验证码哈希),
     * 而 {@code ~/.kaodian} 默认权限跟着 umask 走 —— 多用户机器上那可能是 755。
     * 在 <b>rename 之前</b>收紧,是为了不留一个「刚落盘那一瞬间是可读的」窗口。
     */
    private static void tighten(Path f) {
        try {
            Files.setPosixFilePermissions(f,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统上无解,不因此让写入失败
        }
    }
}
