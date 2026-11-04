package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ElevenLabs' streaming speech endpoints relayed chunk by chunk ({@link StreamingForwarder}), over
 * real sockets: a fake provider on the JDK's HTTP server, a minimal proxy pipeline in front of the
 * forwarder, and a ledger that records what was settled and refunded.
 */
class StreamingForwarderTest {

    private static final String WALLET = "d".repeat(64);
    private static final String PATH = "/v1/text-to-speech/voice123/stream/with-timestamps?output_format=mp3_44100_128";

    static final class FakeLedger extends AicoinLedger {
        final List<Double> refunds = Collections.synchronizedList(new ArrayList<>());
        final List<Double> settles = Collections.synchronizedList(new ArrayList<>());
        final List<Double> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        void refund(String address, double amount, String provider) {
            refunds.add(amount);
        }

        @Override
        void settleCall(String address, double extraCoins, String provider) {
            settles.add(extraCoins);
        }

        @Override
        void recordEvent(String provider, double costUsd, long tokens, Instant timestamp, String walletAddress) {
            events.add(costUsd);
        }
    }

    /** What the fake provider does with one request. */
    interface Script {
        void run(HttpExchange ex) throws Exception;
    }

    private HttpServer upstream;
    private volatile Script script;
    private final List<String> upstreamUris = Collections.synchronizedList(new ArrayList<>());
    private final List<String> upstreamKeys = Collections.synchronizedList(new ArrayList<>());
    private final List<String> upstreamEncodings = Collections.synchronizedList(new ArrayList<>());
    private EventLoopGroup group;
    private Channel proxy;
    private FakeLedger ledger;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newCachedThreadPool());
        upstream.createContext("/", ex -> {
            upstreamUris.add(ex.getRequestURI().toString());
            upstreamKeys.add(ex.getRequestHeaders().getFirst("xi-api-key"));
            upstreamEncodings.add(String.valueOf(ex.getRequestHeaders().getFirst("Accept-Encoding")));
            ex.getRequestBody().readAllBytes();
            try {
                script.run(ex);
            } catch (Exception e) {
                // The proxy hung up, or the script ended the exchange abruptly on purpose.
            } finally {
                ex.close();
            }
        });
        upstream.start();
        ledger = new FakeLedger();
        group = new NioEventLoopGroup(2);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    private void startProxy(int readTimeoutSeconds) throws InterruptedException {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_ELEVENLABS_BASEURL", "http://127.0.0.1:" + upstream.getAddress().getPort());
        env.put("AICOIN_PROXY_ELEVENLABS_APIKEY", "test-key");
        env.put("AICOIN_PROXY_UPSTREAM_READ_TIMEOUT_SECONDS", Integer.toString(readTimeoutSeconds));
        ProxyConfig config = ProxyConfig.load(env);
        ProviderHealthTracker health = new ProviderHealthTracker(10);
        proxy = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(1 << 20));
                        ch.pipeline().addLast(new AccessLogHandler(null));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                                List<Map.Entry<String, String>> headers = new ArrayList<>();
                                headers.add(new AbstractMap.SimpleEntry<>("Content-Type", "application/json"));
                                headers.add(new AbstractMap.SimpleEntry<>("Accept-Encoding", "gzip"));
                                headers.add(new AbstractMap.SimpleEntry<>("xi-api-key", "test-key"));
                                StreamingForwarder.forward(group, config, health, ledger, ctx, req.method(),
                                        req.uri(), headers, ByteBufUtil.getBytes(req.content()),
                                        config.getProvider("elevenlabs").getBaseUrl(), "elevenlabs", WALLET,
                                        BilledCall.CALL_COST_AICOIN);
                            }
                        });
                    }
                }).bind("127.0.0.1", 0).sync().channel();
    }

    @AfterEach
    void tearDown() {
        upstream.stop(0);
        if (proxy != null) {
            proxy.close();
        }
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    private HttpRequest request() {
        int port = ((InetSocketAddress) proxy.localAddress()).getPort();
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"hello\",\"model_id\":\"eleven_v3\"}"))
                .timeout(Duration.ofSeconds(20))
                .build();
    }

    private static void chunk(HttpExchange ex, String text) throws IOException {
        OutputStream out = ex.getResponseBody();
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    /** Reads whatever has arrived until {@code expected} bytes are in, without waiting for the end. */
    private static String readAtLeast(InputStream in, int expected) throws IOException {
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (got.size() < expected) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            got.write(buf, 0, n);
        }
        return got.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theFirstChunkReachesTheClientWhileTheProviderIsStillTalking() throws Exception {
        CountDownLatch rest = new CountDownLatch(1);
        String first = "{\"audio_base64\":\"AAAA\",\"alignment\":null}\n\n";
        String second = "{\"audio_base64\":\"BBBB\",\"alignment\":null}\n\n";
        script = ex -> {
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("character-cost", "5");
            ex.sendResponseHeaders(200, 0);
            chunk(ex, first);
            rest.await(10, TimeUnit.SECONDS);
            chunk(ex, second);
        };
        startProxy(5);

        HttpResponse<InputStream> response = client.send(request(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertEquals("4", response.headers().firstValue("X-Aicoin-Charged").orElse(null),
                "$0.03 per call at $0.009 a coin, the same as the buffered endpoint");
        assertEquals("5", response.headers().firstValue("character-cost").orElse(null));
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
        assertFalse(response.headers().firstValue("Content-Length").isPresent());
        InputStream body = response.body();
        // The provider is still holding its second chunk: this can only arrive if the first was relayed.
        assertEquals(first, readAtLeast(body, first.length()));
        assertEquals(List.of(3.0), ledger.settles, "charged once the first byte is out");
        rest.countDown();
        assertEquals(second, new String(body.readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(List.of(), ledger.refunds);
        assertEquals(List.of(0.03), ledger.events);

        assertEquals(List.of(PATH), upstreamUris, "path and query go upstream unchanged");
        assertEquals(List.of("test-key"), upstreamKeys);
        assertEquals(List.of("null"), upstreamEncodings, "chunks are relayed as they are, so never compressed");
    }

    @Test
    void aStreamLongerThanTheReadTimeoutIsFineWhileChunksKeepComing() throws Exception {
        script = ex -> {
            ex.sendResponseHeaders(200, 0);
            for (int i = 0; i < 6; i++) {
                chunk(ex, "chunk" + i + "\n");
                Thread.sleep(400);
            }
        };
        startProxy(1);

        HttpResponse<String> response = client.send(request(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("chunk0\nchunk1\nchunk2\nchunk3\nchunk4\nchunk5\n", response.body(),
                "2.4s of stream under a 1s timeout: the timeout is idle time between chunks");
        assertEquals(List.of(), ledger.refunds);
    }

    @Test
    void aProviderErrorIsRelayedAsItIsAndRefunded() throws Exception {
        script = ex -> {
            byte[] json = "{\"detail\":{\"status\":\"voice_not_found\"}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(404, json.length);
            ex.getResponseBody().write(json);
        };
        startProxy(5);

        HttpResponse<String> response = client.send(request(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertEquals("{\"detail\":{\"status\":\"voice_not_found\"}}", response.body());
        waitFor("the refund", () -> ledger.refunds.size() == 1);
        assertEquals(List.of(), ledger.settles);
        assertNull(response.headers().firstValue("X-Aicoin-Charged").orElse(null));
    }

    @Test
    void theProvidersOwn402GoesOutAs503() throws Exception {
        script = ex -> {
            byte[] json = "{\"detail\":\"quota_exceeded\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(402, json.length);
            ex.getResponseBody().write(json);
        };
        startProxy(5);

        HttpResponse<String> response = client.send(request(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode());
        assertEquals("402", response.headers().firstValue("X-Upstream-Status").orElse(null));
        waitFor("the refund", () -> ledger.refunds.size() == 1);
    }

    @Test
    void aProviderThatGoesQuietBeforeAnyAudioIsA504AndRefunded() throws Exception {
        script = ex -> {
            ex.sendResponseHeaders(200, 0);
            Thread.sleep(3_000);
        };
        startProxy(1);

        HttpResponse<String> response = client.send(request(), HttpResponse.BodyHandlers.ofString());
        assertEquals(504, response.statusCode());
        waitFor("the refund", () -> ledger.refunds.size() == 1);
        assertEquals(List.of(), ledger.settles);
    }

    @Test
    void anEmpty2xxIsNoSpeechAndRefunded() throws Exception {
        script = ex -> ex.sendResponseHeaders(200, 0);
        startProxy(5);

        HttpResponse<String> response = client.send(request(), HttpResponse.BodyHandlers.ofString());
        assertEquals(502, response.statusCode());
        waitFor("the refund", () -> ledger.refunds.size() == 1);
    }

    @Test
    void anUnreachableProviderIsA502AndRefunded() throws Exception {
        upstream.stop(0);
        startProxy(5);

        HttpResponse<String> response = client.send(request(), HttpResponse.BodyHandlers.ofString());
        assertEquals(502, response.statusCode());
        waitFor("the refund", () -> ledger.refunds.size() == 1);
    }

    @Test
    void aStreamThatBreaksAfterItStartedEndsTruncatedAndStaysCharged() throws Exception {
        String first = "{\"audio_base64\":\"AAAA\"}\n\n";
        script = ex -> {
            ex.sendResponseHeaders(200, 0);
            chunk(ex, first);
            Thread.sleep(3_000);
        };
        startProxy(1);

        HttpResponse<InputStream> response = client.send(request(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        InputStream body = response.body();
        assertEquals(first, readAtLeast(body, first.length()));
        assertThrows(IOException.class, body::readAllBytes,
                "a stream cut short must not read as a complete one");
        assertEquals(List.of(3.0), ledger.settles);
        assertEquals(List.of(), ledger.refunds);
    }

    @Test
    void aClientThatLeavesBeforeAnyAudioIsRefunded() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        script = ex -> {
            released.await(10, TimeUnit.SECONDS);
            ex.sendResponseHeaders(200, 0);
            chunk(ex, "late\n");
        };
        startProxy(5);

        int port = ((InetSocketAddress) proxy.localAddress()).getPort();
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            String body = "{\"text\":\"hi\"}";
            socket.getOutputStream().write(("POST " + PATH + " HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + body.length() + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            waitFor("the upstream call", () -> upstreamUris.size() == 1);
        }
        waitFor("the refund", () -> ledger.refunds.size() == 1);
        released.countDown();
        Thread.sleep(200);
        assertEquals(List.of(1.0), ledger.refunds, "refunded once, and the late audio charges nothing");
        assertEquals(List.of(), ledger.settles);
    }

    @Test
    void onlyElevenLabsStreamingSpeechIsStreamed() {
        assertTrue(StreamingForwarder.streams("elevenlabs", "POST", "/v1/text-to-speech/abc/stream"));
        assertTrue(StreamingForwarder.streams("elevenlabs", "POST", "/v1/text-to-speech/abc/stream/with-timestamps"));
        assertFalse(StreamingForwarder.streams("elevenlabs", "POST", "/v1/text-to-speech/abc"));
        assertFalse(StreamingForwarder.streams("elevenlabs", "POST", "/v1/text-to-speech/abc/with-timestamps"));
        assertFalse(StreamingForwarder.streams("elevenlabs", "GET", "/v1/text-to-speech/abc/stream"));
        assertFalse(StreamingForwarder.streams("elevenlabs", "POST", "/v1/text-to-speech//stream"));
        assertFalse(StreamingForwarder.streams("elevenlabs", "POST", "/v1/music/stream"));
        assertFalse(StreamingForwarder.streams("openai", "POST", "/v1/text-to-speech/abc/stream"));
        assertEquals("/v1/text-to-speech/a/stream", ProxyFrontendHandler.pathOf("/v1/text-to-speech/a/stream?x=1"));
    }
}
