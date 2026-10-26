package com.aicoin.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One connection waiting on a consortium call: streamed (NDJSON, one event per line) or plain
 * (one JSON body at the end). A call has one of these per client attached to it — the one that
 * started it, and any that came back for it by {@code call_id} — so a call is no longer the same
 * thing as the connection that asked for it.
 *
 * <p>Every write is handed to the channel's event loop rather than written directly, and callers
 * do that under the call's lock: a write made on the loop thread would go out at once while one
 * made elsewhere is queued, so two turns finishing on different threads could otherwise reach the
 * wire in the opposite order to the one they happened in.
 *
 * <p>A client only ever receives events with a {@code seq} above the last one it was sent, so
 * replaying a stored call and then joining it live cannot show an event twice.
 */
final class ConsortiumClient {

    /** Seconds of silence after which a streamed client is sent {@code {"event":"ping"}}. */
    static final int PING_SECONDS = 15;

    final ChannelHandlerContext ctx;
    final boolean stream;
    private int lastSeq;
    private boolean begun;
    private boolean ended;
    private long lastWriteNanos;
    private ScheduledFuture<?> pinger;

    ConsortiumClient(ChannelHandlerContext ctx, boolean stream) {
        this.ctx = ctx;
        this.stream = stream;
    }

    boolean isOpen() {
        return !ended && ctx.channel().isActive();
    }

    /**
     * Commits a streamed client to {@code 200} and starts its keep-alive. Nothing for a plain one,
     * whose status is not known until the end. From here every failure is an {@code error} event
     * rather than a status, which is why the refusals a caller acts on are decided before this.
     */
    synchronized void begin() {
        if (!stream || begun) {
            return;
        }
        begun = true;
        HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        head.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-ndjson; charset=utf-8");
        // Nothing between here and the phone may hold lines back to fill a buffer: the point of
        // the stream is that each one arrives when it happens. Chunked, so a proxy in front
        // (Caddy) treats it as a stream and flushes each write; no-cache and no-transform so
        // nothing caches or recompresses it; X-Accel-Buffering for any nginx-style hop.
        HttpUtil.setTransferEncodingChunked(head, true);
        head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-transform");
        head.headers().set("X-Accel-Buffering", "no");
        head.headers().set("Trailer", "X-Aicoin-Charged");
        lastWriteNanos = System.nanoTime();
        ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(head));
        // A turn can run for minutes with nothing to say. An idle connection is exactly what
        // Caddy, an HTTP/3 hop or the phone's own networking eventually drops, so silence is
        // filled with a ping every fifteen seconds.
        pinger = ctx.channel().eventLoop().scheduleAtFixedRate(() -> {
            synchronized (this) {
                if (!ended && System.nanoTime() - lastWriteNanos >= TimeUnit.SECONDS.toNanos(PING_SECONDS)) {
                    write("{\"event\":\"ping\"}");
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** One event line, if this client has not been sent it yet. */
    synchronized void event(int seq, String line) {
        if (!stream || ended || seq <= lastSeq) {
            return;
        }
        lastSeq = seq;
        write(line);
    }

    private void write(String line) {
        lastWriteNanos = System.nanoTime();
        byte[] bytes = (line + "\n").getBytes(CharsetUtil.UTF_8);
        ctx.channel().eventLoop().execute(
                () -> ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(bytes))));
    }

    /**
     * The call's outcome. A streamed client gets the last line (unless it already has it) and the
     * end of the response, with what the call cost as a trailer — the total is not known when the
     * headers go out. A plain client gets the body a call without streaming always got.
     */
    synchronized void end(int status, String body, int seq, String line, long coinsCharged) {
        if (ended) {
            return;
        }
        if (stream) {
            begin();
            if (seq > lastSeq) {
                lastSeq = seq;
            } else {
                line = null;
            }
            ended = true;
            if (pinger != null) {
                pinger.cancel(false);
            }
            LastHttpContent last = line == null ? new DefaultLastHttpContent()
                    : new DefaultLastHttpContent(Unpooled.wrappedBuffer((line + "\n").getBytes(CharsetUtil.UTF_8)));
            last.trailingHeaders().set("X-Aicoin-Charged", Long.toString(coinsCharged));
            ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(last));
            return;
        }
        ended = true;
        byte[] bytes = body.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        // The same header a single proxied call sets, for the same reason: a client can show
        // what the call cost without being told how the proxy bills.
        response.headers().set("X-Aicoin-Charged", Long.toString(coinsCharged));
        HttpUtil.setContentLength(response, bytes.length);
        ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(response));
    }

    /** Stops the keep-alive of a client that went away. */
    synchronized void closed() {
        ended = true;
        if (pinger != null) {
            pinger.cancel(false);
        }
    }
}
