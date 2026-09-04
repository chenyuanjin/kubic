package com.kaodian.server.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SignupLedger} 的阶段 0/1 实现 —— <b>一本只追加的账</b>。
 *
 * <p>刻意<b>不和 {@link FileAccountStore} 共用一个文件</b>:那个文件里的账号会被合并、
 * 会被注销;这本账不会。分开是为了让「只追加」这条性质在物理上就成立,
 * 而不是靠每一次改动时记得别碰它。
 *
 * <p>它是阶段 3 判据的数据源,也是这个后端里<b>唯一一个为「判据能不能被算出来」而存在的东西</b>。
 * 总路线图 §六 那条自检说的正是这件事:合规与数据两条轨都能产出让人满意的可量化进展,
 * <b>而两者都不需要面对一个真实用户</b>。这本账上的数字是少数几个必须由真人产生的数之一。
 */
@Component
public class FileSignupLedger implements SignupLedger {

    private static final String FILE_NAME = "auth-signups.json";

    private final AuthJsonFile file;
    private final Object lock = new Object();

    private List<Entry> entries;

    // 🔴 这个类有两个构造器,Spring 挑不出来 —— 少了这个注解,启动期报的是
    // 「No default constructor found」,而那句话和真正的原因(构造器歧义)毫无关系。
    // 另一个构造器是给测试用的:它直接收 Path,不碰配置也不碰用户目录。
    @Autowired
    public FileSignupLedger(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public FileSignupLedger(Path file) {
        this.file = new AuthJsonFile(file);
    }

    @Override
    public void record(Entry entry) {
        synchronized (lock) {
            ensureLoaded();
            List<Entry> next = new ArrayList<>(entries);
            next.add(entry);
            persist(next);
        }
    }

    @Override
    public int totalCount() {
        synchronized (lock) {
            ensureLoaded();
            return entries.size();
        }
    }

    @Override
    public List<Entry> all() {
        synchronized (lock) {
            ensureLoaded();
            return List.copyOf(entries);
        }
    }

    private void ensureLoaded() {
        if (entries != null) {
            return;
        }
        entries = file.read(FileSignupLedger::parse, ArrayList::new);
    }

    private void persist(List<Entry> next) {
        ObjectNode root = file.newRoot(
                "建账号流水 —— 阶段 3「累计 50 个陌生注册」的唯一数据源。",
                "🔴 只追加。注销不删、合并不删 —— 累计指标不能往回走。",
                "⚪ 「陌生」两个字数据里没有:referrer 是给人工判定用的线索,不是自动算出来的结论。");
        ArrayNode arr = root.putArray("signups");
        for (Entry e : next) {
            arr.add(toNode(e));
        }
        file.write(root);
        entries = next;
    }

    private static List<Entry> parse(JsonNode root) {
        JsonNode arr = root.path("signups");
        if (!arr.isArray()) {
            throw new IllegalStateException("注册流水文件里没有 signups 数组");
        }
        List<Entry> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(new Entry(
                    requiredLong(n, "userId"),
                    Instant.parse(required(n, "at")),
                    IdentityType.ofWireName(required(n, "channel")),
                    n.path("referrer").asString(null)));
        }
        return out;
    }

    private static ObjectNode toNode(Entry e) {
        ObjectNode o = AuthJsonFile.mapper().createObjectNode();
        o.put("userId", e.userId());
        o.put("at", e.at().toString());
        o.put("channel", e.channel().wireName());
        if (e.referrer() != null && !e.referrer().isBlank()) {
            o.put("referrer", e.referrer());
        }
        return o;
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asString("");
        if (v.isEmpty()) {
            throw new IllegalStateException("注册流水缺少必填字段:" + field);
        }
        return v;
    }

    /** userId 是 int64(B0-2 §3.3)。 */
    private static long requiredLong(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isIntegralNumber()) {
            throw new IllegalStateException("注册流水的 " + field + " 不是 int64:" + v
                    + " —— B0-2 之前的存量数据?删掉 ~/.kaodian/auth-*.json 重新注册即可");
        }
        return v.longValue();
    }
}
