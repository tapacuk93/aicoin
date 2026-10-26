package com.aicoin.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The durable consortium calls this process knows about, per CONTRACT.md's "Durable calls": the
 * ones it is running, and the way to every other one — finished, running elsewhere, or orphaned
 * by a process that died.
 *
 * <p>Ownership is a lease in Redis that the running process keeps renewing. That is the only way a
 * process can tell "someone is running this" from "someone was running this and is gone": a
 * process that dies cannot say so, it just stops renewing. Once a lease runs out, the call is
 * picked up — by a client coming back for it, or by the periodic scan below, so a redeploy does not
 * leave calls waiting for a client that may not return for hours. A process shutting down cleanly
 * gives its leases up at once, so the process that replaces it resumes them within seconds.
 */
final class ConsortiumCalls {

    private static final Logger LOG = Logger.getLogger(ConsortiumCalls.class.getName());

    private static volatile ConsortiumCalls installed;

    /** This process, for leases. Random per start: a restarted process is a different owner. */
    final String owner = UUID.randomUUID().toString();
    private final ConsortiumStore store;
    private final ProxyConfig config;
    private final EventLoopGroup group;
    private final ProviderHealthTracker healthTracker;
    private final AicoinLedger ledger;
    private final Map<String, ConsortiumSession> live = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "consortium-calls");
        thread.setDaemon(true);
        return thread;
    });

    ConsortiumCalls(ConsortiumStore store, ProxyConfig config, EventLoopGroup group,
                    ProviderHealthTracker healthTracker, AicoinLedger ledger) {
        this.store = store;
        this.config = config;
        this.group = group;
        this.healthTracker = healthTracker;
        this.ledger = ledger;
    }

    static void install(ConsortiumCalls calls) {
        installed = calls;
    }

    /** The registry, or null when none was installed (tests, or a proxy without Redis). */
    static ConsortiumCalls get() {
        return installed;
    }

    ConsortiumStore store() {
        return store;
    }

    /** Renews this process's leases, and picks up orphaned calls — now, then on a timer. */
    void start() {
        long lease = config.getConsortium().getCallLeaseSeconds();
        timer.scheduleAtFixedRate(this::renewLeases, lease / 3, lease / 3, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(this::resumeOrphans, 2, lease / 2, TimeUnit.SECONDS);
    }

    /** Gives every lease up, so the next process resumes these calls without waiting them out. */
    void stop() {
        timer.shutdownNow();
        for (String key : live.keySet()) {
            try {
                store.releaseLease(key, owner).toCompletableFuture().get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "could not release consortium lease " + key, e);
            }
        }
    }

    private void renewLeases() {
        for (String key : live.keySet()) {
            store.renewLease(key, owner);
        }
    }

    private void resumeOrphans() {
        store.running(keys -> {
            for (String key : keys) {
                if (!live.containsKey(key)) {
                    store.takeLease(key, owner, taken -> {
                        if (taken) {
                            store.load(key, stored -> resumeOrRelease(key, stored, null));
                        }
                    });
                }
            }
        });
    }

    /**
     * Starts a new durable call, unless the id already names one — two requests racing with the
     * same id get one call between them, and the loser attaches to it.
     */
    void create(ConsortiumSession session, String requestBody, ChannelHandlerContext ctx, boolean stream) {
        String key = session.storeKey();
        ConsortiumSession existing = live.putIfAbsent(key, session);
        if (existing != null) {
            existing.attach(ctx, stream);
            return;
        }
        store.create(key, requestBody, session.initialState(), owner, created -> {
            if (!created) {
                live.remove(key, session);
                open(ctx, stream, key);
                return;
            }
            session.onFinished(() -> finished(key, session));
            session.start(ctx, stream);
        });
    }

    private void finished(String key, ConsortiumSession session) {
        live.remove(key, session);
        store.releaseLease(key, owner);
    }

    /**
     * A request naming a call that may already exist. {@code onNew} runs when it does not — the
     * request then starts it — and the call is attached to, replayed or resumed otherwise; the
     * request's own body is ignored, since the call is the one its id was first given to.
     */
    void openOrCreate(ChannelHandlerContext ctx, boolean stream, String key, Runnable onNew) {
        ConsortiumSession session = live.get(key);
        if (session != null) {
            session.attach(ctx, stream);
            return;
        }
        store.load(key, stored -> {
            if (stored == null) {
                onNew.run();
                return;
            }
            handleStored(ctx, stream, key, stored);
        });
    }

    private void open(ChannelHandlerContext ctx, boolean stream, String key) {
        openOrCreate(ctx, stream, key, () -> ConsortiumHandler.sendError(ctx,
                io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE,
                "call could not be found or started; retry"));
    }

    private void handleStored(ChannelHandlerContext ctx, boolean stream, String key, ConsortiumStore.Stored stored) {
        ConsortiumSession session = live.get(key);
        if (session != null) {
            session.attach(ctx, stream);
            return;
        }
        Map<?, ?> state = parse(stored.state);
        if (state == null) {
            ConsortiumHandler.sendError(ctx, io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "call is being set up; retry");
            return;
        }
        if (Boolean.TRUE.equals(state.get("finished"))) {
            ConsortiumSession.replayStored(new ConsortiumClient(ctx, stream), state, stored.events);
            return;
        }
        if (stored.leaseOwner != null && !owner.equals(stored.leaseOwner)) {
            // Running in another process — or in one that died and whose lease has not run out
            // yet. Follow its events from Redis until it ends, or until the lease lapses and this
            // process can take it over.
            new Follower(ctx, stream, key).poll();
            return;
        }
        store.takeLease(key, owner, taken -> {
            if (!taken) {
                new Follower(ctx, stream, key).poll();
                return;
            }
            resumeOrRelease(key, stored, new ConsortiumClient(ctx, stream));
        });
    }

    /**
     * With the lease taken: resumes the call here, or — if it has meanwhile ended — gives it back.
     * {@code client} is the connection that asked for it, or null for the scan.
     */
    private void resumeOrRelease(String key, ConsortiumStore.Stored stored, ConsortiumClient client) {
        Map<?, ?> state = stored == null ? null : parse(stored.state);
        if (state == null || Boolean.TRUE.equals(state.get("finished"))) {
            store.releaseLease(key, owner);
            if (stored == null || state != null) {
                store.finished(key);
            }
            if (client != null && state != null) {
                ConsortiumSession.replayStored(client, state, stored.events);
            }
            return;
        }
        ConsortiumSession session;
        try {
            session = ConsortiumSession.restore(state, stored.events, stored.request, config, group,
                    healthTracker, ledger, store);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "could not restore consortium call " + key, e);
            store.releaseLease(key, owner);
            return;
        }
        ConsortiumSession existing = live.putIfAbsent(key, session);
        if (existing != null) {
            if (client != null) {
                existing.attach(client);
            }
            return;
        }
        session.onFinished(() -> finished(key, session));
        if (client != null) {
            session.attach(client);
        }
        session.resume();
    }

    private static Map<?, ?> parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            Object parsed = Json.load(json);
            return parsed instanceof Map ? (Map<?, ?>) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A client attached to a call another process holds: replays its stored events, then polls
     * Redis for new ones each second. Ends with the call; takes the call over if its lease lapses.
     */
    private final class Follower {
        private final ChannelHandlerContext ctx;
        private final String key;
        private final ConsortiumClient client;

        Follower(ChannelHandlerContext ctx, boolean stream, String key) {
            this.ctx = ctx;
            this.key = key;
            this.client = new ConsortiumClient(ctx, stream);
            client.begin();
        }

        void poll() {
            if (!ctx.channel().isActive()) {
                client.closed();
                return;
            }
            ConsortiumSession session = live.get(key);
            if (session != null) {
                // Taken over here meanwhile (by the scan, or another client).
                session.attach(client);
                return;
            }
            store.load(key, stored -> {
                if (stored == null) {
                    client.end(503, "{\"error\":\"call expired\"}", Integer.MAX_VALUE,
                            "{\"event\":\"error\",\"status\":503,\"error\":\"call expired\"}", 0);
                    return;
                }
                Map<?, ?> state = parse(stored.state);
                for (String line : stored.events) {
                    client.event(ConsortiumSession.seqOf(line), line);
                }
                if (state != null && Boolean.TRUE.equals(state.get("finished"))) {
                    ConsortiumSession.replayStored(client, state, stored.events);
                    return;
                }
                if (stored.leaseOwner == null) {
                    store.takeLease(key, owner, taken -> {
                        if (taken) {
                            resumeOrRelease(key, stored, client);
                        } else {
                            timer.schedule(this::poll, 1, TimeUnit.SECONDS);
                        }
                    });
                    return;
                }
                timer.schedule(this::poll, 1, TimeUnit.SECONDS);
            });
        }
    }
}
