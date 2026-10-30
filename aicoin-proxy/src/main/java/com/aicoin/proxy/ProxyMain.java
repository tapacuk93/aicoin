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
        // Asks each provider directly whether it is there, on a timer, so /health can answer for a
        // provider nobody has called — see ProviderLiveness.
        // How long each provider has been taking to answer, so the router can prefer the quicker
        // of two providers it rates the same - see ProviderSpeed.
        ProviderSpeed speed = new ProviderSpeed(config.getHealthWindowSize());
        ProviderLiveness liveness = new ProviderLiveness(config);
        liveness.start();
        AicoinLedger ledger = new AicoinLedger(config.getRedisHost(), config.getRedisPort(),
                config.getRedisUsername(), config.getRedisPassword(), config.isRedisSsl());
        // Null when accessLog.path is empty, or when the file couldn't be opened — an unwritable
        // log is reported and then ignored, never a reason to refuse to serve traffic.
        AccessLog accessLog = AccessLog.create(config);
        // Push notifications to the admin app — see AdminAlerts. Without an APNs key they are
        // still recorded for GET /admin/alerts.
        // One APNs sender for both: its key is a team key, good for every app the team ships, so
        // the admin app's alerts and the book app's "Chapter ready" go out through the same one.
        Apns apns = Apns.fromEnv(System.getenv());
        AdminAlerts alerts = new AdminAlerts(ledger.redis(), ledger, apns, System.getenv());
        PushDevices.install(new PushDevices(new PushDevices.Redis(ledger.redis()),
                (device, payload) -> apns.send(device.token(), device.sandbox(), device.topic(), payload), accessLog));
        AdminAlerts.install(alerts);
        // Hosted Anyphone — off unless anyphone.enabled. The meter keeps running instances billed
        // and reaps any no record owns, so it starts with the process, not with the first request.
        HostedPhones phones = null;
        if (config.getAnyphone().isEnabled()) {
            AnyphoneConfig anyphone = config.getAnyphone();
            phones = new HostedPhones(anyphone, new PhoneStore.Redis(ledger.redis()), Ec2PhoneCloud.create(anyphone),
                    HostedPhones.pushNotifier(anyphone.getPushTopic()), System::currentTimeMillis,
                    () -> PhoneUserData.template(anyphone));
            HostedPhones.install(phones);
            phones.startMeter();
            LOG.info("hosted Anyphone enabled in " + anyphone.getRegion());
        }
        alerts.startMonitor();
        alerts.proxyStarted();

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ProxyServerInitializer(config, workerGroup, healthTracker, speed, liveness, ledger, accessLog))
                    .childOption(ChannelOption.AUTO_READ, true)
                    .option(ChannelOption.SO_BACKLOG, 1024);

            Channel channel = bootstrap.bind(config.getPort()).sync().channel();
            LOG.info("aicoin-proxy listening on port " + config.getPort());
            // Durable consortium calls (CONTRACT.md "Durable calls"): resumes any a previous
            // process left running, and on shutdown gives up this one's leases so the next
            // process picks them up within seconds instead of waiting the leases out.
            ConsortiumCalls calls = new ConsortiumCalls(new ConsortiumStore.Redis(ledger.redis(),
                    config.getConsortium().getCallRetentionHours(), config.getConsortium().getCallLeaseSeconds()),
                    config, workerGroup, healthTracker, ledger);
            ConsortiumCalls.install(calls);
            calls.start();
            Runtime.getRuntime().addShutdownHook(new Thread(calls::stop, "consortium-calls-release"));
            channel.closeFuture().sync();
        } finally {
            if (phones != null) {
                phones.stop();
            }
            liveness.stop();
            alerts.stop();
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            ledger.close();
            if (accessLog != null) {
                accessLog.close();
            }
        }
    }
}
