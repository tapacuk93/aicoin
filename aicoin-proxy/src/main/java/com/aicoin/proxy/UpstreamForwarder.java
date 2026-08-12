package com.aicoin.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.CharsetUtil;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opens a fresh outbound Netty client {@link Bootstrap} for a single request
 * to a provider's baseUrl, forwards method/path+query/headers/body unchanged,
 * and relays the upstream's exact response back to the original client.
 *
 * The caller must have already atomically debited the call's aicoin cost
 * from the wallet ({@link AicoinLedger#debitForCall}) before invoking {@link
 * #forward} — this class's job on any path that *doesn't* end in a genuine
 * 2xx upstream response is to {@link AicoinLedger#refund} that debit, since
 * the proxy was never actually billed by the real provider for a call that
 * never completed successfully. On success with a 2xx status, the debit
 * stands and an event is recorded into {@link AicoinLedger} (fire-and-forget,
 * in-process) for the price formula — without blocking or affecting the
 * client response. Connection failures are surfaced to the client as a
 * synthetic 502 (there is no real upstream status to relay in that case —
 * see README.md for this assumption).
 *
 * A {@code callCostAicoin} of 0 means the caller identified a {@link
 * FreeTargets free target} and deliberately skipped the debit: such a call is
 * relayed exactly like any other, but neither branch above applies — there is
 * no debit to refund on failure, and no provider bill to feed the price
 * formula on success.
 *
 * Every real upstream response — 2xx or not — is also recorded into that
 * provider's {@link ProviderHealthTracker} rolling window, feeding {@code
 * GET /health} (see CONTRACT.md's "Additional proxy-side endpoints"
 * section). Connection/write failures, having no real upstream status, are
 * not recorded.
 */
final class UpstreamForwarder {

    private static final Logger LOG = Logger.getLogger(UpstreamForwarder.class.getName());
    private static final int MAX_CONTENT_LENGTH = 32 * 1024 * 1024;

    private UpstreamForwarder() {
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
        URI upstreamUri;
        try {
            upstreamUri = new URI(baseUrl);
        } catch (Exception e) {
            refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
            sendSynthetic(clientCtx, HttpResponseStatus.BAD_GATEWAY, "invalid provider baseUrl");
            return;
        }

        boolean tls = "https".equalsIgnoreCase(upstreamUri.getScheme());
        String host = upstreamUri.getHost();
        int port = upstreamUri.getPort() != -1 ? upstreamUri.getPort() : (tls ? 443 : 80);
        if (host == null) {
            refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
            sendSynthetic(clientCtx, HttpResponseStatus.BAD_GATEWAY, "invalid provider baseUrl");
            return;
        }

        // Exactly one of: the upstream answered, the upstream timed out/failed, or the client gave
        // up first. Whichever happens first owns the debit — the other paths must not also refund
        // it (a double refund credits coins that were never spent) and must not write a second
        // response. Shared across the response handler, the read-timeout path, and the
        // client-disconnect listener below, all of which can fire on different event loops.
        AtomicBoolean settled = new AtomicBoolean(false);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        if (tls) {
                            SslContext sslCtx = SslContextBuilder.forClient().build();
                            ch.pipeline().addLast(sslCtx.newHandler(ch.alloc(), host, port));
                        }
                        // The only bound on a call once the socket is up. CONNECT_TIMEOUT_MILLIS
                        // above covers establishing the connection and nothing past it, so a
                        // provider that accepted and then stalled used to be waited on forever:
                        // the client's own timeout fired, the client walked away, and this side
                        // kept the socket, the aggregation buffer and an unrefunded debit alive
                        // with nobody left to deliver to. See
                        // ProxyConfig#getUpstreamReadTimeoutSeconds for the duration.
                        ch.pipeline().addLast(new ReadTimeoutHandler(config.getUpstreamReadTimeoutSeconds()));
                        ch.pipeline().addLast(new HttpClientCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                        ch.pipeline().addLast(new UpstreamResponseHandler(
                                config, healthTracker, ledger, clientCtx, provider, walletAddress, callCostAicoin,
                                settled));
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                LOG.log(Level.WARNING, "upstream connect failed for provider " + provider + " at " + baseUrl, future.cause());
                if (settled.compareAndSet(false, true)) {
                    refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
                    sendSynthetic(clientCtx, HttpResponseStatus.BAD_GATEWAY, "upstream connection failed");
                }
                return;
            }
            Channel upstreamCh = future.channel();

            // A client that gave up is a call nobody can receive, and continuing it spends real
            // money on an answer that will be written to a closed socket. Without this, a client
            // whose own timeout fired first (mobile clients all have one) still paid: the debit is
            // taken before forwarding, the upstream 2xx arrives later, and settlement runs against
            // a wallet whose owner never got the bytes. The retry that client then makes pays
            // again, and again, for as long as this side stays slower than its timeout.
            //
            // AccessLogHandler already records this case as status -1 / "client_gone"; that only
            // ever noted it happened. This ends the upstream call and returns the coins.
            ChannelFutureListener clientGone = f -> {
                if (settled.compareAndSet(false, true)) {
                    LOG.log(Level.FINE, "client gone before upstream answered; aborting " + provider + " call");
                    refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
                }
                upstreamCh.close();
            };
            clientCtx.channel().closeFuture().addListener(clientGone);
            // Dropped again the moment this call is over, because the client channel outlives it:
            // clients keep the connection alive across requests, so a listener left registered
            // stays for the life of the connection, pinning this call's upstream channel and
            // stacking one more listener per request until the client finally disconnects. The
            // upstream channel closes on every terminal path here — response relayed, read
            // timeout, error, or this listener itself — so it is the reliable place to unhook.
            upstreamCh.closeFuture().addListener(f -> clientCtx.channel().closeFuture().removeListener(clientGone));
            String hostHeader = (port == (tls ? 443 : 80)) ? host : host + ":" + port;
            FullHttpRequest req = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, method, forwardUri, Unpooled.wrappedBuffer(body));
            for (Map.Entry<String, String> h : headers) {
                req.headers().add(h.getKey(), h.getValue());
            }
            req.headers().set(HttpHeaderNames.HOST, hostHeader);
            HttpUtil.setContentLength(req, body.length);

            upstreamCh.writeAndFlush(req).addListener((ChannelFutureListener) writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    LOG.log(Level.WARNING, "upstream write failed for provider " + provider, writeFuture.cause());
                    if (settled.compareAndSet(false, true)) {
                        refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
                        sendSynthetic(clientCtx, HttpResponseStatus.BAD_GATEWAY, "upstream write failed");
                    }
                    upstreamCh.close();
                }
            });
        });
    }

    /**
     * Reverses the pre-forward debit — unless there wasn't one. {@code callCostAicoin} is 0 for a
     * {@link FreeTargets free target}: nothing was debited, so nothing may be refunded (a 0-amount
     * refund would still append a bogus {@code refund} entry to the wallet's transaction log).
     */
    /**
     * The response body as text for {@link CostCalculator} — decompressed first when the upstream
     * sent it compressed.
     *
     * <p>Without this, every real call was priced at {@code defaultCostUsdPerCall}. Clients send
     * {@code Accept-Encoding: gzip} by default (URLSession does, so every call from the apps did),
     * the provider honours it, and this proxy forwards the bytes untouched — so what reached the
     * cost calculator was gzip, no {@code usage} object could be parsed out of it, and the flat
     * default was recorded every single time. Measured against production before the fix: an
     * Anthropic call of 16 tokens recorded $0.001000 with gzip and $0.000032 — 16 x the per-token
     * rate, i.e. the correct figure — with {@code Accept-Encoding: identity}. Roughly a 30x
     * over-estimate on a tiny call, and `GET /price` is what the App Store price ladder is derived
     * from, so it mattered well beyond the dashboard.
     *
     * <p>Only the copy used for pricing is decoded; the client still receives the original bytes
     * and headers exactly as the provider sent them. Failing to decode falls back to the raw bytes,
     * which is what the old behaviour was — a wrong price is better than a dropped response.
     */
    static String decodedForPricing(HttpHeaders headers, byte[] bodyBytes) {
        String encoding = headers.get(HttpHeaderNames.CONTENT_ENCODING);
        if (encoding == null || bodyBytes.length == 0) {
            return new String(bodyBytes, CharsetUtil.UTF_8);
        }
        String normalized = encoding.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (normalized.contains("gzip")) {
                try (java.util.zip.GZIPInputStream in =
                             new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bodyBytes))) {
                    return new String(in.readAllBytes(), CharsetUtil.UTF_8);
                }
            }
            if (normalized.contains("deflate")) {
                try (java.util.zip.InflaterInputStream in =
                             new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(bodyBytes))) {
                    return new String(in.readAllBytes(), CharsetUtil.UTF_8);
                }
            }
        } catch (java.io.IOException e) {
            LOG.log(Level.FINE, "could not decode " + normalized + " body for pricing", e);
        }
        return new String(bodyBytes, CharsetUtil.UTF_8);
    }

    private static void refundIfBilled(AicoinLedger ledger, String walletAddress, double callCostAicoin, String provider) {
        if (callCostAicoin > 0) {
            ledger.refund(walletAddress, callCostAicoin, provider);
        }
    }

    private static void sendSynthetic(ChannelHandlerContext clientCtx, HttpResponseStatus status, String message) {
        String json = "{\"error\":\"" + message + "\"}";
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        clientCtx.writeAndFlush(response);
    }

    /**
     * Relays the upstream response back to the client; on 2xx keeps the debit and fires the
     * price-formula event, otherwise refunds it. Both are skipped when {@code callCostAicoin} is 0
     * (a free target — nothing was debited and the provider bills nothing).
     */
    private static final class UpstreamResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final ProxyConfig config;
        private final ProviderHealthTracker healthTracker;
        private final AicoinLedger ledger;
        private final ChannelHandlerContext clientCtx;
        private final String provider;
        private final String walletAddress;
        private final double callCostAicoin;
        private final AtomicBoolean settled;

        UpstreamResponseHandler(ProxyConfig config, ProviderHealthTracker healthTracker, AicoinLedger ledger,
                                 ChannelHandlerContext clientCtx, String provider, String walletAddress, double callCostAicoin,
                                 AtomicBoolean settled) {
            this.config = config;
            this.healthTracker = healthTracker;
            this.ledger = ledger;
            this.clientCtx = clientCtx;
            this.provider = provider;
            this.walletAddress = walletAddress;
            this.callCostAicoin = callCostAicoin;
            this.settled = settled;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext upstreamCtx, FullHttpResponse response) {
            HttpResponseStatus status = response.status();

            // The client's own timeout beat this answer, and the disconnect listener has already
            // refunded. Record it for provider health — the upstream really did respond, and that
            // is worth knowing — but bill nothing and write nothing to a socket that is gone.
            if (!settled.compareAndSet(false, true)) {
                healthTracker.record(provider, status.code());
                upstreamCtx.close();
                return;
            }

            byte[] bodyBytes = ByteBufUtil.getBytes(response.content());

            healthTracker.record(provider, status.code());

            FullHttpResponse toClient = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bodyBytes));
            copyHeaders(response.headers(), toClient.headers());

            if (status.code() >= 200 && status.code() < 300) {
                // A free target's upstream cost really is zero, so it must not feed the price
                // formula — recording defaultCostUsdPerCall for a model listing would inflate
                // GET /price with spend the proxy was never billed for.
                if (callCostAicoin > 0) {
                    double costUsd = costUsdFor(response.headers(), bodyBytes);
                    ledger.recordEvent(provider, costUsd, Instant.now());

                    // Metering settles AFTER the upstream answers, because that answer is the only
                    // place the call's real cost exists. The gate up front still holds exactly one
                    // coin, so "one coin is enough to make a call" stays true and an empty wallet
                    // is still refused before any provider is touched; this takes the remainder.
                    long charged = 1L;
                    if (config.isMeteredBilling()) {
                        charged = CoinMeter.coinsFor(costUsd, config.getCoinValueUsd());
                        ledger.settleCall(walletAddress, charged - callCostAicoin, provider);
                    }
                    // Told to the client either way, so a caller can show what a call cost and can
                    // tell metered from flat billing without being configured for it.
                    toClient.headers().set("X-Aicoin-Charged", Long.toString(charged));
                }
                clientCtx.writeAndFlush(toClient);
            } else {
                clientCtx.writeAndFlush(toClient);
                refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
            }

            upstreamCtx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext upstreamCtx, Throwable cause) {
            boolean timedOut = cause instanceof ReadTimeoutException;
            if (timedOut) {
                // Distinct from a transport error: the provider is reachable and simply took
                // longer than any client is still waiting for. Logged at a lower level because on
                // a slow provider this is expected traffic, not a fault in this proxy.
                LOG.log(Level.INFO, "upstream read timed out for provider " + provider
                        + " after " + config.getUpstreamReadTimeoutSeconds() + "s");
            } else {
                LOG.log(Level.WARNING, "upstream response handling failed for provider " + provider, cause);
            }
            if (settled.compareAndSet(false, true)) {
                refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
                sendSynthetic(clientCtx,
                        timedOut ? HttpResponseStatus.GATEWAY_TIMEOUT : HttpResponseStatus.BAD_GATEWAY,
                        timedOut ? "upstream timed out" : "upstream error");
            }
            upstreamCtx.close();
        }

        /**
         * What this call cost the proxy upstream — without reading the body at all when the
         * provider is priced per call.
         *
         * <p>ElevenLabs and Stability report no token usage and are configured with a flat
         * {@code usdPerCall}, so parsing their bodies could only ever end at that same figure. It
         * was not free to find that out: {@link #decodedForPricing} gunzips the body and builds a
         * Java String from it (UTF-16, so twice the bytes again), then {@link CostCalculator} runs
         * a full YAML/JSON parse over the result. For a speech response — base64 audio plus
         * per-character alignment arrays, routinely megabytes — that is several times the response
         * size in short-lived allocation, plus the parse, per call, on a host with a 512MB budget
         * shared with Redis. Three concurrent narration requests made it the largest allocator in
         * the process, to reach a constant.
         */
        private double costUsdFor(HttpHeaders headers, byte[] bodyBytes) {
            Double perCall = config.getModelPricing().perCallUsd(provider);
            if (perCall != null) {
                return perCall;
            }
            String bodyStr = decodedForPricing(headers, bodyBytes);
            return CostCalculator.computeCostUsd(provider, bodyStr, config.getModelPricing());
        }

        private static void copyHeaders(HttpHeaders from, HttpHeaders to) {
            for (Map.Entry<String, String> h : from) {
                to.add(h.getKey(), h.getValue());
            }
        }
    }
}
