package com.aicoin.proxy;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where a durable consortium call — one sent with a {@code call_id} — keeps its progress, per
 * CONTRACT.md's "Durable calls": the request, a checkpoint of the call's state after every turn,
 * every event it has emitted, and a lease saying which process is running it.
 *
 * <p>Why Redis, and the ledger's own: a call outlives the connection that started it (a phone
 * frozen in the background) and the process running it (a redeploy recreates the container), so
 * its state has to live somewhere both the next request and the next process can reach — and the
 * ledger's Redis is the one store every proxy process already shares.
 *
 * <p>Keys, all under {@code aicoin:consortium:<wallet>:<call_id>}, so one wallet's id can never name
 * another wallet's call:
 * <ul>
 *   <li>{@code :request} — the body that started it, written once with {@code NX}: the write that
 *       wins is the call, and a second request with the same id attaches to it instead.</li>
 *   <li>{@code :state} — the latest checkpoint (JSON), replaced whole each time.</li>
 *   <li>{@code :events} — a list of every event line, in {@code seq} order.</li>
 *   <li>{@code :lease} — the id of the process running it, with a short expiry it keeps renewing.</li>
 * </ul>
 * plus the set {@code aicoin:consortium:running} of calls not yet finished, which a process scans
 * for calls whose owner died. Everything but the lease expires after the retention period.
 *
 * <p>Writes go out on the ledger's single Lettuce connection, which sends commands in the order
 * they are issued; the session issues them under its own lock, so a checkpoint can never be
 * overtaken by an older one.
 */
interface ConsortiumStore {

    String RUNNING_KEY = "aicoin:consortium:running";

    /** What a call has stored: everything a replay, an attach or a resume needs. */
    final class Stored {
        final String request;
        final String state;
        final List<String> events;
        final String leaseOwner;

        Stored(String request, String state, List<String> events, String leaseOwner) {
            this.request = request;
            this.state = state;
            this.events = events;
            this.leaseOwner = leaseOwner;
        }
    }

    static String key(String wallet, String callId) {
        return "aicoin:consortium:" + wallet + ":" + callId;
    }

    /** Records a new call. {@code created} is false when the id already names one. */
    void create(String key, String request, String state, String owner, Consumer<Boolean> created);

    void checkpoint(String key, String state);

    void appendEvent(String key, String line);

    /** Replaces one stored event — used to mark a resumed call's start event. */
    void replaceEvent(String key, int index, String line);

    /** Everything stored for the call, or null when there is none. */
    void load(String key, Consumer<Stored> onLoaded);

    /** Claims an unowned call. {@code taken} is false when someone holds it. */
    void takeLease(String key, String owner, Consumer<Boolean> taken);

    void renewLease(String key, String owner);

    /** Gives the call up, if this process holds it; completes when Redis has answered. */
    java.util.concurrent.CompletionStage<?> releaseLease(String key, String owner);

    /** The call is over: it is no longer a candidate for resuming. */
    void finished(String key);

    /**
     * The call is paused: not a candidate for resuming on its own (out of the running set), kept
     * {@code retentionHours} and then gone — and after that, remembered as gone for a week, so a
     * request that comes back for it is told so ({@code 410}) rather than quietly starting a new
     * call under the old id.
     */
    void paused(String key, int retentionHours);

    /** A paused call is running again: back to the ordinary retention, and a resume candidate. */
    void unpaused(String key);

    /** Whether this id named a paused call that has since expired. */
    void gone(String key, Consumer<Boolean> onGone);

    void running(Consumer<List<String>> onKeys);

    /** The ledger's Redis. */
    final class Redis implements ConsortiumStore {
        private static final Logger LOG = Logger.getLogger(ConsortiumStore.class.getName());

        /** Deletes the lease only if this process still holds it. */
        private static final String RELEASE_SCRIPT =
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";
        /** Extends the lease only if this process still holds it — never steals one back. */
        private static final String RENEW_SCRIPT =
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end";

        private final RedisAsyncCommands<String, String> redis;
        private final long retentionSeconds;
        private final long leaseMillis;

        Redis(RedisAsyncCommands<String, String> redis, int retentionHours, int leaseSeconds) {
            this.redis = redis;
            this.retentionSeconds = retentionHours * 3600L;
            this.leaseMillis = leaseSeconds * 1000L;
        }

        private static <T> void logFailure(String what, Throwable err) {
            if (err != null) {
                LOG.log(Level.WARNING, "consortium store: " + what + " failed", err);
            }
        }

        @Override
        public void create(String key, String request, String state, String owner, Consumer<Boolean> created) {
            redis.set(key + ":request", request, SetArgs.Builder.nx().ex(retentionSeconds)).whenComplete((ok, err) -> {
                if (err != null) {
                    // A Redis that cannot be reached must not stop the call: it runs, just without
                    // the durability its id asked for.
                    logFailure("create", err);
                    created.accept(true);
                    return;
                }
                if (!"OK".equals(ok)) {
                    created.accept(false);
                    return;
                }
                redis.set(key + ":state", state, SetArgs.Builder.ex(retentionSeconds));
                redis.set(key + ":lease", owner, SetArgs.Builder.px(leaseMillis));
                redis.sadd(RUNNING_KEY, key);
                created.accept(true);
            });
        }

        @Override
        public void checkpoint(String key, String state) {
            redis.set(key + ":state", state, SetArgs.Builder.ex(retentionSeconds))
                    .whenComplete((ok, err) -> logFailure("checkpoint", err));
        }

        @Override
        public void appendEvent(String key, String line) {
            redis.rpush(key + ":events", line).whenComplete((n, err) -> logFailure("event", err));
            redis.expire(key + ":events", retentionSeconds);
        }

        @Override
        public void replaceEvent(String key, int index, String line) {
            redis.lset(key + ":events", index, line).whenComplete((n, err) -> logFailure("event", err));
        }

        @Override
        public void load(String key, Consumer<Stored> onLoaded) {
            redis.get(key + ":request").whenComplete((request, err) -> {
                if (err != null || request == null) {
                    logFailure("load", err);
                    onLoaded.accept(null);
                    return;
                }
                redis.get(key + ":state").whenComplete((state, err2) ->
                        redis.lrange(key + ":events", 0, -1).whenComplete((events, err3) ->
                                redis.get(key + ":lease").whenComplete((lease, err4) -> onLoaded.accept(new Stored(
                                        request, state, events == null ? new ArrayList<>() : events, lease)))));
            });
        }

        @Override
        public void takeLease(String key, String owner, Consumer<Boolean> taken) {
            redis.set(key + ":lease", owner, SetArgs.Builder.nx().px(leaseMillis))
                    .whenComplete((ok, err) -> taken.accept(err == null && "OK".equals(ok)));
        }

        @Override
        public void renewLease(String key, String owner) {
            redis.eval(RENEW_SCRIPT, io.lettuce.core.ScriptOutputType.INTEGER, new String[] {key + ":lease"},
                    owner, Long.toString(leaseMillis)).whenComplete((n, err) -> logFailure("lease", err));
        }

        @Override
        public java.util.concurrent.CompletionStage<?> releaseLease(String key, String owner) {
            return redis.eval(RELEASE_SCRIPT, io.lettuce.core.ScriptOutputType.INTEGER, new String[] {key + ":lease"},
                    owner).whenComplete((n, err) -> logFailure("release", err));
        }

        @Override
        public void finished(String key) {
            redis.srem(RUNNING_KEY, key).whenComplete((n, err) -> logFailure("finish", err));
        }

        @Override
        public void paused(String key, int retentionHours) {
            long seconds = retentionHours * 3600L;
            redis.srem(RUNNING_KEY, key).whenComplete((n, err) -> logFailure("pause", err));
            for (String part : new String[] {":request", ":state", ":events"}) {
                redis.expire(key + part, seconds);
            }
            redis.set(key + ":gone", "paused", SetArgs.Builder.ex(seconds + 7 * 24 * 3600L));
        }

        @Override
        public void unpaused(String key) {
            for (String part : new String[] {":request", ":state", ":events"}) {
                redis.expire(key + part, retentionSeconds);
            }
            redis.del(key + ":gone");
            redis.sadd(RUNNING_KEY, key).whenComplete((n, err) -> logFailure("unpause", err));
        }

        @Override
        public void gone(String key, Consumer<Boolean> onGone) {
            redis.exists(key + ":gone").whenComplete((n, err) -> onGone.accept(err == null && n != null && n > 0));
        }

        @Override
        public void running(Consumer<List<String>> onKeys) {
            redis.smembers(RUNNING_KEY).whenComplete((keys, err) -> {
                logFailure("scan", err);
                onKeys.accept(keys == null ? List.of() : new ArrayList<>(keys));
            });
        }
    }
}
