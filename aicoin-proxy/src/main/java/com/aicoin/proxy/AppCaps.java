package com.aicoin.proxy;

import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Per-app daily spend caps, in US dollars, per CONTRACT.md's "Per-app daily caps".
 *
 * <p>The proxy has no notion of an app on the wire: every caller is a wallet address, proven by its
 * API token. An <em>app</em> is therefore an operator-side name for a set of wallets — typically an
 * app's server wallet — written through {@code PUT /admin/apps/{app}}. A client cannot assert which
 * app it is, for the same reason it cannot exempt itself from the spend budget.
 *
 * <p>Each app has an optional {@code usd_per_day}. Spend is the proxy's own cost accounting — the
 * same {@code costUsd} that {@link AicoinLedger#recordEvent} writes into the price signal — summed
 * per UTC day. The day rolls over at UTC midnight: spend lives in one key per app per day, so a new
 * day starts at zero without anything having to reset it.
 *
 * <p>The cap is checked once per client request, before any coin is held. A request already in
 * flight when the cap is crossed finishes (its cost was committed when it was let through), so an
 * app can end a day slightly over its cap by at most what its in-flight requests cost. A blocked
 * request is never charged: no coin is held and no provider is called.
 */
final class AppCaps {

    private static final Logger LOG = Logger.getLogger(AppCaps.class.getName());

    /** App names: lowercase, digits, {@code - _ .}; short enough to sit in a URL and a Redis key. */
    private static final Pattern APP_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /** The operator-side configuration of one app. */
    static final class AppConfig {
        private final String app;
        private final Double usdPerDay;
        private final List<String> wallets;

        AppConfig(String app, Double usdPerDay, List<String> wallets) {
            this.app = app;
            this.usdPerDay = usdPerDay;
            List<String> sorted = new ArrayList<>(wallets);
            Collections.sort(sorted);
            this.wallets = Collections.unmodifiableList(sorted);
        }

        String getApp() {
            return app;
        }

        /** Null means no cap: spend is still tracked, nothing is refused. */
        Double getUsdPerDay() {
            return usdPerDay;
        }

        List<String> getWallets() {
            return wallets;
        }

        String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("usd_per_day", usdPerDay);
            m.put("wallets", new ArrayList<Object>(wallets));
            return Json.write(m);
        }

        static AppConfig fromJson(String app, String json) {
            Object parsed = Json.load(json);
            Double cap = null;
            List<String> wallets = new ArrayList<>();
            if (parsed instanceof Map<?, ?> map) {
                if (map.get("usd_per_day") instanceof Number n) {
                    cap = n.doubleValue();
                }
                if (map.get("wallets") instanceof List<?> list) {
                    for (Object w : list) {
                        if (w instanceof String s) {
                            wallets.add(s);
                        }
                    }
                }
            }
            return new AppConfig(app, cap, wallets);
        }
    }

    /** One app's cap and today's spend, as {@code GET /admin/apps} reports it. */
    static final class AppStatus {
        private final AppConfig config;
        private final double spentTodayUsd;
        private final Instant resetAt;

        AppStatus(AppConfig config, double spentTodayUsd, Instant resetAt) {
            this.config = config;
            this.spentTodayUsd = spentTodayUsd;
            this.resetAt = resetAt;
        }

        AppConfig getConfig() {
            return config;
        }

        double getSpentTodayUsd() {
            return spentTodayUsd;
        }

        Instant getResetAt() {
            return resetAt;
        }

        boolean isCapped() {
            return isAtCap(config.getUsdPerDay(), spentTodayUsd);
        }

        String toJson() {
            Double cap = config.getUsdPerDay();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("app", config.getApp());
            m.put("usd_per_day", cap);
            m.put("spent_today_usd", round(spentTodayUsd));
            m.put("remaining_usd", cap == null ? null : round(Math.max(0, cap - spentTodayUsd)));
            m.put("capped", isCapped());
            m.put("reset_at", resetAt.toString());
            m.put("wallets", new ArrayList<Object>(config.getWallets()));
            return Json.write(m);
        }
    }

    /** What the gate decided for one request. */
    static final class Decision {
        private final AppStatus capped;

        private Decision(AppStatus capped) {
            this.capped = capped;
        }

        static Decision allowed() {
            return new Decision(null);
        }

        static Decision capped(AppStatus status) {
            return new Decision(status);
        }

        boolean isCapped() {
            return capped != null;
        }

        /** The capped app's status; null when allowed. */
        AppStatus getStatus() {
            return capped;
        }

        /** Seconds until the cap resets, for {@code Retry-After}. */
        long retryAfterSeconds(Instant now) {
            return capped == null ? 0 : Math.max(1, capped.getResetAt().getEpochSecond() - now.getEpochSecond());
        }

        /** The {@code 429} body. */
        String errorJson() {
            AppConfig c = capped.getConfig();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "app_daily_cap_reached");
            m.put("message", "this app has reached its daily spend cap of $" + c.getUsdPerDay()
                    + "; requests resume at " + capped.getResetAt());
            m.put("app", c.getApp());
            m.put("usd_per_day", c.getUsdPerDay());
            m.put("spent_today_usd", round(capped.getSpentTodayUsd()));
            m.put("reset_at", capped.getResetAt().toString());
            return Json.write(m);
        }
    }

    /** Where caps and spend live. Redis in production; an in-memory map in tests. */
    interface Store {
        CompletionStage<Optional<String>> appForWallet(String wallet);

        CompletionStage<Optional<AppConfig>> getApp(String app);

        CompletionStage<List<AppConfig>> listApps();

        /** Writes {@code config}, and points exactly its wallets at it (unmapping wallets it dropped). */
        CompletionStage<Void> putApp(AppConfig config, List<String> droppedWallets);

        CompletionStage<Void> deleteApp(AppConfig config);

        CompletionStage<Double> spend(String app, LocalDate day);

        CompletionStage<Void> addSpend(String app, LocalDate day, double usd);
    }

    private final Store store;

    AppCaps(Store store) {
        this.store = store;
    }

    static boolean isValidAppName(String app) {
        return app != null && APP_NAME.matcher(app).matches();
    }

    /** The UTC day {@code now} falls in — the day whose spend counts against the cap. */
    static LocalDate dayOf(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** The next UTC midnight after {@code now}: when today's spend stops counting. */
    static Instant resetAt(Instant now) {
        return dayOf(now).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** At or over the cap. No cap never is. */
    static boolean isAtCap(Double usdPerDay, double spentUsd) {
        return usdPerDay != null && spentUsd >= usdPerDay;
    }

    /**
     * Decides whether {@code wallet} may make a paid call now. A wallet in no app is always
     * allowed. A store failure allows the call: the cap is a cost control, the wallet's own
     * balance gate still applies, and a Redis blip should not take every capped app offline.
     */
    void check(String wallet, Instant now, Consumer<Decision> onDecision) {
        store.appForWallet(wallet).thenCompose(app -> {
            if (app.isEmpty()) {
                return CompletableFuture.completedFuture(Decision.allowed());
            }
            return store.getApp(app.get()).thenCompose(config -> {
                if (config.isEmpty() || config.get().getUsdPerDay() == null) {
                    return CompletableFuture.completedFuture(Decision.allowed());
                }
                return store.spend(app.get(), dayOf(now)).thenApply(spent -> {
                    AppStatus status = new AppStatus(config.get(), spent, resetAt(now));
                    return status.isCapped() ? Decision.capped(status) : Decision.allowed();
                });
            });
        }).whenComplete((decision, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "app cap check failed for " + wallet + ", allowing the call", err);
                onDecision.accept(Decision.allowed());
                return;
            }
            onDecision.accept(decision);
        });
    }

    /**
     * The gate: runs {@code onAllowed} (which is where the coin is held and the call made) only
     * when the wallet's app is under its cap, and {@code onCapped} otherwise — so a blocked request
     * is never charged, because the charge lives inside {@code onAllowed}.
     */
    void gate(String wallet, Instant now, Runnable onAllowed, Consumer<Decision> onCapped) {
        check(wallet, now, decision -> {
            if (decision.isCapped()) {
                onCapped.accept(decision);
            } else {
                onAllowed.run();
            }
        });
    }

    /** Adds one call's cost to its wallet's app's spend for the UTC day of {@code at}. Fire-and-forget. */
    void recordSpend(String wallet, double costUsd, Instant at) {
        if (wallet == null || !(costUsd > 0)) {
            return;
        }
        store.appForWallet(wallet)
                .thenCompose(app -> app.isEmpty()
                        ? CompletableFuture.<Void>completedFuture(null)
                        : store.addSpend(app.get(), dayOf(at), costUsd))
                .exceptionally(err -> {
                    LOG.log(Level.WARNING, "app spend record failed for " + wallet, err);
                    return null;
                });
    }

    /** Every app with today's spend, by name. */
    void list(Instant now, Consumer<Optional<List<AppStatus>>> onResult) {
        store.listApps().thenCompose(configs -> {
            List<CompletableFuture<AppStatus>> futures = new ArrayList<>();
            for (AppConfig config : configs) {
                futures.add(store.spend(config.getApp(), dayOf(now))
                        .thenApply(spent -> new AppStatus(config, spent, resetAt(now)))
                        .toCompletableFuture());
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(v -> {
                List<AppStatus> out = new ArrayList<>();
                for (CompletableFuture<AppStatus> f : futures) {
                    out.add(f.join());
                }
                out.sort((a, b) -> a.getConfig().getApp().compareTo(b.getConfig().getApp()));
                return out;
            });
        }).whenComplete((list, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "app list failed", err);
                onResult.accept(Optional.empty());
                return;
            }
            onResult.accept(Optional.of(list));
        });
    }

    /** Outcome of {@link #put}. */
    static final class PutResult {
        private final boolean reachable;
        private final String conflict;
        private final AppStatus status;

        private PutResult(boolean reachable, String conflict, AppStatus status) {
            this.reachable = reachable;
            this.conflict = conflict;
            this.status = status;
        }

        boolean isReachable() {
            return reachable;
        }

        /** Why the write was refused (a wallet that already belongs to another app), or null. */
        String getConflict() {
            return conflict;
        }

        AppStatus getStatus() {
            return status;
        }
    }

    /**
     * Creates or updates {@code app}. {@code setCap} false keeps the existing cap (none for a new
     * app); true sets it to {@code usdPerDay}, where null removes it. {@code wallets} null keeps the
     * existing mapping; otherwise it replaces it. A wallet already mapped to a different app is a
     * conflict and nothing is written — moving a wallet is two explicit writes, never a side effect.
     */
    void put(String app, boolean setCap, Double usdPerDay, List<String> wallets, Instant now,
             Consumer<PutResult> onResult) {
        store.getApp(app).thenCompose(existing -> {
            Double cap = setCap ? usdPerDay : existing.map(AppConfig::getUsdPerDay).orElse(null);
            List<String> newWallets = wallets != null ? normalise(wallets)
                    : existing.map(AppConfig::getWallets).orElse(List.of());
            List<CompletableFuture<Optional<String>>> owners = new ArrayList<>();
            for (String w : newWallets) {
                owners.add(store.appForWallet(w).toCompletableFuture());
            }
            return CompletableFuture.allOf(owners.toArray(new CompletableFuture[0])).thenCompose(v -> {
                for (int i = 0; i < newWallets.size(); i++) {
                    Optional<String> owner = owners.get(i).join();
                    if (owner.isPresent() && !owner.get().equals(app)) {
                        return CompletableFuture.completedFuture(new PutResult(true,
                                "wallet " + newWallets.get(i) + " already belongs to app " + owner.get(), null));
                    }
                }
                List<String> dropped = new ArrayList<>(existing.map(AppConfig::getWallets).orElse(List.of()));
                dropped.removeAll(newWallets);
                AppConfig config = new AppConfig(app, cap, newWallets);
                return store.putApp(config, dropped)
                        .thenCompose(ignored -> store.spend(app, dayOf(now)))
                        .thenApply(spent -> new PutResult(true, null, new AppStatus(config, spent, resetAt(now))));
            });
        }).whenComplete((result, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "app update failed for " + app, err);
                onResult.accept(new PutResult(false, null, null));
                return;
            }
            onResult.accept(result);
        });
    }

    /** Removes {@code app} and unmaps its wallets. Calls back with false if there was no such app; empty on a store failure. */
    void delete(String app, Consumer<Optional<Boolean>> onResult) {
        store.getApp(app).thenCompose(existing -> existing.isEmpty()
                ? CompletableFuture.completedFuture(false)
                : store.deleteApp(existing.get()).thenApply(v -> true))
                .whenComplete((deleted, err) -> {
                    if (err != null) {
                        LOG.log(Level.WARNING, "app delete failed for " + app, err);
                        onResult.accept(Optional.empty());
                        return;
                    }
                    onResult.accept(Optional.of(deleted));
                });
    }

    private static List<String> normalise(List<String> wallets) {
        List<String> out = new ArrayList<>();
        for (String w : wallets) {
            String lower = w.trim().toLowerCase(java.util.Locale.ROOT);
            if (!out.contains(lower)) {
                out.add(lower);
            }
        }
        return out;
    }

    private static double round(double usd) {
        return Math.round(usd * 1_000_000d) / 1_000_000d;
    }

    /**
     * Redis layout, all under the ledger's one hash tag (see {@link AicoinLedger}'s TAG): the hash
     * {@code aicoin:{aicoin}:apps} maps app → its {@link AppConfig} JSON; the hash
     * {@code aicoin:{aicoin}:app-wallets} maps wallet → app; and
     * {@code aicoin:{aicoin}:app-spend:<app>:<yyyy-mm-dd>} holds that UTC day's spend in USD,
     * expiring a week later.
     */
    static final class RedisStore implements Store {
        private static final String TAG = "{aicoin}";
        static final String APPS_KEY = "aicoin:" + TAG + ":apps";
        static final String APP_WALLETS_KEY = "aicoin:" + TAG + ":app-wallets";
        private static final long SPEND_TTL_SECONDS = 8L * 24 * 3600;

        private final RedisAsyncCommands<String, String> commands;

        RedisStore(RedisAsyncCommands<String, String> commands) {
            this.commands = commands;
        }

        static String spendKey(String app, LocalDate day) {
            return "aicoin:" + TAG + ":app-spend:" + app + ":" + day;
        }

        @Override
        public CompletionStage<Optional<String>> appForWallet(String wallet) {
            return commands.hget(APP_WALLETS_KEY, wallet.toLowerCase(java.util.Locale.ROOT))
                    .thenApply(Optional::ofNullable);
        }

        @Override
        public CompletionStage<Optional<AppConfig>> getApp(String app) {
            return commands.hget(APPS_KEY, app)
                    .thenApply(json -> json == null ? Optional.empty() : Optional.of(AppConfig.fromJson(app, json)));
        }

        @Override
        public CompletionStage<List<AppConfig>> listApps() {
            return commands.hgetall(APPS_KEY).thenApply(all -> {
                List<AppConfig> out = new ArrayList<>();
                for (Map.Entry<String, String> e : all.entrySet()) {
                    out.add(AppConfig.fromJson(e.getKey(), e.getValue()));
                }
                return out;
            });
        }

        @Override
        public CompletionStage<Void> putApp(AppConfig config, List<String> droppedWallets) {
            CompletionStage<?> step = commands.hset(APPS_KEY, config.getApp(), config.toJson());
            if (!droppedWallets.isEmpty()) {
                step = step.thenCompose(v -> commands.hdel(APP_WALLETS_KEY, droppedWallets.toArray(new String[0])));
            }
            if (!config.getWallets().isEmpty()) {
                Map<String, String> mapping = new LinkedHashMap<>();
                for (String w : config.getWallets()) {
                    mapping.put(w, config.getApp());
                }
                step = step.thenCompose(v -> commands.hset(APP_WALLETS_KEY, mapping));
            }
            return step.thenApply(v -> null);
        }

        @Override
        public CompletionStage<Void> deleteApp(AppConfig config) {
            CompletionStage<?> step = commands.hdel(APPS_KEY, config.getApp());
            if (!config.getWallets().isEmpty()) {
                step = step.thenCompose(v -> commands.hdel(APP_WALLETS_KEY, config.getWallets().toArray(new String[0])));
            }
            return step.thenApply(v -> null);
        }

        @Override
        public CompletionStage<Double> spend(String app, LocalDate day) {
            return commands.get(spendKey(app, day)).thenApply(v -> v == null ? 0.0 : Double.parseDouble(v));
        }

        @Override
        public CompletionStage<Void> addSpend(String app, LocalDate day, double usd) {
            String key = spendKey(app, day);
            return commands.incrbyfloat(key, usd)
                    .thenCompose(v -> commands.expire(key, SPEND_TTL_SECONDS))
                    .thenApply(v -> null);
        }
    }
}
