package com.aicoin.proxy;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Push notifications to the operator's AICoin Admin app for the events worth being woken for, per
 * CONTRACT.md's "Admin alerts".
 *
 * <p>Events arrive two ways: pushed in from where they happen (a 429 for a capped app, an upstream
 * credit error, an IAP redemption, a 5xx written to a client) and pulled by a once-a-minute monitor
 * (the spend budget, server wallet balances, error rates, an app's cap resetting at midnight).
 *
 * <p>Every alert is debounced per kind and subject through Redis ({@code SET NX EX}), so it holds
 * across restarts: a provider out of credit pushes once per debounce window, not once per failed
 * call. Each alert is kept in a short history ({@code GET /admin/alerts}) whether or not a push was
 * sent — with no APNs key configured, that history is the whole feature.
 */
final class AdminAlerts {

    private static final Logger LOG = Logger.getLogger(AdminAlerts.class.getName());
    private static final String TAG = "{aicoin}";
    static final String DEVICES_KEY = "aicoin:" + TAG + ":admin-devices";
    static final String HISTORY_KEY = "aicoin:" + TAG + ":admin-alerts";
    private static final int HISTORY_CAP = 200;

    /** Every kind of alert, with the window inside which a repeat for the same subject is dropped. */
    enum Kind {
        APP_CAP("app_cap", "App reached its daily cap", 24 * 60),
        APP_CAP_RESET("app_cap_reset", "App's daily cap reset", 48 * 60),
        BUDGET("budget", "Spend budget at 50%, 90% or exhausted", 6 * 60),
        PROVIDER_CREDIT("provider_credit", "Provider out of credit or quota", 60),
        PROVIDER_DOWN("provider_down", "Provider outage", 30),
        WALLET_LOW("wallet_low", "Server wallet low or empty", 6 * 60),
        IAP_PURCHASE("iap_purchase", "In-app purchase credited", 0),
        IAP_FAILURE("iap_failure", "In-app purchase verification failed", 30),
        ERRORS_SPIKE("errors_spike", "Proxy 5xx errors spiking", 30),
        DEPLOY("deploy", "Proxy deployed or restarted", 0),
        TEST("test", "Test notification", 0);

        final String id;
        final String label;
        final int debounceMinutes;

        Kind(String id, String label, int debounceMinutes) {
            this.id = id;
            this.label = label;
            this.debounceMinutes = debounceMinutes;
        }

        static Optional<Kind> byId(String id) {
            for (Kind k : values()) {
                if (k.id.equals(id)) {
                    return Optional.of(k);
                }
            }
            return Optional.empty();
        }
    }

    /** The no-op instance used until {@link #install} — every hook is safe to call from tests. */
    private static volatile AdminAlerts instance = new AdminAlerts(null, null, null, Map.of());

    static AdminAlerts get() {
        return instance;
    }

    static void install(AdminAlerts alerts) {
        instance = alerts;
    }

    private final RedisAsyncCommands<String, String> redis;
    private final AicoinLedger ledger;
    private final Apns apns;
    private final double walletLowCoins;
    private final double debounceScale;
    private ScheduledExecutorService monitor;
    private int ticks;

    private final AtomicInteger clientResponses = new AtomicInteger();
    private final AtomicInteger client5xx = new AtomicInteger();
    private final Map<String, AtomicInteger> upstreamFailures = new ConcurrentHashMap<>();

    AdminAlerts(RedisAsyncCommands<String, String> redis, AicoinLedger ledger, Apns apns, Map<String, String> env) {
        this.redis = redis;
        this.ledger = ledger;
        this.apns = apns;
        this.walletLowCoins = parseDouble(env.get("AICOIN_PROXY_ALERT_WALLET_LOW_COINS"), 1000);
        // Scales every kind's window, e.g. 0 for no debouncing at all while testing.
        this.debounceScale = parseDouble(env.get("AICOIN_PROXY_ALERT_DEBOUNCE_SCALE"), 1);
    }

    boolean isApnsConfigured() {
        return apns != null && apns.isConfigured();
    }

    String apnsKeyId() {
        return apns == null ? "" : apns.getKeyId();
    }

    double getWalletLowCoins() {
        return walletLowCoins;
    }

    // ---- hooks: called from where things happen -------------------------------------------

    /** Startup — which is what a deploy is, here. */
    void proxyStarted() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown host";
        }
        String version = Optional.ofNullable(System.getenv("AICOIN_PROXY_VERSION")).orElse("");
        fire(Kind.DEPLOY, "start-" + System.currentTimeMillis(), "Proxy restarted",
                "aicoin-proxy started" + (version.isEmpty() ? "" : " (" + version + ")") + " on " + host);
    }

    void appCapReached(AppCaps.AppStatus status) {
        String app = status.getConfig().getApp();
        LocalDate day = AppCaps.dayOf(Instant.now());
        if (redis != null) {
            String key = cappedDayKey(day);
            redis.sadd(key, app).thenCompose(v -> redis.expire(key, 3 * 24 * 3600))
                    .exceptionally(err -> null);
        }
        fire(Kind.APP_CAP, app + ":" + day, app + " hit its daily cap",
                String.format(Locale.ROOT, "%s has spent $%.2f of its $%.2f/day cap. Its calls get 429 until %s.",
                        app, status.getSpentTodayUsd(), status.getConfig().getUsdPerDay(), status.getResetAt()));
    }

    /** One provider answer, from a forwarded or proxy-originated call. */
    void upstreamResponse(String provider, int status, byte[] body) {
        if (status >= 500 || status < 0) {
            upstreamFailures.computeIfAbsent(provider, p -> new AtomicInteger()).incrementAndGet();
        }
        if (status >= 200 && status < 300) {
            return;
        }
        String text = body == null ? "" : new String(body, 0, Math.min(body.length, 4000), java.nio.charset.StandardCharsets.UTF_8);
        if (isCreditError(status, text)) {
            fire(Kind.PROVIDER_CREDIT, provider, provider + " is out of credit",
                    provider + " refused a call (" + status + "): " + snippet(text));
        }
    }

    /** Whether an upstream error says the account behind the proxy's key cannot pay. */
    static boolean isCreditError(int status, String body) {
        if (status == 402) {
            return true;
        }
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return b.contains("credit balance") || b.contains("insufficient_quota") || b.contains("exceeded your current quota")
                || b.contains("billing") || b.contains("quota_exceeded") || b.contains("resource_exhausted")
                || b.contains("payment required") || b.contains("insufficient credits");
    }

    /** Every response this proxy writes to a client. */
    void clientResponse(int status) {
        if (status < 0) {
            return;
        }
        clientResponses.incrementAndGet();
        if (status >= 500) {
            client5xx.incrementAndGet();
        }
    }

    void iapCredited(String productId, double coins, String wallet, String transactionId) {
        fire(Kind.IAP_PURCHASE, transactionId, "Purchase: " + productId,
                String.format(Locale.ROOT, "+%.0f coins to %s", coins, shortAddress(wallet)));
    }

    void iapFailed(String reason, String productId) {
        fire(Kind.IAP_FAILURE, reason, "Purchase not credited",
                reason + (productId == null || productId.isEmpty() ? "" : " (" + productId + ")"));
    }

    // ---- the monitor -------------------------------------------------------------------------

    void startMonitor() {
        monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "admin-alerts-monitor");
            t.setDaemon(true);
            return t;
        });
        monitor.scheduleAtFixedRate(this::tickSafely, 60, 60, TimeUnit.SECONDS);
    }

    void stop() {
        if (monitor != null) {
            monitor.shutdownNow();
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "admin alert monitor tick failed", t);
        }
    }

    private void tick() {
        int total = clientResponses.getAndSet(0);
        int errors = client5xx.getAndSet(0);
        if (isErrorSpike(total, errors)) {
            fire(Kind.ERRORS_SPIKE, "proxy", "Proxy errors spiking",
                    errors + " of " + total + " responses in the last minute were 5xx");
        }
        for (Map.Entry<String, AtomicInteger> e : upstreamFailures.entrySet()) {
            int failures = e.getValue().getAndSet(0);
            if (failures >= 5) {
                fire(Kind.PROVIDER_DOWN, e.getKey(), e.getKey() + " looks down",
                        failures + " calls to " + e.getKey() + " failed with 5xx in the last minute");
            }
        }
        if (ledger == null) {
            return;
        }
        // The slower checks scan Redis, so every fifth minute is plenty.
        if (ticks++ % 5 != 0) {
            return;
        }
        checkBudget();
        checkWalletsAndResets();
    }

    /** At least five 5xx in a minute, making up at least a fifth of what was answered. */
    static boolean isErrorSpike(int total, int errors) {
        return errors >= 5 && errors * 5 >= total;
    }

    private void checkBudget() {
        ledger.computeBudget(result -> {
            Double limit = result.getLimitUsd();
            if (!result.isReachable() || limit == null || limit <= 0) {
                return;
            }
            double spent = result.getProductionSpendUsd();
            budgetLevel(spent, limit).ifPresent(level -> {
                String body = String.format(Locale.ROOT, "$%.2f of $%.2f spent.%s", spent, limit,
                        level.equals("exhausted") ? " Nothing is on sale in any app until it is raised." : "");
                if (level.equals("half")) {
                    // An early heads-up, so once per ceiling rather than every few hours: spend
                    // takes days to get from half to 90%, and that would be a push a quarter-day
                    // the whole way. Keyed by the ceiling, so raising it arms the next one.
                    fire(Kind.BUDGET, "half@" + Math.round(limit), "Spend budget at 50%", body, HALF_BUDGET_WINDOW_MINUTES);
                } else {
                    fire(Kind.BUDGET, level, level.equals("exhausted") ? "Spend budget exhausted" : "Spend budget at 90%", body);
                }
            });
        });
    }

    /** How long one ceiling's 50% alert holds off a repeat — in practice, once per ceiling. */
    static final int HALF_BUDGET_WINDOW_MINUTES = 60 * 24 * 60;

    /** "exhausted", "near" (90%+), "half" (50%+), or nothing. */
    static Optional<String> budgetLevel(double spent, double limit) {
        if (spent >= limit) {
            return Optional.of("exhausted");
        }
        if (spent >= 0.9 * limit) {
            return Optional.of("near");
        }
        if (spent >= 0.5 * limit) {
            return Optional.of("half");
        }
        return Optional.empty();
    }

    private void checkWalletsAndResets() {
        Instant now = Instant.now();
        ledger.appCaps().list(now, apps -> {
            if (apps.isEmpty()) {
                return;
            }
            java.util.Set<String> configured = new java.util.HashSet<>();
            for (AppCaps.AppStatus app : apps.get()) {
                configured.add(app.getConfig().getApp());
                for (String wallet : app.getConfig().getWallets()) {
                    ledger.getBalance(wallet, balance -> balance.ifPresent(b -> {
                        if (b < walletLowCoins) {
                            boolean empty = b <= 0;
                            fire(Kind.WALLET_LOW, wallet + (empty ? ":empty" : ":low"),
                                    app.getConfig().getApp() + "'s wallet is " + (empty ? "empty" : "low"),
                                    String.format(Locale.ROOT, "%s holds %.0f coins (alert below %.0f).",
                                            shortAddress(wallet), b, walletLowCoins));
                        }
                    }));
                }
            }
            if (redis == null) {
                return;
            }
            // Only apps that still exist: a deleted app's reset is nobody's news.
            LocalDate yesterday = AppCaps.dayOf(now).minusDays(1);
            redis.smembers(cappedDayKey(yesterday)).thenAccept(capped -> {
                for (String app : capped) {
                    if (configured.contains(app)) {
                        fire(Kind.APP_CAP_RESET, app + ":" + yesterday, app + "'s cap reset",
                                app + " was capped on " + yesterday + "; its calls are allowed again for today.");
                    }
                }
            }).exceptionally(err -> null);
        });
    }

    private static String cappedDayKey(LocalDate day) {
        return "aicoin:" + TAG + ":apps-capped:" + day;
    }

    // ---- dispatch ----------------------------------------------------------------------------

    /** Records and pushes an alert unless the same kind and subject went out inside its window. */
    void fire(Kind kind, String subject, String title, String body) {
        fire(kind, subject, title, body, kind.debounceMinutes);
    }

    /** As {@link #fire(Kind, String, String, String)}, with a window of its own instead of the kind's. */
    void fire(Kind kind, String subject, String title, String body, int debounceMinutes) {
        if (redis == null) {
            return;
        }
        long windowSeconds = Math.round(debounceMinutes * 60L * debounceScale);
        CompletionStage<Boolean> acquired = windowSeconds <= 0
                ? CompletableFuture.completedFuture(true)
                : redis.set("aicoin:" + TAG + ":alert-sent:" + kind.id + ":" + subject, "1",
                        SetArgs.Builder.nx().ex(windowSeconds)).thenApply("OK"::equals);
        acquired.thenAccept(fresh -> {
            if (fresh) {
                LOG.info("admin alert " + kind.id + ": " + title + " - " + body);
                send(kind, title, body, results -> { });
            }
        }).exceptionally(err -> {
            LOG.log(Level.WARNING, "admin alert debounce failed for " + kind.id, err);
            return null;
        });
    }

    /** Pushes to every registered device that has not turned {@code kind} off, then records it. */
    void send(Kind kind, String title, String body, Consumer<List<String>> onDone) {
        redis.hgetall(DEVICES_KEY).thenAccept(devices -> {
            List<CompletableFuture<String>> sends = new ArrayList<>();
            for (Map.Entry<String, String> d : devices.entrySet()) {
                Device device = Device.fromJson(d.getKey(), d.getValue());
                if (kind != Kind.TEST && device.disabled().contains(kind.id)) {
                    continue;
                }
                if (apns == null || !apns.isConfigured()) {
                    sends.add(CompletableFuture.completedFuture(shortToken(device.token()) + ": APNs not configured"));
                    continue;
                }
                sends.add(apns.send(device.token(), device.development(), title, body, kind.id).thenApply(r -> {
                    if (r.unregistered()) {
                        redis.hdel(DEVICES_KEY, device.token());
                    }
                    return shortToken(device.token()) + ": " + (r.ok() ? "sent" : r.detail());
                }));
            }
            CompletableFuture.allOf(sends.toArray(new CompletableFuture[0])).thenRun(() -> {
                List<String> results = sends.stream().map(CompletableFuture::join).toList();
                long sent = results.stream().filter(s -> s.endsWith(": sent")).count();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("kind", kind.id);
                entry.put("title", title);
                entry.put("body", body);
                entry.put("at", Instant.now().toString());
                entry.put("devices", (long) results.size());
                entry.put("pushed", sent);
                String json = Json.write(entry);
                redis.lpush(HISTORY_KEY, json).thenCompose(n -> redis.ltrim(HISTORY_KEY, 0, HISTORY_CAP - 1))
                        .exceptionally(err -> null);
                onDone.accept(results);
            });
        }).exceptionally(err -> {
            LOG.log(Level.WARNING, "admin alert send failed for " + kind.id, err);
            onDone.accept(List.of("failed: " + err.getMessage()));
            return null;
        });
    }

    // ---- devices -----------------------------------------------------------------------------

    /** One registered admin device: its APNs token, which APNs it talks to, and the kinds it turned off. */
    record Device(String token, boolean development, List<String> disabled) {
        String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("environment", development ? "development" : "production");
            m.put("disabled", new ArrayList<Object>(disabled));
            m.put("updated_at", Instant.now().toString());
            return Json.write(m);
        }

        static Device fromJson(String token, String json) {
            boolean dev = false;
            List<String> disabled = new ArrayList<>();
            try {
                if (Json.load(json) instanceof Map<?, ?> m) {
                    dev = "development".equals(m.get("environment"));
                    if (m.get("disabled") instanceof List<?> l) {
                        for (Object o : l) {
                            if (o instanceof String s) {
                                disabled.add(s);
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // A corrupt entry still receives everything rather than nothing.
            }
            return new Device(token, dev, disabled);
        }
    }

    static boolean isValidDeviceToken(String token) {
        return token != null && token.matches("[0-9a-fA-F]{32,200}");
    }

    RedisAsyncCommands<String, String> redis() {
        return redis;
    }

    private static String snippet(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 180 ? flat.substring(0, 180) + "…" : flat;
    }

    private static String shortAddress(String a) {
        return a == null || a.length() <= 14 ? String.valueOf(a) : a.substring(0, 6) + "…" + a.substring(a.length() - 6);
    }

    private static String shortToken(String t) {
        return t.length() <= 8 ? t : t.substring(0, 8) + "…";
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return s == null || s.isBlank() ? fallback : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
