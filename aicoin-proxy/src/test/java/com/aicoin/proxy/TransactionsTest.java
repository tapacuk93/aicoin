package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The log's labels, storage bounds, paging, filters and totals, against the in-memory store. */
class TransactionsTest {

    private static final long DAY = 86_400_000L;

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final Transactions.MemoryStore store = new Transactions.MemoryStore();

    private Transactions log(int retentionDays, int maxEntries) {
        return new Transactions(store, 0.01, retentionDays, maxEntries, now::get,
                w -> CompletableFuture.completedFuture(w.equals("mapped") ? Optional.of("earth-empire") : Optional.empty()));
    }

    private static Transactions.Call ok(String wallet, String app, String purpose, long coins) {
        return Transactions.Call.of(wallet, Transactions.Tag.fromHeaders(app, purpose), "anthropic")
                .model("claude").succeeded(200, coins, coins * 0.004);
    }

    private static Transactions.Call failed(String wallet, String app, String purpose) {
        return Transactions.Call.of(wallet, Transactions.Tag.fromHeaders(app, purpose), "openai").failed(500, "boom");
    }

    private static Map<?, ?> query(Transactions t, String qs) {
        Map<String, List<String>> params = new HashMap<>();
        if (!qs.isEmpty()) {
            for (String pair : qs.split("&")) {
                String[] kv = pair.split("=", 2);
                params.put(kv[0], List.of(kv[1]));
            }
        }
        Object q = Transactions.Query.parse(params);
        AtomicReference<Optional<String>> out = new AtomicReference<>();
        t.query((Transactions.Query) q, out::set);
        return (Map<?, ?>) Json.load(out.get().orElseThrow());
    }

    private static List<?> txs(Map<?, ?> page) {
        return (List<?>) page.get("transactions");
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    @Test
    void labelsAreShortPrintableAndLowercase() {
        assertNull(Transactions.label(null));
        assertNull(Transactions.label("  \t "));
        assertEquals("translate", Transactions.label(" Translate\n"));
        assertEquals("deep dive", Transactions.label("deep   dive"));
        assertEquals("caf", Transactions.label("café"));
        assertEquals(Transactions.MAX_LABEL, Transactions.label("x".repeat(500)).length());
    }

    @Test
    void theModelComesFromTheBodyOrThePath() {
        assertEquals("gpt-5", Transactions.modelOf("/v1/chat/completions",
                "{\"messages\":[],\"model\":\"gpt-5\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("eleven_v3", Transactions.modelOf("/v1/text-to-speech/x",
                "{\"text\":\"hi\",\"model_id\":\"eleven_v3\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("gemini-2.5-flash", Transactions.modelOf("/v1beta/models/gemini-2.5-flash:generateContent", new byte[0]));
        assertNull(Transactions.modelOf("/v1/messages", "{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("/v1/x", Transactions.endpointOf("/v1/x?key=secret"));
    }

    @Test
    void missingLabelsAreUnspecifiedAndTheAppFallsBackToTheWalletsThenUnknown() {
        Transactions t = log(90, 100);
        t.record(ok("w1", null, null, 1));
        t.record(ok("mapped", null, null, 1));
        t.record(ok("mapped", "com.example.app", "chat", 1));
        List<?> list = txs(query(t, ""));
        assertEquals(List.of("com.example.app", "earth-empire", "unknown"),
                list.stream().map(m -> ((Map<?, ?>) m).get("app")).toList());
        assertEquals(List.of("chat", "unspecified", "unspecified"),
                list.stream().map(m -> ((Map<?, ?>) m).get("purpose")).toList());
    }

    @Test
    void pagesNewestFirstWithACursorUntilTheEnd() {
        Transactions t = log(90, 1000);
        for (int i = 0; i < 7; i++) {
            now.addAndGet(1000);
            t.record(ok("w", "a", "p" + i, 1));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            Map<?, ?> page = query(t, "limit=3" + (cursor == null ? "" : "&before=" + cursor));
            for (Object m : txs(page)) {
                seen.add((String) ((Map<?, ?>) m).get("purpose"));
            }
            cursor = (String) page.get("next_before");
        } while (cursor != null);
        assertEquals(List.of("p6", "p5", "p4", "p3", "p2", "p1", "p0"), seen);
    }

    @Test
    void filtersAndTotalsCoverTheFilteredSet() {
        Transactions t = log(90, 1000);
        t.record(ok("w1", "radio", "narrate", 3));
        t.record(ok("w2", "book", "write", 5));
        t.record(failed("w1", "radio", "narrate"));
        t.record(failed("w2", "book", null));

        Map<?, ?> all = query(t, "limit=1");
        Map<?, ?> totals = (Map<?, ?>) all.get("totals");
        assertEquals(1, txs(all).size());
        assertEquals(4, num(totals.get("count")));
        assertEquals(2, num(totals.get("failures")));
        assertEquals(8, num(totals.get("coins")));
        assertEquals(0.08, num(totals.get("usd")), 1e-9);
        assertEquals(0.032, num(totals.get("cost_usd")), 1e-9);
        assertEquals(List.of("book", "radio"), all.get("apps"));
        assertEquals(List.of("narrate", "unspecified", "write"), all.get("purposes"));

        Map<?, ?> radio = query(t, "app=radio");
        assertEquals(2, txs(radio).size());
        assertEquals(3, num(((Map<?, ?>) radio.get("totals")).get("coins")));

        Map<?, ?> failures = query(t, "ok=false");
        assertEquals(2, txs(failures).size());
        assertTrue(txs(failures).stream().allMatch(m -> Boolean.FALSE.equals(((Map<?, ?>) m).get("ok"))));

        Map<?, ?> w2 = query(t, "wallet=w2&ok=true");
        assertEquals(1, txs(w2).size());
        assertEquals(5, num(((Map<?, ?>) w2.get("totals")).get("coins")));
        assertEquals(0.05, num(((Map<?, ?>) w2.get("totals")).get("usd")), 1e-9);
    }

    @Test
    void entriesPastTheRetentionWindowOrTheCapAreTrimmed() {
        Transactions t = log(2, 3);
        t.record(ok("w", "a", "old", 1));
        now.addAndGet(3 * DAY);
        t.record(ok("w", "a", "new", 1));
        assertEquals(1, store.entries.size(), "older than the window");
        for (int i = 0; i < 5; i++) {
            t.record(ok("w", "a", "more", 1));
        }
        assertEquals(3, store.entries.size(), "beyond the cap");
    }

    @Test
    void badParametersAreRefused() {
        assertTrue(Transactions.Query.parse(Map.of("limit", List.of("0"))) instanceof String);
        assertTrue(Transactions.Query.parse(Map.of("limit", List.of("999"))) instanceof String);
        assertTrue(Transactions.Query.parse(Map.of("ok", List.of("maybe"))) instanceof String);
        assertTrue(Transactions.Query.parse(Map.of("before", List.of("x"))) instanceof String);
    }

    @Test
    void aStoreFailureIsSwallowed() {
        Transactions t = log(90, 10);
        store.failing = true;
        t.record(ok("w", "a", "p", 1));
        AtomicReference<Optional<String>> out = new AtomicReference<>();
        t.query(new Transactions.Query(), out::set);
        assertEquals(Optional.empty(), out.get());
    }
}
