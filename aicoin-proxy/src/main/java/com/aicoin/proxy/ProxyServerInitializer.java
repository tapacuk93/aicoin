package com.aicoin.proxy;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

/**
 * Inbound pipeline per CONTRACT.md's "Forwarding" step 1:
 * HttpServerCodec + HttpObjectAggregator + access logging + routing/forwarding handler.
 */
public class ProxyServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final int MAX_CONTENT_LENGTH = 32 * 1024 * 1024;

    private final ProxyConfig config;
    private final EventLoopGroup clientGroup;
    private final ProviderHealthTracker healthTracker;
    private final AicoinLedger ledger;
    private final AccessLog accessLog;

    public ProxyServerInitializer(ProxyConfig config, EventLoopGroup clientGroup, ProviderHealthTracker healthTracker,
                                   AicoinLedger ledger, AccessLog accessLog) {
        this.config = config;
        this.clientGroup = clientGroup;
        this.healthTracker = healthTracker;
        this.ledger = ledger;
        this.accessLog = accessLog;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast(new HttpServerCodec());
        ch.pipeline().addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
        // Before the routing handler so it sees every request, and — being a duplex handler —
        // every response on its way back out, whichever code path produced it.
        ch.pipeline().addLast(new AccessLogHandler(accessLog));
        ch.pipeline().addLast(new ProxyFrontendHandler(config, clientGroup, healthTracker, ledger));
    }
}
