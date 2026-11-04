package com.aicoin.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code POST /audio} with {@code "stream": true}: speech handed to the caller as raw samples while
 * the provider is still producing them — 16-bit signed little-endian mono PCM at 24 kHz, in an
 * HTTP/1.1 chunked body — instead of one base64 MP3 inside JSON when it has finished.
 *
 * <p>Raw samples, not MP3, because they are what every speaking provider here can be asked for in
 * a stream (ElevenLabs {@code output_format=pcm_24000}, OpenAI {@code response_format: "pcm"},
 * Gemini's {@code streamGenerateContent}, which only ever answers {@code audio/l16} at 24 kHz) and
 * what a client can play the most simply as it arrives: append the bytes to a buffer and schedule
 * it, with no decoder, no frame boundaries and no container. They cost bandwidth — 48 KB a second
 * against 8 KB for the 64 kbps MP3 the buffered call returns — which is the price of the first
 * sound arriving in under a second instead of after the whole passage.
 *
 * <p>Cloud TTS ({@code googletts}) cannot stream over REST (its streaming synthesis is gRPC only),
 * so it is still asked for the whole clip, and that clip is sent as the stream's one burst of
 * samples: the same format, no earlier.
 *
 * <p><b>Routing, failover and billing</b> are {@link CapabilityHandler}'s: the same candidates,
 * the same pinning, up to three providers; each piece of a long passage (see {@link SpeechChunks})
 * is an ordinary paid call holding one coin, priced as the buffered call prices it. Failover is
 * possible only until the first sample has gone to the client, because the status and the
 * provider's name go out with it. A piece is refunded if it fails before any of its samples
 * reached the client; once some have, it is charged, as the provider charged for it. A failure
 * after the stream started resets the connection without the terminating chunk ({@link
 * UpstreamStream#abortClient}), so the client sees a broken body, not a recording that quietly
 * stops.
 *
 * <p>Pieces are requested all at once, as the buffered call does, and their samples written in
 * order: the first piece live, each later one from what it has buffered meanwhile, then live.
 */
final class AudioStream {

    private static final Logger LOG = Logger.getLogger(AudioStream.class.getName());

    static final int SAMPLE_RATE = 24_000;
    static final String CONTENT_TYPE = "audio/pcm;rate=24000;channels=1;encoding=s16le";

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR_BODY = 16 * 1024;

    private AudioStream() {
    }

    /** Whether this provider's speech arrives incrementally; the others arrive as one burst. */
    static boolean isIncremental(String provider) {
        return "elevenlabs".equals(provider) || "openai".equals(provider) || "google".equals(provider);
    }

    /** One streaming text-to-speech request for {@code provider}. */
    static MediaAdapter.Request request(String provider, String model, String voice, String text, String language) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(new AbstractMap.SimpleEntry<>("content-type", "application/json"));
        headers.add(new AbstractMap.SimpleEntry<>("accept-encoding", "identity"));
        if ("elevenlabs".equals(provider)) {
            String json = "{\"text\":" + Json.string(text) + ",\"model_id\":" + Json.string(model) + "}";
            return new MediaAdapter.Request("/v1/text-to-speech/" + voice + "/stream?output_format=pcm_24000",
                    headers, json.getBytes(StandardCharsets.UTF_8));
        }
        if ("openai".equals(provider)) {
            String json = "{\"model\":" + Json.string(model) + ",\"voice\":" + Json.string(voice)
                    + ",\"input\":" + Json.string(text) + ",\"response_format\":\"pcm\"}";
            return new MediaAdapter.Request("/v1/audio/speech", headers, json.getBytes(StandardCharsets.UTF_8));
        }
        MediaAdapter.Request buffered = MediaAdapter.audio(provider, model, voice, text, language);
        if ("google".equals(provider)) {
            // The same body as the buffered call, to the streaming method. Answered as a JSON array
            // whose elements arrive one by one, each with a few hundred milliseconds of samples.
            return new MediaAdapter.Request("/v1beta/models/" + model + ":streamGenerateContent", headers,
                    buffered.body());
        }
        // Cloud TTS: the whole clip, asked for at the stream's rate.
        String json = new String(buffered.body(), StandardCharsets.UTF_8)
                .replace("\"audioEncoding\":\"LINEAR16\"", "\"audioEncoding\":\"LINEAR16\",\"sampleRateHertz\":" + SAMPLE_RATE);
        return new MediaAdapter.Request(buffered.path(), headers, json.getBytes(StandardCharsets.UTF_8));
    }

    static Decoder decoderFor(String provider) {
        if ("google".equals(provider)) {
            return new GeminiStreamDecoder();
        }
        if ("googletts".equals(provider)) {
            return new WholeClipDecoder(provider);
        }
        return new RawPcmDecoder();
    }

    /** A provider stream that held no usable speech, or said it failed. */
    static final class DecodeException extends Exception {
        DecodeException(String message) {
            super(message);
        }
    }

    /** Turns a provider's 2xx body, as it arrives, into samples. */
    interface Decoder {
        /** Samples ready from these body bytes — always a whole number of them; may be empty. */
        byte[] feed(byte[] bytes) throws DecodeException;

        /** The body has ended: any samples still held. */
        byte[] finish() throws DecodeException;

        /** The samples' rate as the provider stated it (24 kHz unless it said otherwise). */
        int sampleRate();

        /** What to price the call from when the model has no per-call rate: the provider's usage. */
        String pricingBody();
    }

    /** ElevenLabs and OpenAI: the body is the samples. Holds back a trailing odd byte. */
    static final class RawPcmDecoder implements Decoder {
        private int carry = -1;

        @Override
        public byte[] feed(byte[] bytes) {
            int have = bytes.length + (carry >= 0 ? 1 : 0);
            int out = have & ~1;
            byte[] samples = new byte[out];
            int at = 0;
            int from = 0;
            if (carry >= 0 && out > 0) {
                samples[at++] = (byte) carry;
                carry = -1;
            }
            while (at < out) {
                samples[at++] = bytes[from++];
            }
            if (from < bytes.length) {
                carry = bytes[from] & 0xFF;
            }
            return samples;
        }

        @Override
        public byte[] finish() {
            return new byte[0];
        }

        @Override
        public int sampleRate() {
            return SAMPLE_RATE;
        }

        @Override
        public String pricingBody() {
            return "";
        }
    }

    /**
     * Gemini's {@code streamGenerateContent}: a JSON array, {@code [{...}\n,\r\n{...}...]}, each
     * element a whole {@code GenerateContentResponse} whose {@code inlineData.data} is base64 of
     * bare samples ({@code audio/l16; rate=24000; channels=1}, little-endian whatever "L16" says).
     * Elements are found by tracking depth outside strings, so one split across reads is simply
     * finished by the next.
     */
    static final class GeminiStreamDecoder implements Decoder {
        private final ByteArrayOutputStream element = new ByteArrayOutputStream();
        private int depth;
        private int elementDepth;
        private boolean inString;
        private boolean escaped;
        private int rate = SAMPLE_RATE;
        private String lastUsage = "";
        private boolean sawAudio;

        @Override
        public byte[] feed(byte[] bytes) throws DecodeException {
            ByteArrayOutputStream samples = new ByteArrayOutputStream();
            for (byte b : bytes) {
                boolean inside = elementDepth > 0 && depth >= elementDepth;
                if (inString) {
                    element.write(b);
                    if (escaped) {
                        escaped = false;
                    } else if (b == '\\') {
                        escaped = true;
                    } else if (b == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (b == '{' || b == '[') {
                    if (elementDepth == 0) {
                        // The first structural byte says whether elements are inside an array.
                        elementDepth = b == '[' ? 2 : 1;
                    }
                    depth++;
                    if (depth == elementDepth) {
                        element.reset();
                    }
                    if (depth >= elementDepth) {
                        element.write(b);
                    }
                } else if (b == '}' || b == ']') {
                    if (inside) {
                        element.write(b);
                    }
                    depth--;
                    if (inside && depth == elementDepth - 1) {
                        samples.writeBytes(element(element.toString(StandardCharsets.UTF_8)));
                        element.reset();
                    }
                } else if (inside) {
                    element.write(b);
                    if (b == '"') {
                        inString = true;
                    }
                }
            }
            return samples.toByteArray();
        }

        private byte[] element(String json) throws DecodeException {
            if (json.contains("\"usageMetadata\"")) {
                lastUsage = json;
            }
            int inline = json.indexOf("\"inlineData\"");
            if (inline < 0) {
                if (json.startsWith("{\"error\"") || json.contains("\"error\":{")) {
                    throw new DecodeException("provider error in stream: "
                            + json.substring(0, Math.min(200, json.length())));
                }
                return new byte[0];
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (inline >= 0) {
                String data = stringField(json, "data", inline);
                String mimeType = stringField(json, "mimeType", inline);
                if (data != null && !data.isEmpty()) {
                    byte[] bytes;
                    try {
                        bytes = Base64.getDecoder().decode(data);
                    } catch (IllegalArgumentException e) {
                        throw new DecodeException("undecodable audio in stream");
                    }
                    MediaAdapter.Wav wav = MediaAdapter.Wav.read(bytes);
                    if (wav != null) {
                        rate = wav.sampleRate;
                        bytes = wav.samples;
                    } else if (mimeType != null) {
                        rate = MediaAdapter.pcmRate(mimeType);
                    }
                    out.writeBytes(bytes);
                    sawAudio = true;
                }
                inline = json.indexOf("\"inlineData\"", inline + 1);
            }
            byte[] samples = out.toByteArray();
            // Whole samples only; an odd byte would shift every sample after it.
            return samples.length % 2 == 0 ? samples : java.util.Arrays.copyOf(samples, samples.length - 1);
        }

        @Override
        public byte[] finish() throws DecodeException {
            if (!sawAudio) {
                throw new DecodeException("no audio in stream");
            }
            return new byte[0];
        }

        @Override
        public int sampleRate() {
            return rate;
        }

        @Override
        public String pricingBody() {
            return lastUsage;
        }
    }

    /** Cloud TTS: the whole reply, read as the buffered call reads it, at the end. */
    static final class WholeClipDecoder implements Decoder {
        private final String provider;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int rate = SAMPLE_RATE;

        WholeClipDecoder(String provider) {
            this.provider = provider;
        }

        @Override
        public byte[] feed(byte[] bytes) {
            body.writeBytes(bytes);
            return new byte[0];
        }

        @Override
        public byte[] finish() throws DecodeException {
            MediaAdapter.Pcm pcm = MediaAdapter.speechPcm(provider, body.toByteArray());
            if (pcm == null || pcm.channels != 1) {
                throw new DecodeException("no audio in response");
            }
            rate = pcm.sampleRate;
            return pcm.samples.length % 2 == 0 ? pcm.samples
                    : java.util.Arrays.copyOf(pcm.samples, pcm.samples.length - 1);
        }

        @Override
        public int sampleRate() {
            return rate;
        }

        @Override
        public String pricingBody() {
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    /** The string value of the first {@code "name":} at or after {@code from}, or null. */
    private static String stringField(String json, String name, int from) {
        int key = json.indexOf("\"" + name + "\"", from);
        if (key < 0) {
            return null;
        }
        int colon = json.indexOf(':', key + name.length() + 2);
        int open = colon < 0 ? -1 : json.indexOf('"', colon + 1);
        int close = open < 0 ? -1 : json.indexOf('"', open + 1);
        return close < 0 ? null : json.substring(open + 1, close);
    }

    /** What {@link CapabilityHandler} has decided before handing the request over. */
    static final class Plan {
        final String prompt;
        final String subject;
        final List<String> ranked;
        final String voice;
        final String language;

        Plan(String prompt, String subject, List<String> ranked, String voice, String language) {
            this.prompt = prompt;
            this.subject = subject;
            this.ranked = ranked;
            this.voice = voice;
            this.language = language;
        }
    }

    static void serve(ChannelHandlerContext ctx, ProxyConfig config, EventLoopGroup group,
                      ProviderHealthTracker healthTracker, ProviderSpeed speed, AicoinLedger ledger,
                      String wallet, Plan plan) {
        new Session(ctx, config, group, healthTracker, speed, ledger, wallet, plan).next();
    }

    /** One request, working down the ranked providers until one starts talking. */
    private static final class Session {
        private final ChannelHandlerContext ctx;
        private final ProxyConfig config;
        private final EventLoopGroup group;
        private final ProviderHealthTracker healthTracker;
        private final ProviderSpeed speed;
        private final AicoinLedger ledger;
        private final String wallet;
        private final Plan plan;
        private final Transactions.Tag tag;

        private final List<String> tried = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private int index;
        private int calls;
        private long coinsCharged;

        /** The attempt now under way; null between attempts. */
        private Attempt current;
        private boolean headSent;
        private boolean over;
        private final ChannelFutureListener clientGone;

        Session(ChannelHandlerContext ctx, ProxyConfig config, EventLoopGroup group,
                ProviderHealthTracker healthTracker, ProviderSpeed speed, AicoinLedger ledger, String wallet,
                Plan plan) {
            this.ctx = ctx;
            this.config = config;
            this.group = group;
            this.healthTracker = healthTracker;
            this.speed = speed;
            this.ledger = ledger;
            this.wallet = wallet;
            this.plan = plan;
            this.tag = Transactions.Tag.of(ctx.channel());
            this.clientGone = f -> clientLeft();
            ctx.channel().closeFuture().addListener(clientGone);
        }

        synchronized void next() {
            current = null;
            if (over) {
                return;
            }
            if (index >= plan.ranked.size() || index >= MAX_ATTEMPTS) {
                finishWithError(HttpResponseStatus.BAD_GATEWAY,
                        "{\"error\":\"no provider answered\",\"capability\":\"audio\",\"subject\":"
                                + Json.string(plan.subject) + ",\"tried\":[" + joinQuoted(tried) + "]"
                                + ",\"calls\":" + calls + ",\"coins_charged\":" + coinsCharged
                                + ",\"errors\":[" + String.join(",", errors) + "]}");
                return;
            }
            String provider = plan.ranked.get(index++);
            tried.add(provider);
            String model = config.getCapabilities().modelFor(Capability.AUDIO, provider);
            String requested = plan.voice == null ? "" : plan.voice.trim();
            String voice = requested.isEmpty() ? config.getCapabilities().voiceFor(provider) : requested;
            List<String> texts = MediaAdapter.speaksInSamples(provider)
                    ? SpeechChunks.split(plan.prompt) : List.of(plan.prompt);
            current = new Attempt(this, provider, model, voice, texts);
            current.start();
        }

        private synchronized void clientLeft() {
            if (over) {
                return;
            }
            over = true;
            if (current != null) {
                current.abort("client gone");
            }
        }

        /** The attempt could not be used and nothing has gone to the client: the next provider's turn. */
        synchronized void attemptFailed(Attempt attempt, String error) {
            if (attempt != current) {
                return;
            }
            speed.record(attempt.provider, Capability.AUDIO, 0, System.currentTimeMillis() - attempt.began);
            errors.add("{\"provider\":" + Json.string(attempt.provider) + ",\"error\":" + Json.string(error) + "}");
            next();
        }

        synchronized void insufficient(Attempt attempt) {
            if (attempt != current || over) {
                return;
            }
            over = true;
            ledger.getSpending(wallet, spending -> finishWithError(HttpResponseStatus.PAYMENT_REQUIRED,
                    spending.map(s -> s.insufficientJson(BilledCall.CALL_COST_AICOIN))
                            .orElse(AicoinLedger.insufficientJson(0, false, false, 0)), true));
        }

        synchronized void counted(long coins, boolean attempted) {
            if (attempted) {
                calls++;
            }
            coinsCharged += coins;
        }

        /** Samples for the client, in order. The first ones send the head. */
        synchronized void write(Attempt attempt, byte[] samples, int rate) {
            if (attempt != current || over || samples.length == 0) {
                return;
            }
            if (!headSent) {
                headSent = true;
                HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                head.headers().set(HttpHeaderNames.CONTENT_TYPE,
                        "audio/pcm;rate=" + rate + ";channels=1;encoding=s16le");
                head.headers().set("X-Audio-Encoding", "pcm_s16le");
                head.headers().set("X-Audio-Sample-Rate", Integer.toString(rate));
                head.headers().set("X-Audio-Channels", "1");
                head.headers().set("X-Aicoin-Capability", "audio");
                head.headers().set("X-Aicoin-Provider", attempt.provider);
                head.headers().set("X-Aicoin-Model", attempt.model);
                head.headers().set("X-Aicoin-Subject", plan.subject);
                head.headers().set("X-Aicoin-Considered", String.join(",", plan.ranked));
                head.headers().set("X-Aicoin-Pieces", Integer.toString(attempt.pieces.size()));
                HttpUtil.setTransferEncodingChunked(head, true);
                ctx.write(head);
            }
            ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(samples)));
        }

        synchronized boolean hasStarted() {
            return headSent;
        }

        /** Every piece is in. */
        synchronized void completed(Attempt attempt) {
            if (attempt != current || over) {
                return;
            }
            over = true;
            if (!headSent) {
                // Every piece answered and none had a sample in it.
                over = false;
                attemptFailed(attempt, "no audio in response");
                return;
            }
            speed.record(attempt.provider, Capability.AUDIO, 200, System.currentTimeMillis() - attempt.began);
            ctx.channel().closeFuture().removeListener(clientGone);
            LastHttpContent last = new DefaultLastHttpContent();
            last.trailingHeaders().set("X-Aicoin-Charged", Long.toString(coinsCharged));
            ctx.writeAndFlush(last);
        }

        /** A piece broke after samples went out: nothing can be retried, so the stream is cut. */
        synchronized void broke(Attempt attempt, String error) {
            if (attempt != current || over) {
                return;
            }
            over = true;
            LOG.log(Level.INFO, attempt.provider + " speech stream broke after it started: " + error);
            ctx.channel().closeFuture().removeListener(clientGone);
            UpstreamStream.abortClient(ctx);
        }

        private void finishWithError(HttpResponseStatus status, String json) {
            finishWithError(status, json, false);
        }

        private synchronized void finishWithError(HttpResponseStatus status, String json, boolean alreadyOver) {
            if (over && !alreadyOver) {
                return;
            }
            over = true;
            ctx.channel().closeFuture().removeListener(clientGone);
            byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().set("X-Aicoin-Charged", Long.toString(coinsCharged));
            HttpUtil.setContentLength(response, bytes.length);
            ctx.writeAndFlush(response);
        }
    }

    /** One provider's go at the whole passage: one piece per {@link SpeechChunks} piece. */
    private static final class Attempt {
        final Session session;
        final String provider;
        final String model;
        final String voice;
        final List<Piece> pieces = new ArrayList<>();
        final long began = System.currentTimeMillis();
        /** The piece whose samples go to the client now; later ones buffer. */
        private int playing;
        private boolean failed;
        private int rate = -1;

        Attempt(Session session, String provider, String model, String voice, List<String> texts) {
            this.session = session;
            this.provider = provider;
            this.model = model;
            this.voice = voice;
            for (int i = 0; i < texts.size(); i++) {
                pieces.add(new Piece(this, i, texts.get(i)));
            }
        }

        void start() {
            for (Piece piece : pieces) {
                piece.start();
            }
        }

        void samples(Piece piece, byte[] samples, int sampleRate) {
            if (failed || samples.length == 0) {
                return;
            }
            if (rate < 0) {
                rate = sampleRate;
            } else if (rate != sampleRate) {
                pieceFailed(piece, "pieces disagree on sample rate", false);
                return;
            }
            if (piece.index == playing) {
                piece.delivered = true;
                session.write(this, samples, rate);
            } else {
                piece.pending.add(samples);
            }
        }

        void pieceDone(Piece piece) {
            if (failed) {
                return;
            }
            while (playing < pieces.size() && pieces.get(playing).done) {
                playing++;
                if (playing < pieces.size()) {
                    Piece next = pieces.get(playing);
                    for (byte[] buffered : next.pending) {
                        next.delivered = true;
                        session.write(this, buffered, rate);
                    }
                    next.pending.clear();
                }
            }
            if (playing >= pieces.size()) {
                session.completed(this);
            }
        }

        void pieceFailed(Piece piece, String error, boolean insufficient) {
            if (failed) {
                return;
            }
            failed = true;
            for (Piece other : pieces) {
                if (other != piece) {
                    other.cancel();
                }
            }
            if (session.hasStarted()) {
                // Too late for any other answer: the status went out with the first samples.
                session.broke(this, error);
            } else if (insufficient) {
                session.insufficient(this);
            } else {
                session.attemptFailed(this, error);
            }
        }

        void abort(String why) {
            failed = true;
            for (Piece piece : pieces) {
                piece.cancel();
            }
        }
    }

    /** One paid streaming call to the provider. */
    private static final class Piece implements UpstreamStream.Listener {
        final Attempt attempt;
        final int index;
        final String text;
        final Decoder decoder;
        final List<byte[]> pending = new ArrayList<>();
        final Transactions.Call record;
        /** Some of this piece's samples have been written to the client: from now on it is charged. */
        boolean delivered;
        boolean done;

        private UpstreamStream.Handle handle;
        private HttpResponse head;
        private ByteArrayOutputStream errorBody;
        /** Holding a coin that has been neither kept nor refunded yet. */
        private boolean holding;
        private boolean cancelled;

        Piece(Attempt attempt, int index, String text) {
            this.attempt = attempt;
            this.index = index;
            this.text = text;
            this.decoder = decoderFor(attempt.provider);
            this.record = Transactions.Call.of(attempt.session.wallet, attempt.session.tag, attempt.provider)
                    .model(attempt.model).endpoint("/audio");
        }

        private Session session() {
            return attempt.session;
        }

        void start() {
            Session s = session();
            s.ledger.debitForCall(s.wallet, BilledCall.CALL_COST_AICOIN, attempt.provider, debit -> {
                synchronized (s) {
                    if (!debit.isReachable()) {
                        attempt.pieceFailed(this, "could not validate wallet", false);
                        return;
                    }
                    if (!debit.isSuccess()) {
                        Transactions.recordCall(record.failed(402, "insufficient balance"));
                        attempt.pieceFailed(this, "insufficient balance", true);
                        return;
                    }
                    holding = true;
                    if (cancelled) {
                        refund("cancelled before it started", 499);
                        return;
                    }
                    s.counted(0, true);
                    MediaAdapter.Request request = request(attempt.provider, attempt.model, attempt.voice, text,
                            s.plan.language);
                    ProviderConfig providerConfig = s.config.getProvider(attempt.provider);
                    AuthInjector.Injection injection = AuthInjector.compute(providerConfig);
                    List<Map.Entry<String, String>> headers = new ArrayList<>(request.headers());
                    String uri = request.path();
                    if (injection.isQueryParam()) {
                        uri = AuthInjector.appendQueryParam(uri, injection.getName(), injection.getValue());
                    } else {
                        headers.add(new AbstractMap.SimpleEntry<>(injection.getName(), injection.getValue()));
                    }
                    UpstreamTimer.Span span = UpstreamTimer.of(s.ctx.channel()).begin();
                    handle = UpstreamStream.open(s.group, s.config.getUpstreamReadTimeoutSeconds(),
                            providerConfig.getBaseUrl(), HttpMethod.POST, uri, headers, request.body(),
                            new SpanEnding(this, span, s));
                }
            });
        }

        void cancel() {
            cancelled = true;
            if (handle != null) {
                handle.cancel();
            }
            if (holding) {
                if (delivered) {
                    charge();
                } else {
                    refund("cancelled", 499);
                }
            }
        }

        @Override
        public void onHead(HttpResponse head) {
            this.head = head;
            session().healthTracker.record(attempt.provider, head.status().code());
            if (head.status().code() / 100 != 2) {
                errorBody = new ByteArrayOutputStream();
            }
        }

        @Override
        public void onData(ByteBuf chunk) {
            try {
                byte[] bytes = new byte[chunk.readableBytes()];
                chunk.readBytes(bytes);
                if (errorBody != null) {
                    if (errorBody.size() < MAX_ERROR_BODY) {
                        errorBody.writeBytes(bytes);
                    }
                    return;
                }
                byte[] samples;
                try {
                    samples = decoder.feed(bytes);
                } catch (DecodeException e) {
                    fail(e.getMessage(), 502);
                    return;
                }
                attempt.samples(this, samples, decoder.sampleRate());
            } finally {
                chunk.release();
            }
        }

        @Override
        public void onEnd() {
            if (errorBody != null) {
                int code = head.status().code();
                byte[] body = errorBody.toByteArray();
                AdminAlerts.get().upstreamResponse(attempt.provider, code, body);
                ProviderCredit.get().record(attempt.provider, code, body);
                fail("upstream returned HTTP " + code, code);
                return;
            }
            byte[] rest;
            try {
                rest = decoder.finish();
            } catch (DecodeException e) {
                fail(e.getMessage(), 502);
                return;
            }
            ProviderCredit.get().record(attempt.provider, head.status().code(), new byte[0]);
            attempt.samples(this, rest, decoder.sampleRate());
            charge();
            done = true;
            attempt.pieceDone(this);
        }

        @Override
        public void onFailure(String error, boolean timedOut) {
            fail(error, timedOut ? 504 : 502);
        }

        private void fail(String error, int status) {
            if (handle != null) {
                handle.cancel();
            }
            if (holding) {
                if (delivered) {
                    // The client has some of this piece and the provider billed for all of it.
                    charge();
                } else {
                    refund(error, status);
                }
            }
            attempt.pieceFailed(this, error, false);
        }

        /** Keeps the held coin and settles the rest of the price, as {@link BilledCall} does. */
        private void charge() {
            if (!holding) {
                return;
            }
            holding = false;
            Session s = session();
            CostCalculator.Priced priced = CostCalculator.priceKnownModel(attempt.provider, attempt.model,
                    s.config.getModelPricing());
            if (priced == null) {
                priced = CostCalculator.price(attempt.provider, decoder.pricingBody(), s.config.getModelPricing());
            }
            s.ledger.recordEvent(attempt.provider, priced.getCostUsd(),
                    priced.isTokensKnown() ? priced.getTokens() : -1, Instant.now(), s.wallet);
            long charged = 1L;
            if (s.config.isMeteredBilling()) {
                charged = CoinMeter.coinsFor(priced.getCostUsd(), s.config.getCoinValueUsd());
                s.ledger.settleCall(s.wallet, charged - BilledCall.CALL_COST_AICOIN, attempt.provider);
            }
            Transactions.recordCall(record.succeeded(200, charged, priced.getCostUsd()));
            s.counted(charged, false);
        }

        private void refund(String error, int status) {
            if (!holding) {
                return;
            }
            holding = false;
            Session s = session();
            s.ledger.refund(s.wallet, BilledCall.CALL_COST_AICOIN, attempt.provider);
            Transactions.recordCall(record.failed(status, error));
        }
    }

    /**
     * Ends the piece's share of the request's upstream time when its call ends, and takes the
     * request's one lock around every callback: a request's pieces, its attempt and its session
     * all change together, from several event loops, so they share one monitor (the {@link
     * Session}) rather than each having its own and deadlocking on the order they are taken in.
     */
    private static final class SpanEnding implements UpstreamStream.Listener {
        private final UpstreamStream.Listener inner;
        private final UpstreamTimer.Span span;
        private final Object lock;

        SpanEnding(UpstreamStream.Listener inner, UpstreamTimer.Span span, Object lock) {
            this.inner = inner;
            this.span = span;
            this.lock = lock;
        }

        @Override
        public void onHead(HttpResponse head) {
            synchronized (lock) {
                inner.onHead(head);
            }
        }

        @Override
        public void onData(ByteBuf chunk) {
            synchronized (lock) {
                inner.onData(chunk);
            }
        }

        @Override
        public void onEnd() {
            span.end();
            synchronized (lock) {
                inner.onEnd();
            }
        }

        @Override
        public void onFailure(String error, boolean timedOut) {
            span.end();
            synchronized (lock) {
                inner.onFailure(error, timedOut);
            }
        }
    }

    private static String joinQuoted(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            out.append(i == 0 ? "" : ",").append(Json.string(values.get(i)));
        }
        return out.toString();
    }
}
