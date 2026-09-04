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
        } catch (FileAccountStore.LegacyUserIdException e) {
            // 🔴 原样放出去。这一条自己就带着出路(B0-2 §3.4),包进下面那句
            // 「文件内容不合法」里,用户看到的就不是那条有出路的消息了。
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
