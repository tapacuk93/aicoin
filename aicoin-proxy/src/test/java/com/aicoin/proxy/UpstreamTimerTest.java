package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Upstream time is the union of the calls' intervals: sequential calls add, overlapping calls
 * count once. Overhead is whatever is left of the request's duration.
 */
class UpstreamTimerTest {

    private static final long MS = 1_000_000L;

    @Test
    void sequentialCallsAddUp() {
        AtomicLong now = new AtomicLong();
        UpstreamTimer timer = new UpstreamTimer(now::get);

        UpstreamTimer.Span first = timer.begin();
        now.addAndGet(100 * MS);
        first.end();
        now.addAndGet(50 * MS); // the proxy's own work between a failure and its retry
        UpstreamTimer.Span retry = timer.begin();
        now.addAndGet(200 * MS);
        retry.end();

        assertEquals(300, timer.upstreamMillis());
    }

    @Test
    void overlappingPanelCallsCountOnceFromFirstSendToLastAnswer() {
        AtomicLong now = new AtomicLong();
        UpstreamTimer timer = new UpstreamTimer(now::get);

        UpstreamTimer.Span a = timer.begin();
        now.addAndGet(10 * MS);
        UpstreamTimer.Span b = timer.begin();
        UpstreamTimer.Span c = timer.begin();
        now.addAndGet(500 * MS);
        b.end();
        now.addAndGet(300 * MS);
        a.end();
        now.addAndGet(190 * MS);
        c.end();

        assertEquals(1000, timer.upstreamMillis(),
                "three overlapping calls spanning 1s are 1s of waiting, not the 2.5s their sum would be");
    }

    @Test
    void endingASpanTwiceIsHarmless() {
        AtomicLong now = new AtomicLong();
        UpstreamTimer timer = new UpstreamTimer(now::get);

        UpstreamTimer.Span a = timer.begin();
        UpstreamTimer.Span b = timer.begin();
        now.addAndGet(100 * MS);
        a.end();
        a.end(); // e.g. both the response handler and the close listener
        now.addAndGet(100 * MS);
        assertEquals(200, timer.upstreamMillis(), "b is still in flight, so time keeps counting");
        b.end();
        now.addAndGet(100 * MS);
        assertEquals(200, timer.upstreamMillis());
    }

    @Test
    void aCallStillInFlightCountsUpToNow() {
        AtomicLong now = new AtomicLong();
        UpstreamTimer timer = new UpstreamTimer(now::get);
        timer.begin();
        now.addAndGet(70 * MS);
        assertEquals(70, timer.upstreamMillis());
    }

    @Test
    void noneRecordsNothing() {
        UpstreamTimer.NONE.begin().end();
        assertEquals(0, UpstreamTimer.NONE.upstreamMillis());
        assertEquals(UpstreamTimer.NONE, UpstreamTimer.of(new EmbeddedChannel()));
    }

    /** A request that never calls a provider: all of its time is overhead, and the header says so. */
    @Test
    void handlerSetsOverheadHeaderAndChargesUpstreamTimeToTheProvider() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(new AccessLogHandler(null), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                UpstreamTimer.Span span = UpstreamTimer.of(ctx.channel()).begin();
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                span.end();
                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
            }
        });
        ch.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/text"));
        FullHttpResponse response = ch.readOutbound();

        long overhead = Long.parseLong(response.headers().get(AccessLogHandler.X_OVERHEAD_HEADER));
        assertTrue(overhead >= 0 && overhead < 50,
                "the 60ms spent 'upstream' must not be counted as overhead, got " + overhead);
        assertTrue(UpstreamTimer.of(ch).upstreamMillis() >= 60);
    }
}
