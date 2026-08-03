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
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.CharsetUtil;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the proxy-side {@code GET /price} endpoint: forwards to {@code
 * aicoin.priceUrl} and relays that JSON body verbatim to the client, per
 * CONTRACT.md's "Additional proxy-side endpoints" section. An unreachable
 * upstream yields {@code 502 {"error":"aicoin node unreachable"}}.
 */
final class PriceForwarder {

    private static final Logger LOG = Logger.getLogger(PriceForwarder.class.getName());
    private static final int MAX_CONTENT_LENGTH = 1024 * 1024;

    private PriceForwarder() {
    }

    static void forward(EventLoopGroup group, String priceUrl, ChannelHandlerContext clientCtx) {
        URI uri;
        try {
            uri = new URI(priceUrl);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "invalid aicoin.priceUrl: " + priceUrl, e);
            sendUnreachable(clientCtx);
            return;
        }

        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        if (host == null) {
            sendUnreachable(clientCtx);
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
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext upstreamCtx, FullHttpResponse response) {
                                byte[] bodyBytes = ByteBufUtil.getBytes(response.content());
                                FullHttpResponse toClient = new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1, response.status(), Unpooled.wrappedBuffer(bodyBytes));
                                toClient.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                                HttpUtil.setContentLength(toClient, bodyBytes.length);
                                clientCtx.writeAndFlush(toClient);
                                upstreamCtx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext upstreamCtx, Throwable cause) {
                                LOG.log(Level.WARNING, "aicoin /price upstream response handling failed", cause);
                                sendUnreachable(clientCtx);
                                upstreamCtx.close();
                            }
                        });
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                LOG.log(Level.WARNING, "aicoin /price connect failed: " + priceUrl, future.cause());
                sendUnreachable(clientCtx);
                return;
            }
            Channel ch = future.channel();
            FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, finalPath);
            req.headers().set(HttpHeaderNames.HOST, port == (tls ? 443 : 80) ? host : host + ":" + port);
            HttpUtil.setContentLength(req, 0);
            ch.writeAndFlush(req).addListener((ChannelFutureListener) writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    LOG.log(Level.WARNING, "aicoin /price write failed: " + priceUrl, writeFuture.cause());
                    sendUnreachable(clientCtx);
                    ch.close();
                }
            });
        });
    }

    private static void sendUnreachable(ChannelHandlerContext clientCtx) {
        byte[] bytes = "{\"error\":\"aicoin node unreachable\"}".getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        clientCtx.writeAndFlush(response);
    }
}
