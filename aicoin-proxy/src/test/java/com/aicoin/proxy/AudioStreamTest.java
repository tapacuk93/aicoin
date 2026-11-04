package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /audio} with {@code "stream": true} ({@link AudioStream}): PCM handed over as the
 * provider produces it, failover only before the first sample, pieces in order, and each piece
 * billed and refunded on its own. Over real sockets against fake providers.
 */
class AudioStreamTest {

    private static final String WALLET = "e".repeat(64);

    static final class FakeLedger extends AicoinLedger {
        final List<String> debits = Collections.synchronizedList(new ArrayList<>());
        final List<String> refunds = Collections.synchronizedList(new ArrayList<>());
        final List<Double> settles = Collections.synchronizedList(new ArrayList<>());
        volatile boolean empty;

        @Override
        void debitForCall(String address, double amount, String provider, Consumer<DebitResult> onResult) {
            if (empty) {
                onResult.accept(DebitResult.insufficient(0));
                return;
            }
            debits.add(provider);
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
        void getSpending(String address, Consumer<Optional<Spending>> onResult) {
            onResult.accept(Optional.of(Spending.unlinked(0)));
        }
    }

    interface Script {
        void run(HttpExchange ex, String body) throws Exception;
    }

    private HttpServer upstream;
    private final Map<String, Script> scripts = new HashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private EventLoopGroup group;
    private Channel proxy;
    private FakeLedger ledger;

    @BeforeEach
    void setUp() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newCachedThreadPool());
        upstream.createContext("/", ex -> {
            String uri = ex.getRequestURI().toString();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(uri + " " + body);
            String key = uri.startsWith("/v1/text-to-speech/") ? "elevenlabs"
                    : uri.startsWith("/v1/audio/speech") ? "openai"
                    : uri.contains(":streamGenerateContent") ? "google" : "other";
            try {
                Script script = scripts.get(key);
                if (script == null) {
                    ex.sendResponseHeaders(404, -1);
                } else {
                    script.run(ex, body);
                }
            } catch (Exception e) {
                // The proxy hung up, or the script ends abruptly on purpose.
            } finally {
                ex.close();
            }
        });
        upstream.start();
        ledger = new FakeLedger();
        group = new NioEventLoopGroup(4);

        Map<String, String> env = new HashMap<>();
        for (String provider : List.of("elevenlabs", "openai", "google")) {
            String p = provider.toUpperCase(Locale.ROOT);
            env.put("AICOIN_PROXY_" + p + "_BASEURL", "http://127.0.0.1:" + upstream.getAddress().getPort());
            env.put("AICOIN_PROXY_" + p + "_APIKEY", "test-key");
        }
        env.put("AICOIN_PROXY_UPSTREAM_READ_TIMEOUT_SECONDS", "2");
        ProxyConfig config = ProxyConfig.load(env);
        ProviderHealthTracker health = new ProviderHealthTracker(10);
        ProviderSpeed speed = new ProviderSpeed(10);
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
                                CapabilityHandler.serve(ctx, Capability.AUDIO, ByteBufUtil.getBytes(req.content()),
                                        config, group, health, speed, null, ledger, WALLET);
                            }
                        });
                    }
                }).bind("127.0.0.1", 0).sync().channel();
    }

    @AfterEach
    void tearDown() {
        upstream.stop(0);
        proxy.close();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    // ---- a raw HTTP/1.1 client, so chunks and trailers can be seen as they arrive ----

    static final class RawResponse implements AutoCloseable {
        final Socket socket;
        final InputStream in;
        int status;
        final Map<String, String> headers = new LinkedHashMap<>();
        final Map<String, String> trailers = new LinkedHashMap<>();
        boolean chunked;

        RawResponse(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            String statusLine = line();
            status = Integer.parseInt(statusLine.split(" ")[1]);
            for (String h = line(); !h.isEmpty(); h = line()) {
                int colon = h.indexOf(':');
                headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
            }
            chunked = "chunked".equalsIgnoreCase(headers.get("transfer-encoding"));
        }

        String line() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != '\n') {
                if (b < 0) {
                    throw new EOFException("connection closed mid-line");
                }
                if (b != '\r') {
                    out.write(b);
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        }

        /** The next chunk's bytes, or null after the terminating chunk (whose trailers are kept). */
        byte[] chunk() throws IOException {
            int size = Integer.parseInt(line().trim(), 16);
            if (size == 0) {
                for (String h = line(); !h.isEmpty(); h = line()) {
                    int colon = h.indexOf(':');
                    trailers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
                }
                return null;
            }
            byte[] bytes = in.readNBytes(size);
            if (bytes.length < size) {
                throw new EOFException("connection closed mid-chunk");
            }
            line();
            return bytes;
        }

        byte[] rest() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] c = chunk(); c != null; c = chunk()) {
                out.writeBytes(c);
            }
            return out.toByteArray();
        }

        String fixedBody() throws IOException {
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            return new String(in.readNBytes(length), StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private RawResponse post(String json) throws IOException {
        Socket socket = new Socket("127.0.0.1", ((InetSocketAddress) proxy.localAddress()).getPort());
        socket.setSoTimeout(15_000);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("POST /audio HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: "
                + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
        return new RawResponse(socket);
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

    private static void send(HttpExchange ex, byte[] bytes) throws IOException {
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().flush();
    }

    /** Gemini's streamed array, one element per piece of samples, then the usage element. */
    private static byte[][] geminiElements(byte[]... sampleRuns) {
        List<byte[]> parts = new ArrayList<>();
        for (int i = 0; i < sampleRuns.length; i++) {
            String element = "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":"
                    + "\"audio/l16; rate=24000; channels=1\",\"data\":\""
                    + Base64.getEncoder().encodeToString(sampleRuns[i]) + "\"}}],\"role\":\"model\"},\"index\":0}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":" + (i + 1) + "}}";
            parts.add(((i == 0 ? "[" : "\n,\r\n") + element).getBytes(StandardCharsets.UTF_8));
        }
        parts.add(("\n,\r\n{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}],\"role\":\"model\"},"
                + "\"finishReason\":\"STOP\",\"index\":0}],\"usageMetadata\":{\"promptTokenCount\":10,"
                + "\"candidatesTokenCount\":50,\"totalTokenCount\":60}}\n]").getBytes(StandardCharsets.UTF_8));
        return parts.toArray(new byte[0][]);
    }

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    // ---- the tests ----

    @Test
    void elevenLabsSamplesReachTheClientWhileItIsStillSpeaking() throws Exception {
        CountDownLatch rest = new CountDownLatch(1);
        scripts.put("elevenlabs", (ex, body) -> {
            ex.sendResponseHeaders(200, 0);
            send(ex, new byte[] {1, 2, 3}); // an odd byte count: one sample and a half
            rest.await(10, TimeUnit.SECONDS);
            send(ex, new byte[] {4, 5, 6, 7, 8});
        });

        try (RawResponse response = post("{\"prompt\":\"Hello there.\",\"provider\":\"elevenlabs\",\"stream\":true}")) {
            assertEquals(200, response.status);
            assertTrue(response.chunked);
            assertEquals("audio/pcm;rate=24000;channels=1;encoding=s16le", response.headers.get("content-type"));
            assertEquals("pcm_s16le", response.headers.get("x-audio-encoding"));
            assertEquals("24000", response.headers.get("x-audio-sample-rate"));
            assertEquals("1", response.headers.get("x-audio-channels"));
            assertEquals("elevenlabs", response.headers.get("x-aicoin-provider"));
            assertEquals("eleven_multilingual_v2", response.headers.get("x-aicoin-model"));
            assertArrayEquals(new byte[] {1, 2}, response.chunk(),
                    "whole samples only, and before the provider has finished");
            rest.countDown();
            assertArrayEquals(new byte[] {3, 4, 5, 6, 7, 8}, response.rest());
            assertEquals("4", response.trailers.get("x-aicoin-charged"), "$0.03 per call at $0.009 a coin");
        }
        String request = requests.get(0);
        assertTrue(request.startsWith("/v1/text-to-speech/EXAVITQu4vr4xnSDxMaL/stream?output_format=pcm_24000 "), request);
        assertTrue(request.contains("\"model_id\":\"eleven_multilingual_v2\""), request);
        assertEquals(List.of("elevenlabs"), ledger.debits);
        assertEquals(List.of(), ledger.refunds);
        assertEquals(List.of(3.0), ledger.settles);
    }

    @Test
    void aProviderThatFailsBeforeAnySampleIsRefundedAndTheNextOneSpeaks() throws Exception {
        scripts.put("elevenlabs", (ex, body) -> {
            byte[] json = "{\"detail\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, json.length);
            send(ex, json);
        });
        scripts.put("openai", (ex, body) -> {
            ex.sendResponseHeaders(200, 0);
            send(ex, new byte[] {9, 9, 9, 9});
        });

        try (RawResponse response = post("{\"prompt\":\"Hello there.\",\"stream\":true}")) {
            assertEquals(200, response.status);
            assertEquals("openai", response.headers.get("x-aicoin-provider"));
            assertEquals("elevenlabs,openai,google", response.headers.get("x-aicoin-considered"));
            assertArrayEquals(new byte[] {9, 9, 9, 9}, response.rest());
            assertEquals("2", response.trailers.get("x-aicoin-charged"), "only OpenAI's $0.015");
        }
        assertTrue(requests.get(1).contains("\"response_format\":\"pcm\""), requests.get(1));
        assertEquals(List.of("elevenlabs"), ledger.refunds);
    }

    @Test
    void aLongPassageIsSpokenInPiecesWhoseSamplesArriveInOrder() throws Exception {
        String alpha = "Alpha " + "word ".repeat(110) + "end.";
        String bravo = "Bravo " + "word ".repeat(110) + "end.";
        String charlie = "Charlie " + "word ".repeat(110) + "end.";
        assertEquals(3, SpeechChunks.split(alpha + " " + bravo + " " + charlie).size());
        scripts.put("google", (ex, body) -> {
            int value = body.contains("Alpha") ? 1 : body.contains("Bravo") ? 2 : 3;
            if (value == 1) {
                Thread.sleep(500); // the first piece is the slowest: the others must wait for it
            }
            ex.sendResponseHeaders(200, 0);
            for (byte[] part : geminiElements(filled(6, value), filled(4, value))) {
                send(ex, part);
            }
        });

        byte[] all;
        try (RawResponse response = post("{\"prompt\":" + Json.string(alpha + " " + bravo + " " + charlie)
                + ",\"provider\":\"google\",\"stream\":true}")) {
            assertEquals(200, response.status);
            assertEquals("3", response.headers.get("x-aicoin-pieces"));
            all = response.rest();
        }
        byte[] expected = new byte[30];
        java.util.Arrays.fill(expected, 0, 10, (byte) 1);
        java.util.Arrays.fill(expected, 10, 20, (byte) 2);
        java.util.Arrays.fill(expected, 20, 30, (byte) 3);
        assertArrayEquals(expected, all);
        assertEquals(3, ledger.debits.size());
        assertEquals(List.of(), ledger.refunds);
        assertTrue(requests.get(0).contains(":streamGenerateContent"), requests.get(0));
    }

    @Test
    void aPieceThatFailsAfterTheStreamStartedCutsItShortAndIsRefunded() throws Exception {
        String alpha = "Alpha " + "word ".repeat(110) + "end.";
        String bravo = "Bravo " + "word ".repeat(110) + "end.";
        CountDownLatch alphaSent = new CountDownLatch(1);
        CountDownLatch finishAlpha = new CountDownLatch(1);
        scripts.put("google", (ex, body) -> {
            if (body.contains("Alpha")) {
                ex.sendResponseHeaders(200, 0);
                byte[][] parts = geminiElements(filled(4, 1), filled(4, 1));
                send(ex, parts[0]);
                alphaSent.countDown();
                finishAlpha.await(10, TimeUnit.SECONDS);
                for (int i = 1; i < parts.length; i++) {
                    send(ex, parts[i]);
                }
            } else {
                alphaSent.await(10, TimeUnit.SECONDS);
                Thread.sleep(200);
                byte[] json = "{\"error\":{\"code\":500}}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(500, json.length);
                send(ex, json);
            }
        });

        try (RawResponse response = post("{\"prompt\":" + Json.string(alpha + " " + bravo)
                + ",\"provider\":\"google\",\"stream\":true}")) {
            assertEquals(200, response.status);
            assertArrayEquals(filled(4, 1), response.chunk());
            assertThrows(IOException.class, response::rest, "a broken passage must not read as a whole one");
        }
        finishAlpha.countDown();
        waitFor("the refund", () -> ledger.refunds.size() == 1);
        assertEquals(List.of("google"), ledger.refunds, "Bravo never reached the client");
        assertEquals(1, ledger.settles.size(), "Alpha did, and is charged");
    }

    @Test
    void aWalletThatCannotPayIsTold402BeforeAnythingIsSent() throws Exception {
        ledger.empty = true;
        try (RawResponse response = post("{\"prompt\":\"Hello there.\",\"provider\":\"elevenlabs\",\"stream\":true}")) {
            assertEquals(402, response.status);
            assertTrue(response.fixedBody().contains("insufficient"));
        }
        assertEquals(List.of(), requests);
    }

    @Test
    void withoutStreamTheAnswerIsTheBufferedJsonAsBefore() throws Exception {
        scripts.put("elevenlabs", (ex, body) -> {
            byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00};
            ex.sendResponseHeaders(200, mp3.length);
            send(ex, mp3);
        });
        try (RawResponse response = post("{\"prompt\":\"Hello there.\",\"provider\":\"elevenlabs\"}")) {
            assertEquals(200, response.status);
            assertNull(response.headers.get("transfer-encoding"));
            assertTrue(response.fixedBody().contains("\"media_type\":\"audio/mpeg\""));
        }
        assertTrue(requests.get(0).startsWith("/v1/text-to-speech/EXAVITQu4vr4xnSDxMaL "), requests.get(0));
    }

    @Test
    void theGeminiDecoderFindsEveryElementWhereverTheReadsSplitIt() throws Exception {
        byte[][] parts = geminiElements(new byte[] {1, 2, 3, 4}, new byte[] {5, 6});
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            whole.writeBytes(part);
        }
        byte[] stream = whole.toByteArray();
        for (int split = 1; split < stream.length; split += 7) {
            AudioStream.GeminiStreamDecoder decoder = new AudioStream.GeminiStreamDecoder();
            ByteArrayOutputStream samples = new ByteArrayOutputStream();
            samples.writeBytes(decoder.feed(java.util.Arrays.copyOfRange(stream, 0, split)));
            samples.writeBytes(decoder.feed(java.util.Arrays.copyOfRange(stream, split, stream.length)));
            samples.writeBytes(decoder.finish());
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, samples.toByteArray(), "split at " + split);
            assertEquals(24000, decoder.sampleRate());
            assertTrue(decoder.pricingBody().contains("\"candidatesTokenCount\":50"), "priced from the last usage");
        }
    }

    @Test
    void aGeminiStreamThatCarriesAnErrorOrNoAudioIsAFailure() {
        AudioStream.GeminiStreamDecoder errored = new AudioStream.GeminiStreamDecoder();
        assertThrows(AudioStream.DecodeException.class, () -> errored.feed(
                "[{\"error\":{\"code\":500,\"message\":\"internal\"}}]".getBytes(StandardCharsets.UTF_8)));
        AudioStream.GeminiStreamDecoder silent = new AudioStream.GeminiStreamDecoder();
        assertThrows(AudioStream.DecodeException.class, () -> {
            silent.feed("[{\"candidates\":[]}]".getBytes(StandardCharsets.UTF_8));
            silent.finish();
        });
    }

    @Test
    void rawPcmIsHandedOnInWholeSamples() {
        AudioStream.RawPcmDecoder decoder = new AudioStream.RawPcmDecoder();
        assertArrayEquals(new byte[] {}, decoder.feed(new byte[] {1}));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, decoder.feed(new byte[] {2, 3, 4, 5}));
        assertArrayEquals(new byte[] {5, 6}, decoder.feed(new byte[] {6}));
    }
}
