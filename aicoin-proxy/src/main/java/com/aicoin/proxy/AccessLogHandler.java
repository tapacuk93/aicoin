package com.aicoin.proxy;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
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
 *
 * <p>Each request also gets an {@link UpstreamTimer} on the channel, which the code that calls
 * providers charges with the time it spent waiting on them. The line then carries
 * {@code upstream_ms} and {@code overhead_ms} (duration minus upstream, never below zero) — the
 * latency this proxy itself added — and the same overhead goes back to the client as
 * {@code X-Aicoin-Overhead-Ms}. A request that never touched a provider (wallet, IAP, health) has
 * {@code upstream_ms:0}, so all of its duration is overhead.
 */
final class AccessLogHandler extends ChannelDuplexHandler {

    private static final String X_AI_HEADER = "X-AI";
    private static final String X_API_KEY_HEADER = "X-Api-Key";
    private static final String X_CHARGED_HEADER = "X-Aicoin-Charged";
    static final String X_OVERHEAD_HEADER = "X-Aicoin-Overhead-Ms";

    private final AccessLog accessLog;

    /** In-flight request state. HTTP/1.1 on one connection is answered in order, so one slot is enough. */
    private String method = "";
    private String path = "";
    private String provider = "";
    private String wallet = "";
    private int requestBytes;
    private long startNanos;
    private UpstreamTimer upstreamTimer = UpstreamTimer.NONE;
    private boolean pending;
    /**
     * A streamed response in flight ({@code POST /consortium} with {@code stream: true}): its
     * status went out with the headers, its bytes are counted as the chunks pass, and the line
     * is written when the last chunk does — that is when the call is over and its cost (sent as
     * an {@code X-Aicoin-Charged} trailer, since it was not known when the headers left) exists.
     */
    private int streamStatus = -1;
    private int streamBytes;

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
            upstreamTimer = UpstreamTimer.startRequest(ctx.channel());
            pending = true;
            streamStatus = -1;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (pending && msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;
            String coins = Optional.ofNullable(response.headers().get(X_CHARGED_HEADER)).orElse("");
            long[] timing = timing();
            response.headers().set(X_OVERHEAD_HEADER, Long.toString(timing[2]));
            log(response.status().code(), response.content().readableBytes(), coins, "ok", timing);
        } else if (pending && msg instanceof HttpResponse) {
            streamStatus = ((HttpResponse) msg).status().code();
            streamBytes = 0;
        } else if (pending && streamStatus >= 0 && msg instanceof HttpContent) {
            streamBytes += ((HttpContent) msg).content().readableBytes();
            if (msg instanceof LastHttpContent) {
                String coins = Optional.ofNullable(((LastHttpContent) msg).trailingHeaders().get(X_CHARGED_HEADER))
                        .orElse("");
                int status = streamStatus;
                streamStatus = -1;
                log(status, streamBytes, coins, "ok", timing());
            }
        }
        ctx.write(msg, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Still pending means the connection dropped before anything was written back — the
        // client timed out, or went away. Without this the request leaves no trace at all.
        if (pending) {
            log(-1, 0, "", "client_gone", timing());
        }
        ctx.fireChannelInactive();
    }

    /** {duration, upstream, overhead} in milliseconds for the request in flight, as of now. */
    private long[] timing() {
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        long upstreamMillis = Math.min(upstreamTimer.upstreamMillis(), durationMillis);
        return new long[] {durationMillis, upstreamMillis, Math.max(0, durationMillis - upstreamMillis)};
    }

    private void log(int status, int responseBytes, String coins, String outcome, long[] timing) {
        pending = false;
        AdminAlerts.get().clientResponse(status);
        if (accessLog == null) {
            return;
        }
        accessLog.record(method, path, provider, wallet, status, requestBytes, responseBytes,
                timing[0], timing[1], timing[2], coins, outcome);
    }
}
