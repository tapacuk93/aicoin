package com.aicoin.proxy;

import io.lettuce.core.api.async.RedisAsyncCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The phones a wallet has asked to be told on, per CONTRACT.md's "Push notifications": a durable
 * consortium call that finishes while nobody is watching pushes "Chapter ready" to them.
 *
 * <p>Why the proxy pushes at all: an app that wants to know when a ten-minute call is done used
 * to keep itself alive with iOS's background-task allowance, which iOS cuts short when it likes —
 * 32 seconds once, seven minutes another time — and then shows "task error". A durable call no
 * longer needs the app to be running (it finishes on the proxy regardless), so the only thing
 * left for the app to wait for is the news; a push carries that without the app running at all.
 *
 * <p>Stored per wallet as a sorted set in Redis, scored by when each device registered, holding
 * {@code environment|topic|token}. Registering again moves a device to the front; past
 * {@link #MAX_DEVICES} the oldest are dropped — a wallet is one person's few devices, and a token
 * the phone has long since replaced should not be pushed to forever. A token APNs says is dead
 * ({@code 410}, {@code BadDeviceToken}) is removed when a push to it is refused.
 */
final class PushDevices {

    private static final Logger LOG = Logger.getLogger(PushDevices.class.getName());

    static final int MAX_DEVICES = 10;

    private static final Pattern TOKEN = Pattern.compile("[0-9a-fA-F]{32,400}");
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.\\-]{0,199}");

    /** One registered device. */
    record Device(String topic, String token, String environment) {
        String member() {
            return environment + "|" + topic + "|" + token;
        }

        boolean sandbox() {
            return "sandbox".equals(environment);
        }

        static Device parse(String member) {
            String[] parts = member.split("\\|", 3);
            return parts.length == 3 ? new Device(parts[1], parts[2], parts[0]) : null;
        }
    }

    /** Where devices are kept: Redis in the proxy, a map in tests. */
    interface Store {
        CompletableFuture<Void> add(String wallet, Device device, long nowMillis, int max);

        CompletableFuture<Void> remove(String wallet, Device device);

        CompletableFuture<List<Device>> list(String wallet);
    }

    /**
     * A device from a request body, or the reason it is not one. The token is the hex form iOS
     * hands over as bytes; the environment is which APNs host can reach it — {@code sandbox} for
     * a development build, {@code production} for TestFlight and the App Store.
     */
    static Object parse(Map<?, ?> body) {
        Object token = body.get("token");
        Object topic = body.get("topic");
        Object environment = body.get("environment");
        if (!(token instanceof String) || !TOKEN.matcher((String) token).matches()
                || ((String) token).length() % 2 != 0) {
            return "token must be the device token in hex";
        }
        if (!(topic instanceof String) || !TOPIC.matcher((String) topic).matches()) {
            return "topic must be the app's bundle id";
        }
        if (!"sandbox".equals(environment) && !"production".equals(environment)) {
            return "environment must be sandbox or production";
        }
        return new Device((String) topic, ((String) token).toLowerCase(Locale.ROOT), (String) environment);
    }

    static String key(String wallet) {
        return "aicoin:push:devices:" + wallet;
    }

    /** The ledger's Redis. */
    static final class Redis implements Store {
        private final RedisAsyncCommands<String, String> redis;

        Redis(RedisAsyncCommands<String, String> redis) {
            this.redis = redis;
        }

        @Override
        public CompletableFuture<Void> add(String wallet, Device device, long nowMillis, int max) {
            String key = key(wallet);
            return redis.zadd(key, (double) nowMillis, device.member()).toCompletableFuture()
                    .thenCompose(n -> redis.zremrangebyrank(key, 0, -(max + 1)).toCompletableFuture())
                    .thenApply(n -> null);
        }

        @Override
        public CompletableFuture<Void> remove(String wallet, Device device) {
            return redis.zrem(key(wallet), device.member()).toCompletableFuture().thenApply(n -> null);
        }

        @Override
        public CompletableFuture<List<Device>> list(String wallet) {
            return redis.zrevrange(key(wallet), 0, -1).toCompletableFuture().thenApply(members -> {
                List<Device> devices = new ArrayList<>();
                for (String member : members) {
                    Device device = Device.parse(member);
                    if (device != null) {
                        devices.add(device);
                    }
                }
                return devices;
            });
        }
    }

    /** For tests: devices in memory, newest first. */
    static final class Memory implements Store {
        final Map<String, List<Device>> byWallet = new ConcurrentHashMap<>();

        @Override
        public synchronized CompletableFuture<Void> add(String wallet, Device device, long nowMillis, int max) {
            List<Device> list = byWallet.computeIfAbsent(wallet, w -> new ArrayList<>());
            list.remove(device);
            list.add(0, device);
            while (list.size() > max) {
                list.remove(list.size() - 1);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized CompletableFuture<Void> remove(String wallet, Device device) {
            List<Device> list = byWallet.get(wallet);
            if (list != null) {
                list.remove(device);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized CompletableFuture<List<Device>> list(String wallet) {
            return CompletableFuture.completedFuture(new ArrayList<>(byWallet.getOrDefault(wallet, List.of())));
        }
    }

    /** One push out: the device, the topic, and the whole payload. Apns in the proxy, a fake in tests. */
    interface Sender {
        CompletableFuture<Apns.SendResult> send(Device device, String payload);
    }

    private static volatile PushDevices installed;

    private final Store store;
    private final Sender sender;
    private final AccessLog accessLog;

    PushDevices(Store store, Sender sender, AccessLog accessLog) {
        this.store = store;
        this.sender = sender;
        this.accessLog = accessLog;
    }

    /**
     * A push has no request to hang an access-log line on — it happens when a call ends, often
     * with nobody connected — so it gets a line of its own: {@code PUSH /consortium/<call_id>},
     * status 200 when any device took it (502 when none did, 204 when the wallet had none for
     * the topic), the per-device results as the outcome.
     */
    void log(String wallet, String callId, List<String> results) {
        LOG.info("consortium " + callId + " for " + wallet + " pushed: " + results);
        if (accessLog == null) {
            return;
        }
        boolean anySent = results.stream().anyMatch(r -> r.endsWith(" sent"));
        int status = results.isEmpty() ? 204 : anySent ? 200 : 502;
        accessLog.record("PUSH", "/consortium/" + callId, "apns", wallet, status, 0, 0, 0, 0, 0, "",
                results.isEmpty() ? "notified:no_devices" : "notified:" + String.join("; ", results));
    }

    static void install(PushDevices devices) {
        installed = devices;
    }

    /** The registry, or null when none was installed (no Redis). */
    static PushDevices get() {
        return installed;
    }

    CompletableFuture<Void> register(String wallet, Device device) {
        return store.add(wallet, device, System.currentTimeMillis(), MAX_DEVICES);
    }

    /** The wallet's devices, newest first. */
    List<Device> listForTest(String wallet) {
        return store.list(wallet).join();
    }

    CompletableFuture<Void> unregister(String wallet, Device device) {
        return store.remove(wallet, device);
    }

    /**
     * Pushes one alert to every device this wallet registered for {@code topic}, and reports what
     * each came to ({@code onDone} gets one line per device). A device APNs calls dead is
     * forgotten on the spot.
     */
    void push(String wallet, String topic, String payload, Consumer<List<String>> onDone) {
        store.list(wallet).whenComplete((devices, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "could not read push devices for " + wallet, err);
                onDone.accept(List.of("devices unreadable"));
                return;
            }
            List<CompletableFuture<String>> sends = new ArrayList<>();
            for (Device device : devices) {
                if (!device.topic().equals(topic)) {
                    continue;
                }
                sends.add(sender.send(device, payload).thenApply(result -> {
                    if (result.unregistered()) {
                        store.remove(wallet, device);
                        return shortToken(device) + " dropped (" + result.detail() + ")";
                    }
                    return shortToken(device) + (result.ok() ? " sent" : " failed: " + result.detail());
                }).exceptionally(e -> shortToken(device) + " failed: " + e.getMessage()));
            }
            CompletableFuture.allOf(sends.toArray(new CompletableFuture[0])).whenComplete((v, e) -> {
                List<String> results = new ArrayList<>();
                for (CompletableFuture<String> send : sends) {
                    results.add(send.getNow("unfinished"));
                }
                onDone.accept(results);
            });
        });
    }

    private static String shortToken(Device device) {
        String token = device.token();
        return device.environment() + ":" + (token.length() > 8 ? token.substring(0, 8) + "…" : token);
    }

    /**
     * The APNs payload for a finished call: the alert, and the caller's {@code data} beside
     * {@code aps} as custom keys, plus {@code call_id} so the app knows which call to fetch.
     */
    static String payload(String title, String body, Map<?, ?> data, String callId) {
        StringBuilder json = new StringBuilder("{\"aps\":{\"alert\":{\"title\":").append(Json.string(title))
                .append(",\"body\":").append(Json.string(body == null ? "" : body))
                .append("},\"sound\":\"default\"}");
        if (data != null) {
            for (Map.Entry<?, ?> entry : data.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.equals("aps") || key.equals("call_id")) {
                    continue;
                }
                json.append(",").append(Json.string(key)).append(":").append(Json.write(entry.getValue()));
            }
        }
        return json.append(",\"call_id\":").append(Json.string(callId)).append("}").toString();
    }
}
