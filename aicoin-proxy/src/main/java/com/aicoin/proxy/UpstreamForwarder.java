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
import io.netty.util.CharsetUtil;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                        ch.pipeline().addLast(new HttpClientCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                        ch.pipeline().addLast(new UpstreamResponseHandler(
                                config, healthTracker, ledger, clientCtx, provider, walletAddress, callCostAicoin));
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                LOG.log(Level.WARNING, "upstream connect failed for provider " + provider + " at " + baseUrl, future.cause());
                refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
                sendSynthetic(clientCtx, HttpResponseStatus.BAD_GATEWAY, "upstream connection failed");
                return;
            }
            Channel upstreamCh = future.channel();
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
                    refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
                    sendSynthetic(clientCtx, HttpResponseStatus.BAD_GATEWAY, "upstream write failed");
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

        UpstreamResponseHandler(ProxyConfig config, ProviderHealthTracker healthTracker, AicoinLedger ledger,
                                 ChannelHandlerContext clientCtx, String provider, String walletAddress, double callCostAicoin) {
            this.config = config;
            this.healthTracker = healthTracker;
            this.ledger = ledger;
            this.clientCtx = clientCtx;
            this.provider = provider;
            this.walletAddress = walletAddress;
            this.callCostAicoin = callCostAicoin;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext upstreamCtx, FullHttpResponse response) {
            byte[] bodyBytes = ByteBufUtil.getBytes(response.content());
            HttpResponseStatus status = response.status();

            healthTracker.record(provider, status.code());

            FullHttpResponse toClient = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bodyBytes));
            copyHeaders(response.headers(), toClient.headers());

            if (status.code() >= 200 && status.code() < 300) {
                // A free target's upstream cost really is zero, so it must not feed the price
                // formula — recording defaultCostUsdPerCall for a model listing would inflate
                // GET /price with spend the proxy was never billed for.
                if (callCostAicoin > 0) {
                    String bodyStr = decodedForPricing(response.headers(), bodyBytes);
                    double costUsd = CostCalculator.computeCostUsd(provider, bodyStr, config.getModelPricing());
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
            LOG.log(Level.WARNING, "upstream response handling failed for provider " + provider, cause);
            refundIfBilled(ledger, walletAddress, callCostAicoin, provider);
            sendSynthetic(clientCtx, HttpResponseStatus.BAD_GATEWAY, "upstream error");
            upstreamCtx.close();
        }

        private static void copyHeaders(HttpHeaders from, HttpHeaders to) {
            for (Map.Entry<String, String> h : from) {
                to.add(h.getKey(), h.getValue());
            }
        }
    }
}
