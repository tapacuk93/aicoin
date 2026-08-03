package com.aicoin.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.util.logging.Logger;

/**
 * Entry point: builds a raw Netty ServerBootstrap on the configured port.
 * No web framework — see CONTRACT.md's aicoin-proxy section.
 */
public final class ProxyMain {

    private static final Logger LOG = Logger.getLogger(ProxyMain.class.getName());

    private ProxyMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        ProxyConfig config = ProxyConfig.load();
        ProviderHealthTracker healthTracker = new ProviderHealthTracker(config.getHealthWindowSize());

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ProxyServerInitializer(config, workerGroup, healthTracker))
                    .childOption(ChannelOption.AUTO_READ, true)
                    .option(ChannelOption.SO_BACKLOG, 1024);

            Channel channel = bootstrap.bind(config.getPort()).sync().channel();
            LOG.info("aicoin-proxy listening on port " + config.getPort());
            channel.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
