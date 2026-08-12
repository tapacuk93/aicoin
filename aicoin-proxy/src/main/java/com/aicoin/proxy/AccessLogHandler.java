package com.aicoin.proxy;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import java.util.Optional;

/**
 * Sits at the head of the inbound pipeline and times every request, writing one {@link AccessLog}
 * line when the response goes out.
 *
 * <p>Placed here rather than at each place that sends a response because there are many of those —
 * auth rejections, the balance gate, synthetic upstream errors, the wallet page, the real proxied
 * response — and an access log that silently misses whichever one a future change adds is worse
 * than none. Everything written to the client passes through this one point.
 *
 * <p>It also logs the case that has no response at all: a client that gave up and disconnected
 * while the proxy was still waiting on an upstream. That request is invisible everywhere else —
 * no status code is ever produced — and it is exactly the shape of "the request timed out" reports
 * from clients, so it is recorded with {@code status:-1} and {@code outcome:"client_gone"}.
 */
final class AccessLogHandler extends ChannelDuplexHandler {

    private static final String X_AI_HEADER = "X-AI";
    private static final String X_API_KEY_HEADER = "X-Api-Key";
    private static final String X_CHARGED_HEADER = "X-Aicoin-Charged";

    private final AccessLog accessLog;

    /** In-flight request state. HTTP/1.1 on one connection is answered in order, so one slot is enough. */
    private String method = "";
    private String path = "";
    private String provider = "";
    private String wallet = "";
    private int requestBytes;
    private long startNanos;
    private boolean pending;

    AccessLogHandler(AccessLog accessLog) {
        this.accessLog = accessLog;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;
            method = request.method().name();
            // Query string deliberately dropped — see AccessLog's note on never writing credentials.
            String uri = request.uri();
            int q = uri.indexOf('?');
            path = q >= 0 ? uri.substring(0, q) : uri;
            String providerHeader = request.headers().get(X_AI_HEADER);
            provider = ProviderRouting.resolve(providerHeader).orElse(providerHeader == null ? "" : "unknown");
            // The address is read out of the token without verifying it: this is for attribution in
            // a log, not authorization, and logging the claimed address of a request that then
            // failed verification is precisely what makes an auth failure diagnosable.
            wallet = WalletValidation.extractWalletId(request.headers().get(X_API_KEY_HEADER))
                    .flatMap(WalletSignature::peekTokenAddress)
                    .orElse("");
            requestBytes = request.content().readableBytes();
            startNanos = System.nanoTime();
            pending = true;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (pending && msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;
            String coins = Optional.ofNullable(response.headers().get(X_CHARGED_HEADER)).orElse("");
            log(response.status().code(), response.content().readableBytes(), coins, "ok");
        }
        ctx.write(msg, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Still pending means the connection dropped before anything was written back — the
        // client timed out, or went away. Without this the request leaves no trace at all.
        if (pending) {
            log(-1, 0, "", "client_gone");
        }
        ctx.fireChannelInactive();
    }

    private void log(int status, int responseBytes, String coins, String outcome) {
        pending = false;
        if (accessLog == null) {
            return;
        }
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        accessLog.record(method, path, provider, wallet, status, requestBytes, responseBytes,
                durationMillis, coins, outcome);
    }
}
