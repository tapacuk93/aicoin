package com.aicoin.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link UpstreamForwarder} for the provider endpoints whose answer is a stream — ElevenLabs'
 * {@code POST /v1/text-to-speech/{voice}/stream} and {@code .../stream/with-timestamps} — relayed
 * chunk by chunk as the provider sends them, instead of read whole and then written.
 *
 * <p>Read whole, a streaming endpoint is no better than the plain one: a reader waits for the last
 * second of a paragraph to be synthesised before hearing the first. Relayed, the first audio
 * reaches the app as soon as the provider has it.
 *
 * <p>Everything else is {@link UpstreamForwarder}'s, deliberately:
 * <ul>
 *   <li><b>Billing</b> is the same: the gate's one coin is held before forwarding, and the call is
 *       priced exactly as the non-streaming endpoint is — by the provider's per-call figure
 *       ({@code pricing.providers.elevenlabs.usdPerCall}), which never read the body anyway.</li>
 *   <li><b>Refunds</b>: the call is settled (kept) at the first byte of a 2xx body, because that is
 *       the moment the client has something it paid for. Anything that ends the call before then —
 *       a non-2xx answer, connect/write failure, the idle timeout, the provider closing early, an
 *       empty 2xx body, or the client walking away — refunds the coin, exactly once. After the
 *       first byte the charge stands whatever happens: the provider bills for the request, and the
 *       client already holds audio it can play.</li>
 *   <li><b>Errors</b> before the first byte are answered as {@link UpstreamForwarder} answers them:
 *       the provider's own status and body (402 relayed as 503 with {@code X-Upstream-Status}), or a
 *       synthetic 502/504. A failure after the first byte cannot change a status that has already
 *       gone out, so the client connection is reset without the terminating chunk ({@link
 *       UpstreamStream#abortClient}): the client sees a broken body (URLSession: {@code
 *       NSURLErrorNetworkConnectionLost}), never a stream that silently looks complete.</li>
 *   <li><b>Timeout</b>: {@code upstream.readTimeoutSeconds} is an idle timeout between chunks, not a
 *       bound on the whole stream (see {@link UpstreamStream}).</li>
 * </ul>
 *
 * <p>The answer goes out with {@code Transfer-Encoding: chunked}, one chunk per upstream read,
 * flushed immediately. {@code X-Aicoin-Charged} is known when the head goes out (a per-call price),
 * so it is an ordinary header and the stream carries no trailer — not every HTTP client reads
 * trailers well (the JDK's trips an assertion on them).
 */
final class StreamingForwarder {

    private static final Logger LOG = Logger.getLogger(StreamingForwarder.class.getName());

    /** An error body is read whole to relay it; one larger than this is cut, it is not audio. */
    private static final int MAX_ERROR_BODY = 64 * 1024;

    private StreamingForwarder() {
    }

    /**
     * Whether a call is relayed by this class rather than {@link UpstreamForwarder}: ElevenLabs'
     * two streaming speech endpoints, nothing else. {@code path} is without its query string.
     */
    static boolean streams(String provider, String method, String path) {
        if (!"elevenlabs".equals(provider) || !"POST".equalsIgnoreCase(method) || path == null) {
            return false;
        }
        String prefix = "/v1/text-to-speech/";
        if (!path.startsWith(prefix)) {
            return false;
        }
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String tail = rest.substring(slash);
        return "/stream".equals(tail) || "/stream/with-timestamps".equals(tail);
    }

    /**
     * What a streamed call costs the proxy: the provider's per-call figure, which is what the
     * buffered endpoint is priced at too. A provider priced by tokens would be priced from its
     * body, which a relayed stream never holds; that falls to the configured default, the figure
     * an unreadable body gets on the buffered path.
     */
    static CostCalculator.Priced costOf(ProxyConfig config, String provider) {
        Double perCall = config.getModelPricing().perCallUsd(provider);
        if (perCall != null) {
            return new CostCalculator.Priced(perCall, 0, false);
        }
        return CostCalculator.price(provider, "", config.getModelPricing());
    }

    static void forward(EventLoopGroup group,
                        ProxyConfig config,
                        ProviderHealthTracker healthTracker,
                        AicoinLedger ledger,
                        ChannelHandlerContext clientCtx,
                        HttpMethod method,
                        String forwardUri,
                        List<Map.Entry<String, String>> headers,
                        byte[] body,
                        String baseUrl,
                        String provider,
                        String walletAddress,
                        double callCostAicoin) {
        UpstreamForwarder.Recorder recorder = callCostAicoin > 0
                ? new UpstreamForwarder.Recorder(
                        Transactions.Call.of(walletAddress, Transactions.Tag.of(clientCtx.channel()), provider)
                                .model(Transactions.modelOf(Transactions.endpointOf(forwardUri), body))
                                .endpoint(Transactions.endpointOf(forwardUri)))
                : UpstreamForwarder.Recorder.NONE;

        // Each chunk is relayed as it is, so it has to be readable as it is: no compression.
        List<Map.Entry<String, String>> outHeaders = new ArrayList<>();
        for (Map.Entry<String, String> h : headers) {
            if (!h.getKey().equalsIgnoreCase(HttpHeaderNames.ACCEPT_ENCODING.toString())) {
                outHeaders.add(h);
            }
        }

        // Who owns the debit — the stream reaching its first byte, a failure before it, or the
        // client leaving — exactly as in UpstreamForwarder.
        AtomicBoolean settled = new AtomicBoolean(false);
        UpstreamTimer.Span span = UpstreamTimer.of(clientCtx.channel()).begin();
        AtomicReference<UpstreamStream.Handle> handle = new AtomicReference<>();

        ChannelFutureListener clientGone = f -> {
            span.end();
            if (settled.compareAndSet(false, true)) {
                LOG.log(Level.FINE, "client gone before the stream started; aborting " + provider + " call");
                UpstreamForwarder.refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
                recorder.failed(499, "client gone before the provider answered");
            }
            UpstreamStream.Handle h = handle.get();
            if (h != null) {
                h.cancel();
            }
        };

        Relay relay = new Relay(config, healthTracker, ledger, clientCtx, provider, walletAddress, callCostAicoin,
                settled, span, recorder, handle, clientGone);
        clientCtx.channel().closeFuture().addListener(clientGone);
        handle.set(UpstreamStream.open(group, config.getUpstreamReadTimeoutSeconds(), baseUrl, method, forwardUri,
                outHeaders, body, relay));
    }

    private static final class Relay implements UpstreamStream.Listener {
        private final ProxyConfig config;
        private final ProviderHealthTracker healthTracker;
        private final AicoinLedger ledger;
        private final ChannelHandlerContext clientCtx;
        private final String provider;
        private final String walletAddress;
        private final double callCostAicoin;
        private final AtomicBoolean settled;
        private final UpstreamTimer.Span span;
        private final UpstreamForwarder.Recorder recorder;
        private final AtomicReference<UpstreamStream.Handle> handle;
        private final ChannelFutureListener clientGone;

        private HttpResponse head;
        private ByteArrayOutputStream errorBody;
        private boolean streaming;
        private long charged;

        Relay(ProxyConfig config, ProviderHealthTracker healthTracker, AicoinLedger ledger,
              ChannelHandlerContext clientCtx, String provider, String walletAddress, double callCostAicoin,
              AtomicBoolean settled, UpstreamTimer.Span span, UpstreamForwarder.Recorder recorder,
              AtomicReference<UpstreamStream.Handle> handle, ChannelFutureListener clientGone) {
            this.config = config;
            this.healthTracker = healthTracker;
            this.ledger = ledger;
            this.clientCtx = clientCtx;
            this.provider = provider;
            this.walletAddress = walletAddress;
            this.callCostAicoin = callCostAicoin;
            this.settled = settled;
            this.span = span;
            this.recorder = recorder;
            this.handle = handle;
            this.clientGone = clientGone;
        }

        private boolean isSuccess() {
            int code = head.status().code();
            return code >= 200 && code < 300;
        }

        @Override
        public void onHead(HttpResponse head) {
            this.head = head;
            healthTracker.record(provider, head.status().code());
            if (!isSuccess()) {
                errorBody = new ByteArrayOutputStream();
            }
        }

        @Override
        public void onData(ByteBuf chunk) {
            if (errorBody != null) {
                int room = MAX_ERROR_BODY - errorBody.size();
                if (room > 0) {
                    byte[] bytes = new byte[Math.min(room, chunk.readableBytes())];
                    chunk.readBytes(bytes);
                    errorBody.writeBytes(bytes);
                }
                chunk.release();
                return;
            }
            if (!streaming) {
                if (!settled.compareAndSet(false, true)) {
                    // The client left first and has been refunded; nobody is reading this.
                    chunk.release();
                    cancel();
                    return;
                }
                startStream();
            }
            ChannelFuture write = clientCtx.writeAndFlush(new DefaultHttpContent(chunk));
            if (!clientCtx.channel().isWritable()) {
                // A client reading slower than the provider talks: stop reading upstream until this
                // chunk has gone out, instead of queueing the whole recording in memory.
                UpstreamStream.Handle h = handle.get();
                if (h != null) {
                    h.setReading(false);
                    write.addListener(f -> h.setReading(true));
                }
            }
        }

        /** The first byte of a 2xx body: the call is now paid for, and the head goes out. */
        private void startStream() {
            streaming = true;
            charged = 0;
            if (callCostAicoin > 0) {
                CostCalculator.Priced priced = costOf(config, provider);
                ledger.recordEvent(provider, priced.getCostUsd(),
                        priced.isTokensKnown() ? priced.getTokens() : -1, Instant.now(), walletAddress);
                charged = 1L;
                if (config.isMeteredBilling()) {
                    charged = CoinMeter.coinsFor(priced.getCostUsd(), config.getCoinValueUsd());
                    ledger.settleCall(walletAddress, charged - callCostAicoin, provider);
                }
                recorder.succeeded(head.status().code(), charged, priced.getCostUsd());
                ProviderCredit.get().record(provider, head.status().code(), new byte[0]);
            }
            AdminAlerts.get().upstreamResponse(provider, head.status().code(), new byte[0]);

            HttpResponse toClient = new DefaultHttpResponse(HttpVersion.HTTP_1_1, head.status());
            copyRelayable(head.headers(), toClient.headers());
            HttpUtil.setTransferEncodingChunked(toClient, true);
            if (callCostAicoin > 0) {
                toClient.headers().set("X-Aicoin-Charged", Long.toString(charged));
            }
            clientCtx.write(toClient);
        }

        @Override
        public void onEnd() {
            finish();
            if (errorBody != null) {
                relayError();
            } else if (streaming) {
                clientCtx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            } else if (settled.compareAndSet(false, true)) {
                // A 2xx with nothing in it is no speech at all.
                UpstreamForwarder.refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
                recorder.failed(502, "upstream sent an empty stream");
                UpstreamForwarder.sendSynthetic(clientCtx, HttpResponseStatus.BAD_GATEWAY, "upstream sent an empty stream");
            }
        }

        private void relayError() {
            byte[] bytes = errorBody.toByteArray();
            int code = head.status().code();
            AdminAlerts.get().upstreamResponse(provider, code, bytes);
            if (callCostAicoin > 0) {
                ProviderCredit.get().record(provider, code, bytes);
            }
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            FullHttpResponse toClient = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    UpstreamForwarder.statusForClient(head.status()), Unpooled.wrappedBuffer(bytes));
            copyRelayable(head.headers(), toClient.headers());
            if (code != toClient.status().code()) {
                toClient.headers().set("X-Upstream-Status", Integer.toString(code));
            }
            HttpUtil.setContentLength(toClient, bytes.length);
            clientCtx.writeAndFlush(toClient);
            UpstreamForwarder.refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
            if (callCostAicoin > 0) {
                recorder.failed(code, Transactions.upstreamError(code, bytes));
            }
        }

        @Override
        public void onFailure(String error, boolean timedOut) {
            finish();
            if (streaming) {
                // The status went out with the first chunk and cannot be taken back. Resetting the
                // connection, without the terminating chunk, is how a client learns the body is
                // incomplete; writing the terminator would pass half a recording off as all of it.
                LOG.log(Level.INFO, provider + " stream broke after it started: " + error);
                UpstreamStream.abortClient(clientCtx);
                return;
            }
            if (settled.compareAndSet(false, true)) {
                UpstreamForwarder.refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
                recorder.failed(timedOut ? 504 : 502, error);
                UpstreamForwarder.sendSynthetic(clientCtx,
                        timedOut ? HttpResponseStatus.GATEWAY_TIMEOUT : HttpResponseStatus.BAD_GATEWAY, error);
            }
        }

        /** The call is over: stop timing it and stop listening for the client leaving. */
        private void finish() {
            span.end();
            clientCtx.channel().closeFuture().removeListener(clientGone);
        }

        private void cancel() {
            UpstreamStream.Handle h = handle.get();
            if (h != null) {
                h.cancel();
            }
        }

        /** The provider's headers minus credentials ({@link UpstreamForwarder}) and this hop's framing. */
        private void copyRelayable(HttpHeaders from, HttpHeaders to) {
            HttpHeaders filtered = new DefaultHttpHeaders();
            UpstreamForwarder.UpstreamResponseHandler.copyHeaders(from, filtered, config.getProvider(provider));
            for (Map.Entry<String, String> h : filtered) {
                if (!UpstreamStream.isHopByHop(h.getKey())) {
                    to.add(h.getKey(), h.getValue());
                }
            }
        }
    }
}
