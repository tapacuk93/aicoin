package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Per-app daily caps: enforcement, the UTC-midnight reset, and that a blocked request is never charged. */
class AppCapsTest {

    private static final String EE_WALLET = "bd08427d1f29b8eb0093ec49216f8a653daa3fe106e2d77cfc1f79e187882507";
    private static final String OTHER_WALLET = "00c0759c5748da81065fbb0334c48c03a634cc52b1fd235258c57db9a5e6aab9";

    /** The Redis store's semantics, in memory. */
    static class MemoryStore implements AppCaps.Store {
        final Map<String, AppCaps.AppConfig> apps = new HashMap<>();
        final Map<String, String> walletToApp = new HashMap<>();
        final Map<String, Double> spend = new HashMap<>();

        @Override
        public CompletionStage<Optional<String>> appForWallet(String wallet) {
            return CompletableFuture.completedFuture(Optional.ofNullable(walletToApp.get(wallet.toLowerCase())));
        }

        @Override
        public CompletionStage<Optional<AppCaps.AppConfig>> getApp(String app) {
            return CompletableFuture.completedFuture(Optional.ofNullable(apps.get(app)));
        }

        @Override
        public CompletionStage<List<AppCaps.AppConfig>> listApps() {
            return CompletableFuture.completedFuture(new ArrayList<>(apps.values()));
        }

        @Override
        public CompletionStage<Void> putApp(AppCaps.AppConfig config, List<String> droppedWallets) {
            apps.put(config.getApp(), AppCaps.AppConfig.fromJson(config.getApp(), config.toJson()));
            droppedWallets.forEach(walletToApp::remove);
            config.getWallets().forEach(w -> walletToApp.put(w, config.getApp()));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> deleteApp(AppCaps.AppConfig config) {
            apps.remove(config.getApp());
            config.getWallets().forEach(walletToApp::remove);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Double> spend(String app, LocalDate day) {
            return CompletableFuture.completedFuture(spend.getOrDefault(app + ":" + day, 0.0));
        }

        @Override
        public CompletionStage<Void> addSpend(String app, LocalDate day, double usd) {
            spend.merge(app + ":" + day, usd, Double::sum);
            return CompletableFuture.completedFuture(null);
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final AppCaps caps = new AppCaps(store);
    private static final Instant MORNING = Instant.parse("2026-10-25T09:00:00Z");

    private void putEarthEmpire(double cap) {
        caps.put("earth-empire", true, cap, List.of(EE_WALLET), MORNING, r -> assertNull(r.getConflict()));
    }

    private AppCaps.Decision decide(String wallet, Instant now) {
        AtomicReference<AppCaps.Decision> out = new AtomicReference<>();
        caps.check(wallet, now, out::set);
        return out.get();
    }

    @Test
    void walletInNoAppIsAlwaysAllowed() {
        assertFalse(decide(OTHER_WALLET, MORNING).isCapped());
    }

    @Test
    void underTheCapIsAllowedAndAtTheCapIsRefused() {
        putEarthEmpire(20);
        caps.recordSpend(EE_WALLET, 19.99, MORNING);
        assertFalse(decide(EE_WALLET, MORNING).isCapped());

        caps.recordSpend(EE_WALLET, 0.02, MORNING);
        AppCaps.Decision d = decide(EE_WALLET, MORNING);
        assertTrue(d.isCapped());
        assertEquals("earth-empire", d.getStatus().getConfig().getApp());
        String body = d.errorJson();
        assertTrue(body.contains("\"error\":\"app_daily_cap_reached\""), body);
        assertTrue(body.contains("\"reset_at\":\"2026-10-26T00:00:00Z\""), body);
        assertEquals(15 * 3600, d.retryAfterSeconds(MORNING));
    }

    @Test
    void spendOfOtherWalletsDoesNotCount() {
        putEarthEmpire(1);
        caps.recordSpend(OTHER_WALLET, 50, MORNING);
        assertFalse(decide(EE_WALLET, MORNING).isCapped());
    }

    @Test
    void walletMatchingIsCaseInsensitive() {
        putEarthEmpire(1);
        caps.recordSpend(EE_WALLET.toUpperCase(), 2, MORNING);
        assertTrue(decide(EE_WALLET.toUpperCase(), MORNING).isCapped());
    }

    @Test
    void theCapResetsAtUtcMidnight() {
        putEarthEmpire(20);
        caps.recordSpend(EE_WALLET, 25, MORNING);
        assertTrue(decide(EE_WALLET, Instant.parse("2026-10-25T23:59:59Z")).isCapped());
        assertFalse(decide(EE_WALLET, Instant.parse("2026-10-26T00:00:00Z")).isCapped());
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), AppCaps.resetAt(MORNING));
        assertEquals(Instant.parse("2026-10-27T00:00:00Z"), AppCaps.resetAt(Instant.parse("2026-10-26T00:00:00Z")));
    }

    @Test
    void aBlockedRequestIsNeverCharged() {
        putEarthEmpire(0.01);
        AtomicInteger debits = new AtomicInteger();
        AtomicInteger refusals = new AtomicInteger();
        // Under the cap: the "debit" (what the proxy runs in onAllowed) happens.
        caps.gate(EE_WALLET, MORNING, debits::incrementAndGet, d -> refusals.incrementAndGet());
        assertEquals(1, debits.get());
        caps.recordSpend(EE_WALLET, 0.02, MORNING);
        // At the cap: refused, and the debit never runs.
        caps.gate(EE_WALLET, MORNING, debits::incrementAndGet, d -> refusals.incrementAndGet());
        caps.gate(EE_WALLET, MORNING, debits::incrementAndGet, d -> refusals.incrementAndGet());
        assertEquals(1, debits.get());
        assertEquals(2, refusals.get());
    }

    @Test
    void noCapTracksSpendButNeverRefuses() {
        caps.put("free-app", true, null, List.of(EE_WALLET), MORNING, r -> { });
        caps.recordSpend(EE_WALLET, 1000, MORNING);
        assertFalse(decide(EE_WALLET, MORNING).isCapped());
        AtomicReference<Optional<List<AppCaps.AppStatus>>> list = new AtomicReference<>();
        caps.list(MORNING, list::set);
        assertEquals(1000, list.get().get().get(0).getSpentTodayUsd(), 1e-9);
    }

    @Test
    void aStoreFailureFailsOpen() {
        AppCaps broken = new AppCaps(new MemoryStore() {
            @Override
            public CompletionStage<Optional<String>> appForWallet(String wallet) {
                return CompletableFuture.failedFuture(new RuntimeException("redis down"));
            }
        });
        AtomicReference<AppCaps.Decision> d = new AtomicReference<>();
        broken.check(EE_WALLET, MORNING, d::set);
        assertFalse(d.get().isCapped());
    }

    @Test
    void aWalletCannotBelongToTwoApps() {
        putEarthEmpire(20);
        AtomicReference<AppCaps.PutResult> r = new AtomicReference<>();
        caps.put("other", true, 5.0, List.of(EE_WALLET), MORNING, r::set);
        assertNotNull(r.get().getConflict());
        assertFalse(store.apps.containsKey("other"));
    }

    @Test
    void updatingKeepsWhatTheBodyLeavesOutAndDropsReplacedWallets() {
        putEarthEmpire(20);
        caps.put("earth-empire", false, null, List.of(OTHER_WALLET), MORNING, r -> { });
        assertEquals(20.0, store.apps.get("earth-empire").getUsdPerDay());
        assertNull(store.walletToApp.get(EE_WALLET));
        assertEquals("earth-empire", store.walletToApp.get(OTHER_WALLET));
        caps.put("earth-empire", true, 30.0, null, MORNING, r -> { });
        assertEquals(List.of(OTHER_WALLET), store.apps.get("earth-empire").getWallets());
    }

    @Test
    void deleteUnmapsWallets() {
        putEarthEmpire(20);
        AtomicReference<Optional<Boolean>> deleted = new AtomicReference<>();
        caps.delete("earth-empire", deleted::set);
        assertTrue(deleted.get().get());
        assertTrue(store.walletToApp.isEmpty());
    }

    @Test
    void putBodyParsing() {
        AppCapsHandler.PutRequest p = AppCapsHandler.parsePut("{\"usd_per_day\":20,\"wallets\":[\"" + EE_WALLET + "\"]}");
        assertNull(p.error);
        assertTrue(p.setCap);
        assertEquals(20.0, p.usdPerDay);
        assertEquals(List.of(EE_WALLET), p.wallets);

        AppCapsHandler.PutRequest clear = AppCapsHandler.parsePut("{\"usd_per_day\":null}");
        assertNull(clear.error);
        assertTrue(clear.setCap);
        assertNull(clear.usdPerDay);
        assertNull(clear.wallets);

        assertNotNull(AppCapsHandler.parsePut("{\"usd_per_day\":0}").error);
        assertNotNull(AppCapsHandler.parsePut("{\"usd_per_day\":-1}").error);
        assertNotNull(AppCapsHandler.parsePut("{\"wallets\":[\"nothex\"]}").error);
        assertNotNull(AppCapsHandler.parsePut("[1]").error);
    }

    @Test
    void appNames() {
        assertTrue(AppCaps.isValidAppName("earth-empire"));
        assertFalse(AppCaps.isValidAppName("Earth Empire"));
        assertFalse(AppCaps.isValidAppName("-x"));
        assertFalse(AppCaps.isValidAppName(""));
    }
}
