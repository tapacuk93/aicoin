package com.aicoin.proxy;

import io.netty.bootstrap.Bootstrap;
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
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Performs the actual async Netty HTTP GET to {@code
 * {balanceUrlBase}/balance/{walletId}}, per CONTRACT.md's "Auth — wallet id
 * IS the API key" section. No subprocess — a plain outbound {@link
 * Bootstrap} exactly like {@link PriceForwarder}/{@link UpstreamForwarder}.
 *
 * The decision of what a given outcome (success/failure/timeout) *means*
 * lives in {@link WalletValidation#isReachable}; this class is only
 * responsible for driving the network call and reporting exactly one
 * outcome — true (proceed with forwarding) or false (respond {@code 503})
 * — to {@code onResult}, exactly once.
 */
final class WalletValidator {

    private static final Logger LOG = Logger.getLogger(WalletValidator.class.getName());
    private static final int MAX_CONTENT_LENGTH = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_SECONDS = 5;

    private WalletValidator() {
    }

    static void validate(EventLoopGroup group, String balanceUrlBase, String walletId, Consumer<Boolean> onResult) {
        AtomicBoolean delivered = new AtomicBoolean(false);
        Consumer<Boolean> deliverOnce = reachable -> {
            if (delivered.compareAndSet(false, true)) {
                onResult.accept(reachable);
            }
        };

        URI uri;
        try {
            uri = new URI(WalletValidation.balanceUrl(balanceUrlBase, walletId));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "invalid aicoin.balanceUrlBase: " + balanceUrlBase, e);
            deliverOnce.accept(false);
            return;
        }

        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        if (host == null) {
            deliverOnce.accept(false);
            return;
        }
        int port = uri.getPort() != -1 ? uri.getPort() : (tls ? 443 : 80);
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        String finalPath = path;

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        if (tls) {
                            SslContext sslCtx = SslContextBuilder.forClient().build();
                            ch.pipeline().addLast(sslCtx.newHandler(ch.alloc(), host, port));
                        }
                        ch.pipeline().addLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS));
                        ch.pipeline().addLast(new HttpClientCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
                                deliverOnce.accept(WalletValidation.isReachable(Optional.of(response.status().code())));
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                LOG.log(Level.WARNING, "wallet balance-check failed for " + balanceUrlBase, cause);
                                deliverOnce.accept(false);
                                ctx.close();
                            }
                        });
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                LOG.log(Level.WARNING, "wallet balance-check connect failed: " + balanceUrlBase, future.cause());
                deliverOnce.accept(false);
                return;
            }
            Channel ch = future.channel();
            FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, finalPath);
            req.headers().set(HttpHeaderNames.HOST, port == (tls ? 443 : 80) ? host : host + ":" + port);
            HttpUtil.setContentLength(req, 0);
            ch.writeAndFlush(req).addListener((ChannelFutureListener) writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    LOG.log(Level.WARNING, "wallet balance-check write failed: " + balanceUrlBase, writeFuture.cause());
                    deliverOnce.accept(false);
                    ch.close();
                }
            });
        });
    }
}
