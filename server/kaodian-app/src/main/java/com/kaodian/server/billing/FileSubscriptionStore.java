package com.kaodian.server.billing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 订阅存储的文件实现。一人一行({@code userId} 唯一),续费<b>延长 {@code expiresAt}</b>。
 *
 * <p>🔴 落盘的键里<b>没有 {@code status}</b> —— 见 {@link Subscription} 类注释:
 * 那一列是 {@code expiresAt} 的第二真源。
 */
@Component
public class FileSubscriptionStore implements SubscriptionStore {

    private static final String FILE_NAME = "billing-subscriptions.json";

    private final BillingJsonFile file;
    private final Object lock = new Object();

    private Map<Long, Subscription> subscriptions;

    @Autowired
    public FileSubscriptionStore(@Value("${kaodian.data.dir:${user.home}/.kaodian}") String dataDir) {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    public FileSubscriptionStore(Path file) {
        this.file = new BillingJsonFile(file);
    }

    public Path dataFile() {
        return file.path();
    }

    @Override
    public Optional<Subscription> find(long userId) {
        synchronized (lock) {
            ensureLoaded();
            return Optional.ofNullable(subscriptions.get(userId));
        }
    }

    @Override
    public Subscription save(Subscription subscription) {
        synchronized (lock) {
            ensureLoaded();
            Map<Long, Subscription> next = new LinkedHashMap<>(subscriptions);
            next.put(subscription.userId(), subscription);
            file.write(toJson(next));
            this.subscriptions = next;
            return subscription;
        }
    }

    private void ensureLoaded() {
        if (subscriptions == null) {
            subscriptions = file.read(FileSubscriptionStore::parse, LinkedHashMap::new);
        }
    }

    private static ObjectNode toJson(Map<Long, Subscription> subscriptions) {
        ObjectNode root = BillingJsonFile.newObject();
        ArrayNode array = root.putArray("subscriptions");
        for (Subscription s : subscriptions.values()) {
            ObjectNode n = array.addObject();
            n.put("userId", s.userId());
            n.put("planCode", s.planCode());
            n.put("expiresAt", s.expiresAt() == null ? null : s.expiresAt().toString());
        }
        return root;
    }

    private static Map<Long, Subscription> parse(JsonNode root) {
        Map<Long, Subscription> subscriptions = new LinkedHashMap<>();
        for (JsonNode n : root.path("subscriptions")) {
            JsonNode expires = n.get("expiresAt");
            Subscription s = new Subscription(
                    n.get("userId").asLong(),
                    n.get("planCode").asString(),
                    expires == null || expires.isNull() ? null : Instant.parse(expires.asString()));
            subscriptions.put(s.userId(), s);
        }
        return subscriptions;
    }
}
