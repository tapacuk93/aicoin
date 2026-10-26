package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.FullHttpResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A reader's controls over a running chapter: pause it (turns in flight called off and refunded,
 * nothing more run), resume it (only what was called off is redone), and change who is on the
 * panel while it runs or while it is paused. Run against a fake provider over real HTTP and a
 * ledger that only records, so what was billed and refunded can be counted.
 */
class ConsortiumPauseTest {

    private static final String WALLET = "c".repeat(64);

    /** Records the ledger calls a session makes; every debit succeeds. */
    static final class FakeLedger extends AicoinLedger {
        final List<String> debits = Collections.synchronizedList(new ArrayList<>());
        final List<String> refunds = Collections.synchronizedList(new ArrayList<>());

        @Override
        void debitForCall(String address, double amount, String provider, Consumer<DebitResult> onResult) {
            debits.add(provider);
            onResult.accept(DebitResult.success(100));
        }

        @Override
        void refund(String address, double amount, String provider) {
            refunds.add(provider);
        }

        @Override
        void settleCall(String address, double extraCoins, String provider) {
        }

        @Override
        void recordEvent(String provider, double costUsd, long tokens, Instant timestamp, String walletAddress) {
        }

        @Override
        void getBalance(String userId, Consumer<Optional<Double>> onResult) {
            onResult.accept(Optional.of(100.0));
        }
    }

    /**
     * Anthropic and Kimi on one local server. Each request is answered by its stage (read off the
     * system prompt); a provider can be held at a gate so its turn stays in flight.
     */
    final class FakeProviders {
        final HttpServer server;
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        final Map<String, List<String>> reviews = new ConcurrentHashMap<>();

        FakeProviders() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/v1/messages", ex -> answer(ex, "anthropic"));
            server.createContext("/v1/chat/completions", ex -> answer(ex, "kimi"));
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        private void answer(com.sun.net.httpserver.HttpExchange ex, String provider) throws IOException {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String stage = body.contains("Every member is answering the same request independently") ? "draft"
                    : body.contains("have each answered the same request independently") ? "merge"
                    : body.contains("You are reviewing a candidate answer") ? "review" : "revise";
            requests.add(provider + ":" + stage);
            CountDownLatch gate = gates.get(provider + ":" + stage);
            if (gate != null) {
                try {
                    gate.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String text;
            switch (stage) {
                case "draft":
                    text = "draft by " + provider;
                    break;
                case "review":
                    List<String> queue = reviews.get(provider);
                    text = queue != null && !queue.isEmpty() ? queue.remove(0) : "NO COMMENTS";
                    break;
                default:
                    text = "<<<ANSWER\n" + stage + "d by " + provider + "\nANSWER>>>";
            }
            String json = provider.equals("anthropic")
                    ? "{\"model\":\"claude-opus-5-5\",\"content\":[{\"type\":\"text\",\"text\":" + Json.string(text)
                            + "}],\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}"
                    : "{\"model\":\"kimi-k2.6\",\"choices\":[{\"message\":{\"content\":" + Json.string(text)
                            + "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":10}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            try {
                ex.getResponseHeaders().set("content-type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
            } catch (IOException e) {
                // The proxy called this turn off and hung up: nobody to answer.
            } finally {
                ex.close();
            }
        }

        long count(String providerStage) {
            return requests.stream().filter(providerStage::equals).count();
        }
    }

    private FakeProviders providers;
    private FakeLedger ledger;
    private EventLoopGroup group;
    private ProxyConfig config;

    @BeforeEach
    void setUp() throws IOException {
        providers = new FakeProviders();
        ledger = new FakeLedger();
        group = new NioEventLoopGroup(2);
        Map<String, String> env = new HashMap<>();
        for (String provider : List.of("anthropic", "kimi")) {
            String p = provider.toUpperCase(java.util.Locale.ROOT);
            env.put("AICOIN_PROXY_" + p + "_BASEURL", "http://127.0.0.1:" + providers.port());
            env.put("AICOIN_PROXY_" + p + "_APIKEY", "test-key");
        }
        config = ProxyConfig.load(env);
    }

    @AfterEach
    void tearDown() {
        providers.server.stop(0);
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        providers.gates.values().forEach(CountDownLatch::countDown);
    }

    private ConsortiumSession newCall(List<String> panel, int maxRounds) {
        Map<String, List<String>> models = new LinkedHashMap<>();
        models.put("anthropic", List.of("claude-opus-5-5"));
        models.put("kimi", List.of("kimi-k2.6"));
        ConsortiumSession session = new ConsortiumSession(config, group, new ProviderHealthTracker(10), ledger, WALLET,
                "chapter-1", null, "Write 1.3", null, panel, panel.get(0), models, maxRounds, false, false, false,
                "writing", null, true, 2000, null, UpstreamTimer.NONE, 0, 0, Map.of());
        session.seating(List.of("anthropic", "kimi"), Set.of());
        return session;
    }

    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    private static boolean hasEvent(ConsortiumSession session, String... fragments) {
        for (String line : session.eventsNow()) {
            boolean all = true;
            for (String fragment : fragments) {
                all &= line.contains(fragment);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /** A call paused while Kimi drafts and Claude has already drafted. */
    private ConsortiumSession pausedMidDraft() throws InterruptedException {
        providers.gates.put("kimi:draft", new CountDownLatch(1));
        ConsortiumSession call = newCall(List.of("anthropic", "kimi"), 2);
        call.start(null, false);
        waitFor("Claude's draft", () -> hasEvent(call, "\"provider\":\"anthropic\"", "\"status\":\"finished\""));
        waitFor("Kimi's draft to be under way", () -> providers.count("kimi:draft") == 1);
        assertEquals("paused", call.pause(24));
        return call;
    }

    private ConsortiumSession restoreFrom(ConsortiumSession paused) {
        Map<?, ?> state = (Map<?, ?>) Json.load(Json.write(paused.snapshot()));
        return ConsortiumSession.restore(state, paused.eventsNow(), "{\"prompt\":\"Write 1.3\"}", config, group,
                new ProviderHealthTracker(10), ledger, null);
    }

    @Test
    void pausingMidDraftCallsOffTheTurnInFlightRefundsItAndRunsNothingMore() throws Exception {
        ConsortiumSession call = pausedMidDraft();

        assertEquals(List.of("kimi"), ledger.refunds, "Kimi's held coin goes back; Claude's finished draft is paid");
        assertTrue(hasEvent(call, "\"provider\":\"kimi\"", "\"status\":\"cancelled\""));
        assertTrue(hasEvent(call, "\"event\":\"paused\""));
        Map<String, Object> state = call.snapshot();
        assertEquals(true, state.get("paused"));
        assertEquals("draft", state.get("phase"));
        assertEquals(List.of("anthropic"), state.get("drafts_finished"));
        assertEquals(List.of(), state.get("pending_holds"));

        providers.gates.get("kimi:draft").countDown();
        Thread.sleep(400);
        List<String> sent = new ArrayList<>(providers.requests);
        Collections.sort(sent);
        assertEquals(List.of("anthropic:draft", "kimi:draft"), sent, "no merge, no review: nothing more ran");
        assertEquals(2, ledger.debits.size(), "nothing more was billed");
        assertEquals("paused", call.pause(24), "pausing again changes nothing");
    }

    @Test
    void resumingRedoesOnlyTheTurnThatWasCalledOff() throws Exception {
        ConsortiumSession paused = pausedMidDraft();
        providers.gates.get("kimi:draft").countDown();
        providers.gates.clear();

        ConsortiumSession resumed = restoreFrom(paused);
        resumed.resume();
        waitFor("the result", resumed::isFinished);

        assertEquals(1, providers.count("anthropic:draft"), "Claude's draft stood");
        assertEquals(2, providers.count("kimi:draft"), "Kimi's was called off once and redone once");
        assertEquals(1, providers.count("anthropic:merge"));
        assertTrue(hasEvent(resumed, "\"event\":\"resumed\"", "\"from\":\"draft\""));
        assertEquals(200, resumed.snapshot().get("final_status"));
    }

    @Test
    void resumingWithKimiSkippedCarriesOnWithoutIt() throws Exception {
        ConsortiumSession paused = pausedMidDraft();
        providers.gates.get("kimi:draft").countDown();

        ConsortiumSession resumed = restoreFrom(paused);
        assertEquals(List.of("anthropic"), resumed.applySkip(Set.of("kimi"), null, false));
        resumed.resume();
        waitFor("the result", resumed::isFinished);

        assertEquals(1, providers.count("kimi:draft"), "Kimi is not asked again");
        assertEquals(0, providers.count("kimi:review"));
        assertEquals(0, providers.count("anthropic:merge"), "one draft is the answer: nothing to merge");
        assertEquals(1, providers.count("anthropic:review"), "a panel of one still reviews");
        assertTrue(hasEvent(resumed, "\"event\":\"panel_changed\"", "\"removed\":[\"kimi\"]", "\"added\":[]"));
        assertTrue(((String) resumed.snapshot().get("final_body")).contains("\"panel\":[\"anthropic\"]"));
        assertNull(resumed.applySkip(Set.of("anthropic", "kimi"), null, false), "nobody left is refused");
    }

    @Test
    void takingTheEditorOffMidReviewHandsEditingOnAndTheRoundStillEnds() throws Exception {
        providers.gates.put("anthropic:review", new CountDownLatch(1));
        providers.reviews.put("kimi", new ArrayList<>(List.of("The bridge date is wrong.")));
        ConsortiumSession call = newCall(List.of("anthropic", "kimi"), 3);
        call.start(null, false);
        waitFor("Kimi's review", () -> hasEvent(call, "\"stage\":\"review\"", "\"provider\":\"kimi\"", "\"status\":\"finished\""));
        waitFor("Claude's review under way", () -> providers.count("anthropic:review") == 1);

        assertEquals(List.of("kimi"), call.applySkip(Set.of("anthropic"), null, true));
        waitFor("the result", call::isFinished);

        assertTrue(ledger.refunds.contains("anthropic"), "Claude's review in flight was refunded");
        assertTrue(hasEvent(call, "\"event\":\"editor_changed\"", "\"from\":\"anthropic\"", "\"to\":\"kimi\""));
        assertEquals(1, providers.count("kimi:revise"), "Kimi revised on its own comment as the new editor");
        assertEquals(0, providers.count("anthropic:revise"));
        String result = (String) call.snapshot().get("final_body");
        assertTrue(result.contains("\"editor\":\"kimi\""), result);
        assertTrue(result.contains("\"stopped_reason\":\"clean\""), result);
    }

    @Test
    void puttingAProviderBackJoinsItFromTheNextStage() throws Exception {
        providers.gates.put("anthropic:draft", new CountDownLatch(1));
        ConsortiumSession call = newCall(List.of("anthropic"), 1);
        call.seating(List.of("anthropic", "kimi"), Set.of("kimi"));
        call.start(null, false);
        waitFor("Claude's draft under way", () -> providers.count("anthropic:draft") == 1);
        assertEquals(List.of("anthropic", "kimi"), call.applySkip(Set.of(), null, true));
        providers.gates.get("anthropic:draft").countDown();
        waitFor("the result", call::isFinished);

        assertEquals(0, providers.count("kimi:draft"), "drafting had begun: Kimi does not draft");
        assertEquals(1, providers.count("kimi:review"), "but it reviews");
        assertTrue(hasEvent(call, "\"event\":\"panel_changed\"", "\"added\":[\"kimi\"]"));
    }

    // ---- The registry: expiry and the orphan scan ------------------------------------------------

    /** A store in memory, enough for the registry's lookups. */
    static final class MemoryStore implements ConsortiumStore {
        final Map<String, String> requests = new ConcurrentHashMap<>();
        final Map<String, String> states = new ConcurrentHashMap<>();
        final Map<String, String> leases = new ConcurrentHashMap<>();
        final Set<String> running = ConcurrentHashMap.newKeySet();
        final Set<String> gone = ConcurrentHashMap.newKeySet();

        @Override
        public void create(String key, String request, String state, String owner, Consumer<Boolean> created) {
            created.accept(requests.putIfAbsent(key, request) == null);
            states.put(key, state);
            running.add(key);
        }

        @Override
        public void checkpoint(String key, String state) {
            states.put(key, state);
        }

        @Override
        public void appendEvent(String key, String line) {
        }

        @Override
        public void replaceEvent(String key, int index, String line) {
        }

        @Override
        public void load(String key, Consumer<Stored> onLoaded) {
            String request = requests.get(key);
            onLoaded.accept(request == null ? null : new Stored(request, states.get(key), List.of(), leases.get(key)));
        }

        @Override
        public void takeLease(String key, String owner, Consumer<Boolean> taken) {
            taken.accept(leases.putIfAbsent(key, owner) == null);
        }

        @Override
        public void renewLease(String key, String owner) {
        }

        @Override
        public CompletionStage<?> releaseLease(String key, String owner) {
            leases.remove(key, owner);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void finished(String key) {
            running.remove(key);
        }

        @Override
        public void paused(String key, int retentionHours) {
            running.remove(key);
            gone.add(key);
        }

        @Override
        public void unpaused(String key) {
            running.add(key);
            gone.remove(key);
        }

        @Override
        public void gone(String key, Consumer<Boolean> onGone) {
            onGone.accept(gone.contains(key) && !requests.containsKey(key));
        }

        @Override
        public void running(Consumer<List<String>> onKeys) {
            onKeys.accept(new ArrayList<>(running));
        }
    }

    @Test
    void theOrphanScanNeverResumesAPausedCall() throws Exception {
        MemoryStore store = new MemoryStore();
        ConsortiumCalls calls = new ConsortiumCalls(store, config, group, new ProviderHealthTracker(10), ledger);
        ConsortiumSession paused = pausedMidDraft();
        String key = ConsortiumStore.key(WALLET, "chapter-1");
        store.requests.put(key, "{\"prompt\":\"Write 1.3\"}");
        store.states.put(key, Json.write(paused.snapshot()));
        store.running.add(key); // even if it were still listed as running, with no lease

        int before = providers.requests.size();
        calls.resumeOrphans();
        Thread.sleep(300);
        assertEquals(0, calls.liveCount());
        assertFalse(store.leases.containsKey(key), "the lease it took to look is given back");
        assertEquals(before, providers.requests.size(), "no turn ran");
    }

    @Test
    void aPausedCallThatExpiredIsGoneNotQuietlyRestarted() {
        MemoryStore store = new MemoryStore();
        ConsortiumCalls calls = new ConsortiumCalls(store, config, group, new ProviderHealthTracker(10), ledger);
        String key = ConsortiumStore.key(WALLET, "chapter-old");
        store.gone.add(key); // paused, then its retention ran out: the call is deleted, the marker stays

        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        boolean[] startedNew = {false};
        calls.openOrCreate(channel.pipeline().firstContext(), true, key, Map.of("call_id", "chapter-old"),
                () -> startedNew[0] = true);
        channel.runPendingTasks();
        FullHttpResponse response = channel.readOutbound();
        assertEquals(410, response.status().code());
        assertFalse(startedNew[0]);
    }
}
