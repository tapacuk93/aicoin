package com.aicoin.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
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
import io.netty.util.CharsetUtil;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fire-and-forget POST of a cost event to aicoin.eventsUrl, per
 * CONTRACT.md's "Forwarding" step 4. Must never block or fail the
 * client-facing response; all errors are logged and swallowed.
 */
final class EventPublisher {

    private static final Logger LOG = Logger.getLogger(EventPublisher.class.getName());

    private EventPublisher() {
    }

    static void publish(EventLoopGroup group, String eventsUrl, String userId, String provider, double costUsd) {
        try {
            URI uri = new URI(eventsUrl);
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            boolean tls = "https".equalsIgnoreCase(scheme);
            String host = uri.getHost();
            int port = uri.getPort() != -1 ? uri.getPort() : (tls ? 443 : 80);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) {
                path = path + "?" + uri.getRawQuery();
            }

            String json = "{\"user_id\":" + jsonString(userId)
                    + ",\"provider\":" + jsonString(provider)
                    + ",\"cost_usd\":" + costUsd + "}";
            byte[] body = json.getBytes(CharsetUtil.UTF_8);

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            if (tls) {
                                SslContext sslCtx = SslContextBuilder.forClient().build();
                                ch.pipeline().addLast(sslCtx.newHandler(ch.alloc(), host, port));
                            }
                            ch.pipeline().addLast(new HttpClientCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                                @Override
                                protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, FullHttpResponse msg) {
                                    ctx.close();
                                }

                                @Override
                                public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
                                    LOG.log(Level.FINE, "aicoin events POST response handling failed", cause);
                                    ctx.close();
                                }
                            });
                        }
                    });

            String finalPath = path;
            bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    LOG.log(Level.WARNING, "aicoin events POST connect failed: " + eventsUrl, future.cause());
                    return;
                }
                Channel ch = future.channel();
                ByteBuf content = Unpooled.wrappedBuffer(body);
                FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, finalPath, content);
                req.headers().set(HttpHeaderNames.HOST, port == (tls ? 443 : 80) ? host : host + ":" + port);
                req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                HttpUtil.setContentLength(req, content.readableBytes());
                req.headers().set(HttpHeaderNames.CONNECTION, "close");
                ch.writeAndFlush(req).addListener((ChannelFutureListener) writeFuture -> {
                    if (!writeFuture.isSuccess()) {
                        LOG.log(Level.WARNING, "aicoin events POST write failed: " + eventsUrl, writeFuture.cause());
                        ch.close();
                    }
                });
            });
        } catch (Exception e) {
            // Never let event publishing affect the client-facing response.
            LOG.log(Level.WARNING, "aicoin events POST failed for url " + eventsUrl, e);
        }
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
