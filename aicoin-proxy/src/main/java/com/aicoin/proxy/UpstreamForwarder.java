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
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opens a fresh outbound Netty client {@link Bootstrap} for a single request
 * to a provider's baseUrl, forwards method/path+query/headers/body unchanged,
 * and relays the upstream's exact response back to the original client.
 *
 * On success with a 2xx status, fires an async cost event POST (see
 * {@link EventPublisher}) without blocking or affecting the client response.
 * On connection failure or non-2xx status, no event is emitted; connection
 * failures are surfaced to the client as a synthetic 502 (there is no real
 * upstream status to relay in that case — see README.md for this assumption).
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
                         ChannelHandlerContext clientCtx,
                         HttpMethod method,
                         String forwardUri,
                         List<Map.Entry<String, String>> headers,
                         byte[] body,
                         String baseUrl,
                         String provider,
                         String userId) {
        URI upstreamUri;
        try {
            upstreamUri = new URI(baseUrl);
        } catch (Exception e) {
            sendSynthetic(clientCtx, HttpResponseStatus.BAD_GATEWAY, "invalid provider baseUrl");
            return;
        }

        boolean tls = "https".equalsIgnoreCase(upstreamUri.getScheme());
        String host = upstreamUri.getHost();
        int port = upstreamUri.getPort() != -1 ? upstreamUri.getPort() : (tls ? 443 : 80);
        if (host == null) {
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
                        ch.pipeline().addLast(new UpstreamResponseHandler(config, healthTracker, clientCtx, provider, userId));
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                LOG.log(Level.WARNING, "upstream connect failed for provider " + provider + " at " + baseUrl, future.cause());
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
                    sendSynthetic(clientCtx, HttpResponseStatus.BAD_GATEWAY, "upstream write failed");
                    upstreamCh.close();
                }
            });
        });
    }

    private static void sendSynthetic(ChannelHandlerContext clientCtx, HttpResponseStatus status, String message) {
        String json = "{\"error\":\"" + message + "\"}";
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        clientCtx.writeAndFlush(response);
    }

    /** Relays the upstream response back to the client and, on 2xx, fires the cost event. */
    private static final class UpstreamResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final ProxyConfig config;
        private final ProviderHealthTracker healthTracker;
        private final ChannelHandlerContext clientCtx;
        private final String provider;
        private final String userId;

        UpstreamResponseHandler(ProxyConfig config, ProviderHealthTracker healthTracker, ChannelHandlerContext clientCtx,
                                 String provider, String userId) {
            this.config = config;
            this.healthTracker = healthTracker;
            this.clientCtx = clientCtx;
            this.provider = provider;
            this.userId = userId;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext upstreamCtx, FullHttpResponse response) {
            byte[] bodyBytes = ByteBufUtil.getBytes(response.content());
            HttpResponseStatus status = response.status();

            healthTracker.record(provider, status.code());

            FullHttpResponse toClient = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bodyBytes));
            copyHeaders(response.headers(), toClient.headers());
            clientCtx.writeAndFlush(toClient);

            if (status.code() >= 200 && status.code() < 300) {
                String bodyStr = new String(bodyBytes, CharsetUtil.UTF_8);
                double costUsd = CostCalculator.computeCostUsd(
                        bodyStr, config.getCostPerTokenUsd(), config.getDefaultCostUsdPerCall());
                EventPublisher.publish(upstreamCtx.channel().eventLoop(), config.getEventsUrl(), userId, provider, costUsd);
            }

            upstreamCtx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext upstreamCtx, Throwable cause) {
            LOG.log(Level.WARNING, "upstream response handling failed for provider " + provider, cause);
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
