package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The hard requirement behind the transaction log: a client built before {@code X-AICoin-App} and
 * {@code X-AICoin-Purpose} existed is served exactly as before — same status, same body, same
 * charge — and its call is still recorded, as purpose {@code unspecified}, app {@code unknown}.
 *
 * <p>Runs the real proxy pipeline ({@link ProxyServerInitializer}) on a local port in front of a
 * fake provider over real HTTP, with a ledger that only counts.
 */
class TransactionsCompatTest {

    private static final String ADMIN = "admin-secret";
    private static final String PROVIDER_BODY =
            "{\"model\":\"claude-test\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                    + "\"usage\":{\"input_tokens\":10,\"output_tokens\":6}}";

    /** Every debit succeeds; settles and refunds are counted. */
    static final class CountingLedger extends AicoinLedger {
        final List<String> refunds = Collections.synchronizedList(new ArrayList<>());
        final List<Double> settles = Collections.synchronizedList(new ArrayList<>());
        final AppCaps caps = new AppCaps(new NoApps());

        @Override
        void debitForCall(String address, double amount, String provider, Consumer<DebitResult> onResult) {
            onResult.accept(DebitResult.success(100));
        }

        @Override
        void refund(String address, double amount, String provider) {
            refunds.add(provider);
        }

        @Override
        void settleCall(String address, double extraCoins, String provider) {
            settles.add(extraCoins);
        }

        @Override
        void recordEvent(String provider, double costUsd, long tokens, Instant timestamp, String walletAddress) {
        }

        @Override
        void getTokenRevokedBefore(String address, Consumer<Optional<Long>> onResult) {
            onResult.accept(Optional.empty());
        }

        @Override
        AppCaps appCaps() {
            return caps;
        }
    }

    /** No app is configured and the catalog is empty: every call is uncapped. */
    static final class NoApps implements AppCaps.Store {
        @Override public CompletionStage<Optional<String>> appForWallet(String wallet) { return done(Optional.empty()); }
        @Override public CompletionStage<Optional<AppCaps.AppConfig>> getApp(String app) { return done(Optional.empty()); }
        @Override public CompletionStage<List<AppCaps.AppConfig>> listApps() { return done(List.of()); }
        @Override public CompletionStage<Void> putApp(AppCaps.AppConfig c, List<String> d) { return done(null); }
        @Override public CompletionStage<Void> deleteApp(AppCaps.AppConfig c) { return done(null); }
        @Override public CompletionStage<Double> spend(String app, LocalDate day) { return done(0.0); }
        @Override public CompletionStage<Void> addSpend(String app, LocalDate day, double usd) { return done(null); }
        @Override public CompletionStage<Double> topUp(String app, LocalDate day) { return done(0.0); }
        @Override public CompletionStage<Double> addTopUp(String app, LocalDate day, double usd) { return done(usd); }
        @Override public CompletionStage<Void> clearTopUp(String app, LocalDate day) { return done(null); }
        @Override public CompletionStage<List<String>> catalogApps() { return done(List.of()); }

        private static <T> CompletionStage<T> done(T value) {
            return CompletableFuture.completedFuture(value);
        }
    }

    private HttpServer provider;
    private final List<Map<String, List<String>>> providerHeaders = Collections.synchronizedList(new ArrayList<>());
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel server;
    private CountingLedger ledger;
    private Transactions.MemoryStore store;
    private String token;
    private String wallet;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/messages", ex -> {
            providerHeaders.add(new HashMap<>(ex.getRequestHeaders()));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean fail = body.contains("please fail");
            byte[] bytes = (fail ? "{\"error\":{\"type\":\"overloaded\",\"message\":\"Overloaded\"}}" : PROVIDER_BODY)
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("content-type", "application/json");
            ex.sendResponseHeaders(fail ? 529 : 200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        provider.start();

        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_ANTHROPIC_BASEURL", "http://127.0.0.1:" + provider.getAddress().getPort());
        env.put("AICOIN_PROXY_ANTHROPIC_APIKEY", "test-key");
        env.put("AICOIN_PROXY_ADMIN_TOKEN", ADMIN);
        env.put("AICOIN_PROXY_COIN_VALUE_USD", "0.01");
        ProxyConfig config = ProxyConfig.load(env);

        ledger = new CountingLedger();
        store = new Transactions.MemoryStore();
        Transactions.install(new Transactions(store, config.getCoinValueUsd(), 90, 1000, System::currentTimeMillis,
                w -> CompletableFuture.completedFuture(Optional.empty())));

        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup(2);
        server = new ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel.class)
                .childHandler(new ProxyServerInitializer(config, workers, new ProviderHealthTracker(20),
                        new ProviderSpeed(20), new ProviderLiveness(config), ledger, null))
                .bind("127.0.0.1", 0).sync().channel();

        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] der = keys.getPublic().getEncoded();
        wallet = HexFormat.of().formatHex(Arrays.copyOfRange(der, der.length - 32, der.length));
        long now = Instant.now().getEpochSecond();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"addr\":\"" + wallet + "\",\"iat\":" + (now - 10) + ",\"exp\":" + (now + 3600) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(keys.getPrivate());
        sig.update(payload.getBytes(StandardCharsets.US_ASCII));
        token = payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
    }

    @AfterEach
    void tearDown() {
        Transactions.install(null);
        server.close();
        boss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        workers.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        provider.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort();
    }

    private HttpResponse<String> call(String prompt, String... extraHeaders) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + "/v1/messages"))
                .header("X-AI", "anthropic")
                .header("X-Api-Key", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"claude-test\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":\""
                                + prompt + "\"}]}"));
        for (int i = 0; i < extraHeaders.length; i += 2) {
            b.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<?, ?> admin(String query) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/admin/transactions" + query))
                .header("X-Admin-Token", ADMIN).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return (Map<?, ?>) Json.load(r.body());
    }

    private void awaitEntries(int n) throws InterruptedException {
        for (int i = 0; i < 200 && store.entries.size() < n; i++) {
            Thread.sleep(10);
        }
        assertEquals(n, store.entries.size());
    }

    @Test
    void anOldClientWithoutTheNewHeadersIsServedAsBeforeAndRecordedUnspecified() throws Exception {
        HttpResponse<String> r = call("hello");

        assertEquals(200, r.statusCode());
        assertEquals(PROVIDER_BODY, r.body(), "the provider's answer is relayed untouched");
        assertEquals("1", r.headers().firstValue("X-Aicoin-Charged").orElse(null));
        assertTrue(ledger.refunds.isEmpty());

        awaitEntries(1);
        List<?> txs = (List<?>) admin("").get("transactions");
        Map<?, ?> tx = (Map<?, ?>) txs.get(0);
        assertEquals("unknown", tx.get("app"));
        assertEquals("unspecified", tx.get("purpose"));
        assertEquals("anthropic", tx.get("provider"));
        assertEquals("claude-test", tx.get("model"));
        assertEquals("/v1/messages", tx.get("endpoint"));
        assertEquals(wallet, tx.get("wallet"));
        assertEquals(true, tx.get("ok"));
        assertEquals(200, ((Number) tx.get("status")).intValue());
        assertEquals(1, ((Number) tx.get("coins")).intValue());
        assertEquals(0.01, ((Number) tx.get("usd")).doubleValue(), 1e-9);
    }

    @Test
    void theNewHeadersLabelTheCallAndChangeNothingElse() throws Exception {
        HttpResponse<String> old = call("hello");
        HttpResponse<String> labelled = call("hello", "X-AICoin-App", "com.Example.App", "X-AICoin-Purpose", " Translate ");

        assertEquals(old.statusCode(), labelled.statusCode());
        assertEquals(old.body(), labelled.body());
        assertEquals(old.headers().firstValue("X-Aicoin-Charged"), labelled.headers().firstValue("X-Aicoin-Charged"));
        // Neither label is passed on to the provider.
        for (Map<String, List<String>> h : providerHeaders) {
            for (String name : h.keySet()) {
                assertFalse(name.equalsIgnoreCase("X-AICoin-App") || name.equalsIgnoreCase("X-AICoin-Purpose"), name);
            }
        }

        awaitEntries(2);
        Map<?, ?> page = admin("");
        Map<?, ?> newest = (Map<?, ?>) ((List<?>) page.get("transactions")).get(0);
        assertEquals("com.example.app", newest.get("app"));
        assertEquals("translate", newest.get("purpose"));
        assertEquals(List.of("com.example.app", "unknown"), page.get("apps"));
        assertEquals(List.of("translate", "unspecified"), page.get("purposes"));

        Map<?, ?> filtered = admin("?purpose=translate");
        assertEquals(1, ((List<?>) filtered.get("transactions")).size());
        assertEquals(1, ((Number) ((Map<?, ?>) filtered.get("totals")).get("count")).intValue());
    }

    @Test
    void aFailedCallIsRefundedAsBeforeAndRecordedAsAFailure() throws Exception {
        HttpResponse<String> r = call("please fail", "X-AICoin-Purpose", "chat");

        assertEquals(529, r.statusCode(), "the provider's status is relayed as before");
        assertEquals(List.of("anthropic"), ledger.refunds);

        awaitEntries(1);
        Map<?, ?> page = admin("?ok=false");
        Map<?, ?> tx = (Map<?, ?>) ((List<?>) page.get("transactions")).get(0);
        assertEquals(false, tx.get("ok"));
        assertEquals(529, ((Number) tx.get("status")).intValue());
        assertEquals("HTTP 529: Overloaded", tx.get("error"));
        assertEquals(0, ((Number) tx.get("coins")).intValue());
        Map<?, ?> totals = (Map<?, ?>) page.get("totals");
        assertEquals(1, ((Number) totals.get("failures")).intValue());
        assertEquals(0, ((List<?>) admin("?ok=true").get("transactions")).size());
    }

    @Test
    void aBrokenLogNeverFailsTheCall() throws Exception {
        store.failing = true;
        HttpResponse<String> r = call("hello", "X-AICoin-Purpose", "chat");
        assertEquals(200, r.statusCode());
        assertEquals(PROVIDER_BODY, r.body());
    }

    @Test
    void theEndpointNeedsTheAdminToken() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/admin/transactions")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, r.statusCode());
        assertNotNull(Json.load(r.body()));
    }

    @Test
    void withNoLogInstalledTheEndpointSaysSoAndCallsStillWork() throws Exception {
        Transactions.install(null);
        assertEquals(200, call("hello").statusCode());
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/admin/transactions"))
                .header("X-Admin-Token", ADMIN).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, r.statusCode());
        assertNull(null);
    }
}
