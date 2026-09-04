package com.kaodian.server.collect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存量「不归任何人」的行为层数据 —— B0-3(`B0-平台底座与横切契约` §4.4)。
 *
 * <h2>三种情形,三种处置</h2>
 *
 * <table border="1">
 *   <caption>启动期判定</caption>
 *   <tr><th>情形</th><th>处置</th></tr>
 *   <tr><td>三个文件全空(或都不存在)</td><td>放行 —— 全新环境</td></tr>
 *   <tr><td>有没带归属的条目,{@code kaodian.collect.accept-orphan-loss=false}(默认)</td>
 *       <td><b>拒绝启动</b>,消息里写明三个文件<b>各有多少条</b></td></tr>
 *   <tr><td>同上,配置为 {@code true}</td>
 *       <td>放行,<b>丢弃</b>那些条目,记一条 ERROR + 每个文件的确切条数</td></tr>
 * </table>
 *
 * <h2>🔴 为什么是丢弃,不是认领给第一个用户</h2>
 *
 * 认领的那一版会把<b>别人记的东西算进这个人的覆盖度</b> —— 而覆盖度是这个产品
 * <b>唯一的那个指标</b>。宁可丢一批开发期的假数据,不可以让唯一的指标带着一批
 * 来路不明的记录出生。这与「宁缺毋滥」是同一条推理:<b>错的标签比没有标签更贵。</b>
 * <p>
 * 而且认领是<b>不可逆且无声</b>的:那些记录从此看起来就是这个人自己记的,
 * 没有任何一行数据能说出它们本来不知道属于谁。丢弃至少留下了日志里的确切条数。
 *
 * <h2>形态照抄 {@code PhoneKeyGuard}:有守卫、有出路、出路留痕</h2>
 *
 * 一个<b>没有出路的守卫,会被撞上它的人在半夜关掉</b>,然后它就永远关着了。
 * 所以这里给的不是「跳过检查」,是一次显式的确认:
 * {@code kaodian.collect.accept-orphan-loss=true} 的意思是
 * 「我确认这批记录找不回主人了,请把它们丢掉并告诉我丢了多少条」。
 *
 * <h2>为什么它自己读文件,而不是问三个 store</h2>
 *
 * store 是<b>懒加载</b>的(第一次访问才读盘,理由见 {@code FileTouchStore#ensureLoaded}),
 * 而这道判定必须在<b>任何一次请求之前</b>做完 —— 懒检查意味着第一个用户已经看到了
 * 一份被悄悄丢过东西的覆盖度。它读的也只有一个字段:每个条目上有没有一个正的 {@code userId}。
 */
@Component
public class OrphanGuard {

    private static final Logger log = LoggerFactory.getLogger(OrphanGuard.class);

    /** 三个文件里那一列的键名。<b>写一处</b> —— 读、写、判孤儿三处引用它。 */
    public static final String USER_ID = "userId";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<Path, Integer> orphans;

    /**
     * @param acceptOrphanLoss 🔴 默认 {@code false}。置 {@code true} 是一次不可逆的确认,不是开关
     */
    @Autowired
    public OrphanGuard(FileTouchStore touches, FileRecordTagStore tags, FileAssertionStore assertions,
                       @Value("${kaodian.collect.accept-orphan-loss:false}") boolean acceptOrphanLoss) {
        this(acceptOrphanLoss, filesOf(touches, tags, assertions));
    }

    /** 顺序固定 —— 拒绝启动那条消息里三行的次序不该每次不一样。 */
    private static Map<Path, String> filesOf(FileTouchStore touches, FileRecordTagStore tags,
                                             FileAssertionStore assertions) {
        Map<Path, String> m = new LinkedHashMap<>();
        m.put(touches.dataFile(), "touches");
        m.put(tags.dataFile(), "tags");
        m.put(assertions.dataFile(), "assertions");
        return m;
    }

    /**
     * @param arrayKeyByFile 数据文件 → 它里面那个数组的键名。
     *                       三个文件的数组键各不相同({@code touches} / {@code tags} / {@code assertions}),
     *                       而键名本来就是各自 {@code parse} 里写死的那一个
     */
    OrphanGuard(boolean acceptOrphanLoss, Map<Path, String> arrayKeyByFile) {
        this.orphans = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<Path, String> e : arrayKeyByFile.entrySet()) {
            int n = countOrphans(e.getKey(), e.getValue());
            orphans.put(e.getKey(), n);
            total += n;
        }

        if (total == 0) {
            return;                             // 全新环境,或者存量数据已经都带着归属
        }
        if (!acceptOrphanLoss) {
            throw new OrphanDataException(orphans, total);
        }
        // 🔴 明确记下条数 —— 将来「覆盖度怎么少了一块」时,这一行是唯一的线索。
        log.error("【已确认丢弃无归属的行为层数据】共 {} 条,它们不会被读进内存,也不会被认领给任何用户:{}"
                        + " —— 认领会把别人记的东西算进某个人的覆盖度,而覆盖度是这个产品唯一的那个指标"
                        + "(B0 §4.4)。这批条目仍然留在磁盘文件里,下一次写盘时被全量重写覆盖。",
                total, describe(orphans));
    }

    /** 各文件的孤儿条数。留给测试与排查用,不参与任何业务判断。 */
    public Map<Path, Integer> orphanCounts() {
        return Map.copyOf(orphans);
    }

    /**
     * 这个条目有没有归属。
     *
     * <p>判据只有一条:有一个<b>正的</b> {@code userId}。{@code 0} 与负数都算没有 ——
     * {@code 0} 不是「暂时没有用户」的意思(B0 §3.3,auth 侧从 10001 起号)。
     */
    public static boolean isOrphan(JsonNode entry) {
        return entry.path(USER_ID).asLong(0) <= 0;
    }

    private static int countOrphans(Path file, String arrayKey) {
        if (!Files.exists(file)) {
            return 0;
        }
        JsonNode root;
        try (InputStream in = Files.newInputStream(file)) {
            root = MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("行为层数据文件读取失败:" + file, e);
        }
        JsonNode array = root.path(arrayKey);
        if (!array.isArray()) {
            // 坏文件由各自的 store 去吵(它们的消息里写着为什么不能当成 0 条)。
            // 这道守卫只回答「有没有孤儿」,它不该抢先给出另一句解释。
            return 0;
        }
        int n = 0;
        for (JsonNode entry : array) {
            if (isOrphan(entry)) {
                n++;
            }
        }
        return n;
    }

    private static String describe(Map<Path, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        counts.forEach((file, n) -> sb.append("\n      ").append(file).append(" —— ").append(n).append(" 条"));
        return sb.toString();
    }

    /**
     * 有存量数据不归任何人 —— <b>拒绝启动</b>。
     *
     * <p>消息里把出路写全。一个只说「有孤儿数据」的异常,会让人直接去找怎么关掉它。
     */
    public static class OrphanDataException extends IllegalStateException {

        public OrphanDataException(Map<Path, Integer> counts, int total) {
            super("""
                    拒绝启动:行为层里有 %d 条记录不归任何用户(B0-3 §4.4)。
                    %s

                    这些条目是租户列上线之前写下的。继续跑下去不会报错 ——
                    它们读不进内存,于是覆盖度会静静地少一块,而没有人知道少的是什么。

                    两条出路:
                      ① 这是你自己开发期的数据,而且你不需要它:
                         kaodian.collect.accept-orphan-loss=true
                         启动时会把它们丢掉,并在日志里记下每个文件的确切条数。
                         🔴 它不是「跳过检查」,是一次不可逆的确认。
                      ② 你想留着这批数据:把数据目录整个挪走(备份,不要删),
                         让服务从一个干净的目录重新开始。
                         🔴 【不要】自己给它们补一个 userId —— 认领会把别人记的东西
                         算进某个人的覆盖度,而覆盖度是这个产品唯一的那个指标。
                         宁可丢一批开发期的假数据,不可以让唯一的指标带着一批
                         来路不明的记录出生(与「宁缺毋滥」同一条推理)。"""
                    .formatted(total, describe(counts)));
        }
    }
}
