package com.kaodian.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link AccountStore} 的阶段 0/1 实现 —— 一个 JSON 文件。
 *
 * <p>四类数据放在同一个文件里(账号 / 身份 / 手机号密文 / 合并留痕),
 * 因为它们必须<b>一起原子落盘</b>:建账号是「写 app_user + 写 user_identity」两件事,
 * 分两个文件就出现了「账号建了但登不进去」的中间态。
 * 到了 {@code 1.2.4} 换 JDBC 那天,这一条会变成一个事务 —— 形状是一样的。
 */
@Component
public class FileAccountStore implements AccountStore {

    private static final Logger log = LoggerFactory.getLogger(FileAccountStore.class);

    private static final String FILE_NAME = "auth-accounts.json";

    private final AuthJsonFile file;
    private final Object lock = new Object();

    private State state;

    /**
     * 已经发出去的最大账号 id —— <b>进程内的水位,不落盘</b>,见 {@link #nextUserId()}。
     *
     * <p>不落盘是有意的:它只需要保证<b>同一个进程里</b>不重复发号,
     * 而跨重启的那一半由「已有账号的最大 id」兜住。落盘反而多一个会和账号数据写不同步的东西。
     */
    private long issuedHighWaterMark;

    // 🔴 这个类有两个构造器,Spring 挑不出来 —— 少了这个注解,启动期报的是
    // 「No default constructor found」,而那句话和真正的原因(构造器歧义)毫无关系。
    // 另一个构造器是给测试用的:它直接收 Path,不碰配置也不碰用户目录。
    @Autowired
    public FileAccountStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public FileAccountStore(Path file) {
        this.file = new AuthJsonFile(file);
    }

    public Path dataFile() {
        return file.path();
    }

    @Override
    public Optional<AppUser> findById(long userId) {
        synchronized (lock) {
            ensureLoaded();
            return Optional.ofNullable(state.users.get(userId));
        }
    }

    @Override
    public Optional<AppUser> findByIdentity(IdentityType type, String identifier) {
        synchronized (lock) {
            ensureLoaded();
            UserIdentity id = state.identities.get(UserIdentity.uniqueKey(type, identifier));
            return id == null ? Optional.empty() : Optional.ofNullable(state.users.get(id.userId()));
        }
    }

    /**
     * 发号。
     *
     * <h2>🔴 光「读最大值 + 1」是不够的 —— 那是这次换 id 形态时被并发测试当场抓住的一个 bug</h2>
     *
     * {@link AccountService#createOrJoin} 里发号与建号是<b>两次</b>调用,中间锁是放开的。
     * 只读最大值的话,两个并发的建号会拿到<b>同一个 10001</b>,
     * 第二个撞在 {@link #create} 的「账号 id 已存在」上 ——
     * 而那是 {@link IllegalStateException},<b>不在 {@code createOrJoin} 的捕获范围里</b>,
     * 一路逃成 500。用户在登录页连点两次就能踩到。
     * <p>
     * 旧的 {@code u_}+UUID 形态天然躲开了这一格(随机不用读),
     * <b>换成连续 id 就必须自己把这件事补上</b>。
     *
     * <h2>怎么补:进程内一个只增不减的水位</h2>
     *
     * 发出去的号立刻推高水位,所以同一个号不会被发第二次。
     * 重启后水位从「已有账号的最大 id」重算 —— <b>发出去但没用掉的号会被重新发一次</b>,
     * 而那是安全的:没建成账号,那个号在任何地方都没有引用。
     * <p>
     * ⚠️ <b>id 因此是不连续的</b>(建号撞上身份冲突就会烧掉一个号)。
     * 这是有意的:发号器保证「不重复」,不保证「不跳号」——
     * 反过来要求不跳号,就得在失败时把号还回去,而那是一个会写错的分布式回滚。
     *
     * <p>从<b>最大值</b>往上走而不是「已有条数 + 起始号」:后者在删过任何一条之后
     * 会重新发出一个用过的号,而那个号在令牌文件、注册流水里还留着引用 ——
     * 新账号会继承别人的会话。本层今天不硬删账号(注销只改状态),
     * 但发号器<b>不该依赖那个前提</b>:它是一条会被后来人改掉的实现细节,而这里错了是静默的。
     */
    @Override
    public long nextUserId() {
        synchronized (lock) {
            ensureLoaded();
            long maxExisting = state.users.keySet().stream()
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(AppUser.FIRST_USER_ID - 1);
            long issued = Math.max(issuedHighWaterMark, maxExisting) + 1;
            issuedHighWaterMark = issued;
            return issued;
        }
    }

    @Override
    public int countCreated() {
        synchronized (lock) {
            ensureLoaded();
            return state.users.size();
        }
    }

    @Override
    public List<UserIdentity> identitiesOf(long userId) {
        synchronized (lock) {
            ensureLoaded();
            return state.identities.values().stream()
                    .filter(i -> i.userId() == userId)
                    .toList();
        }
    }

    @Override
    public AppUser create(AppUser user, UserIdentity firstIdentity, PhoneNumberSecret phoneSecret) {
        synchronized (lock) {
            ensureLoaded();
            if (state.users.containsKey(user.id())) {
                throw new IllegalStateException("账号 id 已存在:" + user.id());
            }
            if (firstIdentity.userId() != user.id()) {
                throw new IllegalArgumentException("第一条身份必须属于这个新账号");
            }
            UserIdentity existing = state.identities.get(firstIdentity.uniqueKey());
            if (existing != null) {
                throw new IdentityTakenException("这个身份已经绑在别的账号上", existing.userId());
            }
            State next = state.copy();
            next.users.put(user.id(), user);
            next.identities.put(firstIdentity.uniqueKey(), firstIdentity);
            if (phoneSecret != null) {
                next.phoneSecrets.put(user.id(), phoneSecret);
            }
            persist(next);
            return user;
        }
    }

    @Override
    public void addIdentity(UserIdentity identity, PhoneNumberSecret phoneSecret) {
        synchronized (lock) {
            ensureLoaded();
            if (!state.users.containsKey(identity.userId())) {
                throw new IllegalStateException("账号不存在:" + identity.userId());
            }
            UserIdentity existing = state.identities.get(identity.uniqueKey());
            if (existing != null) {
                if (existing.userId() == identity.userId()) {
                    return;                     // 已经绑过了。重复绑定是幂等的,不报错
                }
                throw new IdentityTakenException("这个身份已经绑在别的账号上", existing.userId());
            }
            // 一个账号只能有一个手机号:再绑一个新号,旧号那一行必须先解绑。
            // 不做这个检查的话,一个账号会挂着两个 phone identity,而「我的手机号是哪个」没有答案。
            if (identity.type() == IdentityType.PHONE) {
                boolean hasPhone = state.identities.values().stream()
                        .anyMatch(i -> i.userId() == identity.userId() && i.type() == IdentityType.PHONE);
                if (hasPhone) {
                    throw new IllegalStateException("这个账号已经绑了手机号,换号请先解绑");
                }
            }
            State next = state.copy();
            next.identities.put(identity.uniqueKey(), identity);
            if (phoneSecret != null) {
                next.phoneSecrets.put(identity.userId(), phoneSecret);
            }
            persist(next);
        }
    }

    @Override
    public Optional<PhoneNumberSecret> phoneSecretOf(long userId) {
        synchronized (lock) {
            ensureLoaded();
            return Optional.ofNullable(state.phoneSecrets.get(userId));
        }
    }

    @Override
    public void deactivate(long userId, Instant now) {
        synchronized (lock) {
            ensureLoaded();
            AppUser u = state.users.get(userId);
            if (u == null) {
                throw new IllegalStateException("账号不存在:" + userId);
            }
            if (!u.isActive()) {
                return;                          // 幂等
            }
            State next = state.copy();
            next.users.put(userId, u.deactivated(now));
            // 🔴 identity 一并摘掉。不摘的话,那个手机号永远登不回来也永远给不了别人 ——
            // 而手机号是会被运营商回收的(docs/technical/INDEX.md §7.1)。
            // 摘掉之后,同一个号再来就是一次全新的注册,那正是「注销」该有的意思。
            next.identities.values().removeIf(i -> i.userId() == userId);
            next.phoneSecrets.remove(userId);
            persist(next);
        }
    }

    @Override
    public AccountMergeLog merge(long fromUserId, long toUserId, int movedRecordCount, Instant now) {
        synchronized (lock) {
            ensureLoaded();
            AppUser from = state.users.get(fromUserId);
            AppUser to = state.users.get(toUserId);
            if (from == null || to == null) {
                throw new IllegalStateException("要合并的账号不存在");
            }
            if (!to.isActive()) {
                throw new IllegalStateException("目标账号已注销,不能作为合并去向");
            }
            State next = state.copy();
            java.util.Set<IdentityType> dropped = new java.util.LinkedHashSet<>();
            List<UserIdentity> moving = next.identities.values().stream()
                    .filter(i -> i.userId() == fromUserId)
                    .toList();
            for (UserIdentity i : moving) {
                UserIdentity moved = new UserIdentity(toUserId, i.type(), i.identifier(), i.boundAt());
                // 目标账号已经有同类型 identity 时,被并走的那条直接丢弃而不是覆盖:
                // 覆盖会让留下来的那个账号的手机号在用户毫不知情的情况下换成另一个号。
                boolean conflict = next.identities.values().stream()
                        .anyMatch(x -> x.userId() == toUserId && x.type() == i.type());
                next.identities.remove(i.uniqueKey());
                if (conflict) {
                    // 🔴 目标账号已经有同类型身份 —— 被并走的这一条<b>就此消失</b>。
                    // 覆盖它更糟(留下来那个账号的手机号会在用户毫不知情时换成另一个号),
                    // 但「丢弃」也不是没有代价:那个手机号从此登不回任何账号。
                    // 所以这里必须留一条 WARN —— 用户三个月后问「我那个号怎么登不上了」,这是唯一的线索。
                    dropped.add(i.type());
                    log.warn("合并丢弃来源账号的 {} 身份:目标账号 {} 已有同类型身份。"
                            + "该凭证从此不再指向任何账号", i.type().wireName(), toUserId);
                } else {
                    next.identities.put(moved.uniqueKey(), moved);
                }
            }
            next.users.put(fromUserId, from.deactivated(now));
            next.phoneSecrets.remove(fromUserId);
            AccountMergeLog log = new AccountMergeLog(fromUserId, toUserId, movedRecordCount, now);
            next.mergeLogs.add(log);
            persist(next);
            return log;
        }
    }

    @Override
    public List<AccountMergeLog> mergeLogs() {
        synchronized (lock) {
            ensureLoaded();
            return List.copyOf(state.mergeLogs);
        }
    }

    // —— 密钥指纹与换钥(R-59)——

    @Override
    public Optional<PhoneKeyFingerprint> keyFingerprint() {
        synchronized (lock) {
            ensureLoaded();
            return Optional.ofNullable(state.fingerprint);
        }
    }

    @Override
    public void stampKeyFingerprint(PhoneKeyFingerprint fingerprint) {
        synchronized (lock) {
            ensureLoaded();
            State next = state.copy();
            next.fingerprint = fingerprint;
            persist(next);
        }
    }

    @Override
    public int phoneIdentityCount() {
        synchronized (lock) {
            ensureLoaded();
            return (int) state.identities.values().stream()
                    .filter(i -> i.type() == IdentityType.PHONE)
                    .count();
        }
    }

    /**
     * 换钥。<b>一次性重算全部手机号,一次性落盘。</b>
     *
     * <p>不变式在这里兑现:重写 {@code identities} 的键与 {@code identifier} 时,
     * 必须和新的 {@code phoneSecret.hmac()} 严格一致 —— 对不上的那一条就是一个
     * 谁也登不进去的账号,而且不报错。所以下面用<b>断言</b>而不是「尽量」。
     */
    @Override
    public int rekeyPhones(java.util.function.UnaryOperator<PhoneNumberSecret> rehash,
                           PhoneKeyFingerprint newFingerprint) {
        synchronized (lock) {
            ensureLoaded();
            State next = state.copy();
            int n = 0;
            for (Map.Entry<Long, PhoneNumberSecret> e : state.phoneSecrets.entrySet()) {
                long userId = e.getKey();
                PhoneNumberSecret old = e.getValue();
                PhoneNumberSecret fresh = rehash.apply(old);

                UserIdentity id = next.identities.remove(
                        UserIdentity.uniqueKey(IdentityType.PHONE, old.hmac()));
                if (id == null) {
                    // 数据本身已经不自洽了(密文在、身份不在)。这时候继续换钥只会把问题埋得更深。
                    throw new IllegalStateException(
                            "账号 " + userId + " 有手机号密文却没有对应的身份行 —— 数据已不一致,拒绝换钥");
                }
                next.identities.put(
                        UserIdentity.uniqueKey(IdentityType.PHONE, fresh.hmac()),
                        new UserIdentity(id.userId(), IdentityType.PHONE, fresh.hmac(), id.boundAt()));
                next.phoneSecrets.put(userId, fresh);
                n++;
            }
            next.fingerprint = newFingerprint;
            persist(next);          // 🔴 全部改完才写一次。中途失败 = 这次换钥没发生,旧数据完好
            return n;
        }
    }

    // —— 载入与落盘 ——

    private void ensureLoaded() {
        if (state != null) {
            return;
        }
        state = file.read(FileAccountStore::parse, State::new);
    }

    private void persist(State next) {
        ObjectNode root = file.newRoot(
                "账号 / 身份 / 手机号密文 / 合并留痕。",
                "🔴 这里没有一个手机号明文:identifier 是 HMAC,phoneEnc 是 AES-GCM 密文(docs/technical/INDEX.md §5.2)。",
                "主表不放任何登录凭证 —— 凭证一律在 identities 里,一个通道一行。",
                "keyFingerprint 是盖在这份数据上的密钥指纹(R-59):它推不回密钥,只用来发现「换了密钥」。");
        ArrayNode users = root.putArray("users");
        for (AppUser u : next.users.values()) {
            users.add(toNode(u));
        }
        ArrayNode ids = root.putArray("identities");
        for (UserIdentity i : next.identities.values()) {
            ids.add(toNode(i));
        }
        ArrayNode secrets = root.putArray("phoneSecrets");
        for (Map.Entry<Long, PhoneNumberSecret> e : next.phoneSecrets.entrySet()) {
            secrets.add(toNode(e.getKey(), e.getValue()));
        }
        if (next.fingerprint != null) {
            // 🔴 和账号数据同一次原子落盘。分两次写就会出现「盖了章但数据没写进去」的中间态,
            // 而那正好会让下一次启动认为「密钥没变」——把 R-59 的守卫本身骗过去。
            ObjectNode fp = root.putObject("keyFingerprint");
            fp.put("hmacKeyId", next.fingerprint.hmacKeyId());
            fp.put("aesKeyId", next.fingerprint.aesKeyId());
        }
        ArrayNode merges = root.putArray("mergeLogs");
        for (AccountMergeLog m : next.mergeLogs) {
            merges.add(toNode(m));
        }
        file.write(root);
        state = next;
    }

    private static final class State {

        final Map<Long, AppUser> users = new LinkedHashMap<>();
        /** 键是 {@link UserIdentity#uniqueKey()} —— 唯一索引就是这个 Map 本身。 */
        final Map<String, UserIdentity> identities = new LinkedHashMap<>();
        final Map<Long, PhoneNumberSecret> phoneSecrets = new LinkedHashMap<>();
        final List<AccountMergeLog> mergeLogs = new ArrayList<>();

        /** 🔴 盖在这份数据上的密钥指纹({@code R-59})。老文件里没有,为 null。 */
        PhoneKeyFingerprint fingerprint;

        State copy() {
            State s = new State();
            s.users.putAll(users);
            s.identities.putAll(identities);
            s.phoneSecrets.putAll(phoneSecrets);
            s.mergeLogs.addAll(mergeLogs);
            s.fingerprint = fingerprint;
            return s;
        }
    }

    private static State parse(JsonNode root) {
        State s = new State();
        for (JsonNode n : requireArray(root, "users")) {
            String deleted = n.path("deletedAt").asString("");
            AppUser u = new AppUser(
                    AuthJsonFile.userId(n, "id"),
                    n.path("nickname").asString(null),
                    AccountStatus.valueOf(required(n, "status")),
                    Instant.parse(required(n, "createdAt")),
                    deleted.isEmpty() ? null : Instant.parse(deleted));
            s.users.put(u.id(), u);
        }
        for (JsonNode n : requireArray(root, "identities")) {
            UserIdentity i = new UserIdentity(
                    AuthJsonFile.userId(n, "userId"),
                    IdentityType.ofWireName(required(n, "type")),
                    required(n, "identifier"),
                    Instant.parse(required(n, "boundAt")));
            s.identities.put(i.uniqueKey(), i);
        }
        for (JsonNode n : requireArray(root, "phoneSecrets")) {
            s.phoneSecrets.put(AuthJsonFile.userId(n, "userId"), new PhoneNumberSecret(
                    required(n, "phoneHmac"),
                    required(n, "phoneEnc"),
                    n.path("masked").asString("")));
        }
        JsonNode fp = root.path("keyFingerprint");
        if (fp.isObject()) {
            s.fingerprint = new PhoneKeyFingerprint(
                    required(fp, "hmacKeyId"), required(fp, "aesKeyId"));
        }
        for (JsonNode n : requireArray(root, "mergeLogs")) {
            s.mergeLogs.add(new AccountMergeLog(
                    AuthJsonFile.userId(n, "fromUserId"),
                    AuthJsonFile.userId(n, "toUserId"),
                    n.path("movedRecordCount").asInt(0),
                    Instant.parse(required(n, "mergedAt"))));
        }
        return s;
    }

    /** 🔴 逐字段写。文件里能出现哪些键由这几个方法显式列出。 */
    private static ObjectNode toNode(AppUser u) {
        ObjectNode o = AuthJsonFile.mapper().createObjectNode();
        o.put("id", AuthJsonFile.userIdString(u.id()));
        if (u.nickname() != null) {
            o.put("nickname", u.nickname());
        }
        o.put("status", u.status().name());
        o.put("createdAt", u.createdAt().toString());
        if (u.deletedAt() != null) {
            o.put("deletedAt", u.deletedAt().toString());
        }
        return o;
    }

    private static ObjectNode toNode(UserIdentity i) {
        ObjectNode o = AuthJsonFile.mapper().createObjectNode();
        o.put("userId", AuthJsonFile.userIdString(i.userId()));
        o.put("type", i.type().wireName());
        o.put("identifier", i.identifier());        // 手机号这一行里是 HMAC,不是号码
        o.put("boundAt", i.boundAt().toString());
        return o;
    }

    private static ObjectNode toNode(long userId, PhoneNumberSecret p) {
        ObjectNode o = AuthJsonFile.mapper().createObjectNode();
        o.put("userId", AuthJsonFile.userIdString(userId));
        o.put("phoneHmac", p.hmac());
        o.put("phoneEnc", p.ciphertext());
        o.put("masked", p.masked());
        return o;
    }

    private static ObjectNode toNode(AccountMergeLog m) {
        ObjectNode o = AuthJsonFile.mapper().createObjectNode();
        o.put("fromUserId", AuthJsonFile.userIdString(m.fromUserId()));
        o.put("toUserId", AuthJsonFile.userIdString(m.toUserId()));
        o.put("movedRecordCount", m.movedRecordCount());
        o.put("mergedAt", m.mergedAt().toString());
        return o;
    }

    private static JsonNode requireArray(JsonNode root, String field) {
        JsonNode n = root.path(field);
        if (!n.isArray()) {
            throw new IllegalStateException("账号文件里没有 " + field + " 数组");
        }
        return n;
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asString("");
        if (v.isEmpty()) {
            throw new IllegalStateException("账号记录缺少必填字段:" + field);
        }
        return v;
    }
}
