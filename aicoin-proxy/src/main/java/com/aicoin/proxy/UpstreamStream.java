package com.aicoin.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
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
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One upstream HTTP call whose response is handed over <em>as it arrives</em> — the head, then each
 * body chunk, then the end — instead of whole, the way {@link UpstreamCall} and {@link
 * UpstreamForwarder} read theirs through an {@code HttpObjectAggregator}.
 *
 * <p>It exists for speech. A streamed text-to-speech answer is minutes of audio produced over many
 * seconds; read whole, a listener waits for the last second of it before hearing the first. Here
 * the first chunk can be on its way to the client a few hundred milliseconds after the provider
 * starts talking.
 *
 * <p>The read timeout is an <em>idle</em> timeout: {@link ReadTimeoutHandler} fires only when no
 * bytes at all arrived for that long, and every chunk resets it. So a stream may run for as long as
 * the provider keeps talking, and a provider that goes quiet mid-stream is still abandoned.
 *
 * <p>This class does no billing and writes nothing to any client; it only reports what the
 * provider did. Exactly one of {@link Listener#onEnd} and {@link Listener#onFailure} is called,
 * once, unless the call is {@link Handle#cancel cancelled} first — after which nothing more is.
 * Callbacks run on the upstream channel's event loop, in order.
 */
final class UpstreamStream {

    private static final Logger LOG = Logger.getLogger(UpstreamStream.class.getName());

    private UpstreamStream() {
    }

    /** What the provider did, in order. */
    interface Listener {
        /** The status line and headers, before any body. Called at most once. */
        void onHead(HttpResponse head);

        /**
         * One piece of the body as it came off the wire (decoded from chunked framing, never
         * empty). Ownership passes to the listener, which must release it or write it on.
         */
        void onData(ByteBuf chunk);

        /** The body is complete. Terminal. */
        void onEnd();

        /**
         * No complete answer: connect, write or read failed, the read went idle for longer than the
         * timeout, or the provider closed the connection before the body ended. May come before or
         * after {@link #onHead} and any number of {@link #onData}. Terminal.
         *
         * @param timedOut true for the idle timeout, which callers report as a 504 rather than a 502
         */
        void onFailure(String error, boolean timedOut);
    }

    /** A call in flight. */
    interface Handle {
        /** Drops the connection; no callback runs after this returns (one already running may finish). */
        void cancel();

        /**
         * Stops (false) or resumes (true) reading from the provider — back-pressure for a client
         * that reads slower than the provider talks. While stopped the idle timeout still runs.
         */
        void setReading(boolean reading);
    }

    static Handle open(EventLoopGroup group, int readTimeoutSeconds, String baseUrl, HttpMethod method,
                       String uri, List<Map.Entry<String, String>> headers, byte[] body, Listener listener) {
        Terminal terminal = new Terminal(listener);
        URI upstreamUri;
        try {
            upstreamUri = new URI(baseUrl);
        } catch (Exception e) {
            terminal.fail("invalid provider baseUrl", false);
            return terminal;
        }
        boolean tls = "https".equalsIgnoreCase(upstreamUri.getScheme());
        String host = upstreamUri.getHost();
        int port = upstreamUri.getPort() != -1 ? upstreamUri.getPort() : (tls ? 443 : 80);
        if (host == null) {
            terminal.fail("invalid provider baseUrl", false);
            return terminal;
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
                        ch.pipeline().addLast(new ReadTimeoutHandler(readTimeoutSeconds));
                        ch.pipeline().addLast(new HttpClientCodec());
                        // Deliberately no HttpObjectAggregator: that is the whole point of this class.
                        ch.pipeline().addLast(new StreamHandler(terminal, readTimeoutSeconds));
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                LOG.log(Level.WARNING, "streaming connect failed for " + baseUrl, future.cause());
                terminal.fail("upstream connection failed", false);
                return;
            }
            Channel ch = future.channel();
            terminal.channel.set(ch);
            if (terminal.done.get()) {
                ch.close();
                return;
            }
            String hostHeader = (port == (tls ? 443 : 80)) ? host : host + ":" + port;
            FullHttpRequest req = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, method, uri, Unpooled.wrappedBuffer(body));
            for (Map.Entry<String, String> h : headers) {
                req.headers().add(h.getKey(), h.getValue());
            }
            req.headers().set(HttpHeaderNames.HOST, hostHeader);
            HttpUtil.setContentLength(req, body.length);
            ch.writeAndFlush(req).addListener((ChannelFutureListener) writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    LOG.log(Level.WARNING, "streaming write failed for " + baseUrl, writeFuture.cause());
                    terminal.fail("upstream write failed", false);
                    ch.close();
                }
            });
        });
        return terminal;
    }

    /** Guards the listener: one terminal callback, nothing after cancel. */
    private static final class Terminal implements Handle {
        private final Listener listener;
        private final AtomicBoolean done = new AtomicBoolean(false);
        private final AtomicReference<Channel> channel = new AtomicReference<>();

        Terminal(Listener listener) {
            this.listener = listener;
        }

        void head(HttpResponse head) {
            if (!done.get()) {
                listener.onHead(head);
            }
        }

        void data(ByteBuf chunk) {
            if (done.get()) {
                chunk.release();
                return;
            }
            listener.onData(chunk);
        }

        void end() {
            if (done.compareAndSet(false, true)) {
                listener.onEnd();
            }
        }

        void fail(String error, boolean timedOut) {
            if (done.compareAndSet(false, true)) {
                listener.onFailure(error, timedOut);
            }
        }

        @Override
        public void cancel() {
            done.set(true);
            Channel ch = channel.get();
            if (ch != null) {
                ch.close();
            }
        }

        @Override
        public void setReading(boolean reading) {
            Channel ch = channel.get();
            if (ch != null) {
                ch.config().setAutoRead(reading);
                if (reading) {
                    ch.read();
                }
            }
        }
    }

    private static final class StreamHandler extends SimpleChannelInboundHandler<HttpObject> {
        private final Terminal terminal;
        private final int readTimeoutSeconds;
        private boolean ended;

        StreamHandler(Terminal terminal, int readTimeoutSeconds) {
            this.terminal = terminal;
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (msg.decoderResult().isFailure()) {
                terminal.fail("upstream sent a malformed response", false);
                ctx.close();
                return;
            }
            if (msg instanceof HttpResponse) {
                HttpResponse head = (HttpResponse) msg;
                // 100 Continue and other interim answers are not the response.
                if (head.status().code() / 100 == 1) {
                    return;
                }
                terminal.head(head);
            }
            if (msg instanceof HttpContent) {
                ByteBuf content = ((HttpContent) msg).content();
                if (content.isReadable()) {
                    terminal.data(content.retain());
                }
                if (msg instanceof LastHttpContent) {
                    ended = true;
                    terminal.end();
                    ctx.close();
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            boolean timedOut = cause instanceof ReadTimeoutException;
            if (timedOut) {
                LOG.log(Level.INFO, "upstream stream went quiet for " + readTimeoutSeconds + "s");
            } else {
                LOG.log(Level.WARNING, "upstream stream failed", cause);
            }
            terminal.fail(timedOut ? "upstream timed out" : "upstream error", timedOut);
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!ended) {
                terminal.fail("upstream closed the connection", false);
            }
            ctx.fireChannelInactive();
        }
    }

    /** Headers that describe one hop's framing and must not be copied onto another's. */
    static boolean isHopByHop(String name) {
        return name.equalsIgnoreCase(HttpHeaderNames.CONTENT_LENGTH.toString())
                || name.equalsIgnoreCase(HttpHeaderNames.TRANSFER_ENCODING.toString())
                || name.equalsIgnoreCase(HttpHeaderNames.CONNECTION.toString())
                || name.equalsIgnoreCase(HttpHeaderNames.KEEP_ALIVE.toString())
                || name.equalsIgnoreCase(HttpHeaderNames.TRAILER.toString())
                || name.equalsIgnoreCase(HttpHeaderNames.UPGRADE.toString());
    }
}
