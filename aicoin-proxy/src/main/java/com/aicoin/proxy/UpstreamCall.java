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
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.CharsetUtil;
import java.net.URI;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One outbound request to a provider that this proxy <em>originates</em>, rather than forwards.
 *
 * <p>{@link UpstreamForwarder} exists to relay a client's own request and hand back the provider's
 * exact bytes; every path through it ends in a write to the client socket and a decision about
 * that client's debit. A consortium turn has neither: the answer is consumed here, several turns
 * make up one client response, and the billing decision belongs to the round that asked for it.
 * So this class does the connect/inject/write/read half and hands the result to a callback.
 *
 * <p>The callback is invoked exactly once, whatever happens — an HTTP response of any status, a
 * connect failure, a read timeout. A consortium round counts its outstanding calls, so a turn that
 * silently never came back would hang the whole request.
 */
final class UpstreamCall {

    private static final Logger LOG = Logger.getLogger(UpstreamCall.class.getName());
    /**
     * Chat responses are text; nothing here is speech or images. Well under {@link
     * UpstreamForwarder}'s 32MB, because a consortium holds several of these in memory at once on
     * a host with a 512MB heap.
     */
    private static final int MAX_CONTENT_LENGTH = 4 * 1024 * 1024;

    private UpstreamCall() {
    }

    /** What a single originated call came back with: an HTTP response, or a reason there wasn't one. */
    static final class Result {
        private final int status;
        private final byte[] body;
        private final String error;

        private Result(int status, byte[] body, String error) {
            this.status = status;
            this.body = body;
            this.error = error;
        }

        static Result of(int status, byte[] body) {
            return new Result(status, body, null);
        }

        static Result failed(String error) {
            return new Result(0, new byte[0], error);
        }

        /** True when the provider answered 2xx — the only case whose body is worth reading. */
        boolean isOk() {
            return error == null && status >= 200 && status < 300;
        }

        /** True when there was a real HTTP response at all, whatever its status. */
        boolean hasResponse() {
            return error == null;
        }

        int getStatus() {
            return status;
        }

        String bodyText() {
            return new String(body, CharsetUtil.UTF_8);
        }

        /** Why no response arrived, or — for a non-2xx — a short description of the status. */
        String getError() {
            if (error != null) {
                return error;
            }
            return "upstream returned HTTP " + status;
        }
    }

    /**
     * POSTs {@code body} to {@code path} on {@code provider}'s configured baseUrl with the proxy's
     * own key injected, and reports the outcome once. Records the response — of any status — in the
     * provider's health window, exactly as a forwarded call would.
     */
    static void post(EventLoopGroup group, ProxyConfig config, ProviderHealthTracker healthTracker,
                      String provider, String path, List<Map.Entry<String, String>> headers, byte[] body,
                      Consumer<Result> onResult) {
        ProviderConfig providerConfig = config.getProvider(provider);
        if (providerConfig == null) {
            onResult.accept(Result.failed("unknown provider " + provider));
            return;
        }

        URI upstreamUri;
        try {
            upstreamUri = new URI(providerConfig.getBaseUrl());
        } catch (Exception e) {
            onResult.accept(Result.failed("invalid provider baseUrl"));
            return;
        }
        boolean tls = "https".equalsIgnoreCase(upstreamUri.getScheme());
        String host = upstreamUri.getHost();
        int port = upstreamUri.getPort() != -1 ? upstreamUri.getPort() : (tls ? 443 : 80);
        if (host == null) {
            onResult.accept(Result.failed("invalid provider baseUrl"));
            return;
        }

        AuthInjector.Injection injection = AuthInjector.compute(providerConfig);
        List<Map.Entry<String, String>> outHeaders = new ArrayList<>(headers);
        String uri = path;
        if (injection.isQueryParam()) {
            uri = AuthInjector.appendQueryParam(uri, injection.getName(), injection.getValue());
        } else {
            outHeaders.add(new AbstractMap.SimpleEntry<>(injection.getName(), injection.getValue()));
        }
        String finalUri = uri;

        // Exactly one of: a response, a failure, a timeout. Whichever gets here first owns the
        // single callback the caller is counting on.
        AtomicBoolean settled = new AtomicBoolean(false);
        Consumer<Result> once = result -> {
            if (settled.compareAndSet(false, true)) {
                onResult.accept(result);
            }
        };

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
                        ch.pipeline().addLast(new ReadTimeoutHandler(config.getUpstreamReadTimeoutSeconds()));
                        ch.pipeline().addLast(new HttpClientCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                        ch.pipeline().addLast(new ResponseHandler(provider, healthTracker, once));
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                LOG.log(Level.WARNING, "consortium connect failed for " + provider, future.cause());
                once.accept(Result.failed("upstream connection failed"));
                return;
            }
            Channel ch = future.channel();
            String hostHeader = (port == (tls ? 443 : 80)) ? host : host + ":" + port;
            FullHttpRequest req = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, finalUri, Unpooled.wrappedBuffer(body));
            for (Map.Entry<String, String> h : outHeaders) {
                req.headers().add(h.getKey(), h.getValue());
            }
            req.headers().set(HttpHeaderNames.HOST, hostHeader);
            HttpUtil.setContentLength(req, body.length);
            ch.writeAndFlush(req).addListener((ChannelFutureListener) writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    LOG.log(Level.WARNING, "consortium write failed for " + provider, writeFuture.cause());
                    once.accept(Result.failed("upstream write failed"));
                    ch.close();
                }
            });
        });
    }

    private static final class ResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final String provider;
        private final ProviderHealthTracker healthTracker;
        private final Consumer<Result> once;

        ResponseHandler(String provider, ProviderHealthTracker healthTracker, Consumer<Result> once) {
            this.provider = provider;
            this.healthTracker = healthTracker;
            this.once = once;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
            healthTracker.record(provider, response.status().code());
            once.accept(Result.of(response.status().code(), ByteBufUtil.getBytes(response.content())));
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            boolean timedOut = cause instanceof ReadTimeoutException;
            if (timedOut) {
                LOG.log(Level.INFO, "consortium call to " + provider + " timed out");
            } else {
                LOG.log(Level.WARNING, "consortium call to " + provider + " failed", cause);
            }
            once.accept(Result.failed(timedOut ? "upstream timed out" : "upstream error"));
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // A provider that closed without answering must still complete the call, or the round
            // that is counting outstanding turns never finishes.
            once.accept(Result.failed("upstream closed the connection"));
        }
    }
}
