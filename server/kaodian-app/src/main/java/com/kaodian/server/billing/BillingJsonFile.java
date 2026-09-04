package com.kaodian.server.billing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Function;

/**
 * 「文件当库」的那套动作,商业化这一侧写一遍。
 *
 * <h2>为什么单独抽出来</h2>
 *
 * 与 {@code kaodian-auth} 的 {@code AuthJsonFile} 同一条理由:商业化有<b>三个</b> store
 * (额度账本、订单、订阅),三份「先写 tmp → fsync → 原子 rename」的代码意味着
 * <b>三个各自会写错的地方</b>,而写错的后果是<b>钱的账对不上</b>。
 *
 * <p>边界刻意很窄:它只管怎么把一个 JSON 根节点安全地落到盘上,不认识订单、额度、订阅里的
 * 任何一个字段。谁的文件里能出现哪些键,仍然由各自的 store <b>逐字段列举</b> ——
 * 那条纪律不下放(红线 4:库里不留能装题干的字段,靠的正是「只写列出来的那几个键」)。
 *
 * <h2>🔴 认不出来就吵着失败,绝不当成空</h2>
 *
 * {@link #read} 在文件存在但内容不合法时抛异常,而不是回一个空节点。
 * 下一次写入是<b>全量重写</b>,「坏文件 → 空数据 → 覆盖」这条链走完,丢的是整本账。
 *
 * <p>ponytail: 全量重写 + 单进程一把锁。天花板与 {@code B0} §2.3 同一条前提
 * ——「文件存储的全部前提是整个进程一份」;{@code B0-1} 裁定本轮不写 DDL,
 * 迁库时换成带唯一索引的表,三个 store 的接口签名一个字不改。
 */
final class BillingJsonFile {

    private static final String TMP_SUFFIX = ".tmp";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    BillingJsonFile(Path file) {
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
     * @throws IllegalStateException 文件在但读不懂 —— 见类注释,这里绝不静默当成空
     */
    <T> T read(Function<JsonNode, T> parse, java.util.function.Supplier<T> ifAbsent) {
        if (!Files.exists(file)) {
            return ifAbsent.get();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "商业化数据文件读不懂,拒绝当成空数据继续:" + file
                            + "。下一次写入是全量重写,继续跑会把它覆盖掉。", e);
        }
        try {
            return parse.apply(root);
        } catch (RuntimeException e) {
            throw new IllegalStateException("商业化数据文件内容不合法:" + file, e);
        }
    }

    /**
     * 全量重写 + 原子替换。
     *
     * <p>直接在原文件上截断重写,写到一半断电就是一个半截 JSON —— 整本账一起没。
     * 所以先写 {@code .tmp}、{@code fsync}、再 {@code ATOMIC_MOVE} 顶上去。
     */
    void write(ObjectNode root) {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("商业化数据写盘失败:" + file, e);
        }
    }

    static ObjectNode newObject() {
        return MAPPER.createObjectNode();
    }
}
