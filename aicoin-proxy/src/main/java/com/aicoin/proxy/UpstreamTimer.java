package com.aicoin.proxy;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * How long one client request spent waiting on upstream providers, so the access log can say how
 * much of its duration was this proxy's own doing.
 *
 * <p><b>Definition.</b> Upstream time is the wall-clock time during which <em>at least one</em>
 * upstream call for the request was in flight — the length of the union of the calls' intervals.
 * Sequential calls (a capability retrying on a second provider, a consortium's successive rounds)
 * therefore add up, and overlapping calls (a consortium panel asked in parallel) count once, from
 * the first of them leaving to the last of them answering. Summing overlapping calls instead would
 * make upstream time exceed the request's own duration and drive overhead negative; taking only
 * first-send to last-answer would charge the proxy's work between rounds to the providers. The
 * union is the one reading under which {@code overhead = duration - upstream} is exactly "time no
 * provider was being waited on".
 *
 * <p>A call's interval runs from just before connecting (connect and TLS handshake are network
 * time to the provider, not work this proxy can do faster) to the moment its response, failure or
 * timeout is known, before any of the proxy's own processing of it.
 *
 * <p>One timer per request, hung on the client channel by {@link AccessLogHandler}. Upstream
 * responses arrive on other event loops than the client's, so everything here is synchronized.
 */
final class UpstreamTimer {

    static final AttributeKey<UpstreamTimer> KEY = AttributeKey.valueOf("aicoin.upstreamTimer");

    /** For calls with no client request to attribute them to (and tests): records nothing. */
    static final UpstreamTimer NONE = new UpstreamTimer(null);

    private final LongSupplier clock;
    private int inFlight;
    private long busySince;
    private long totalNanos;

    UpstreamTimer() {
        this(System::nanoTime);
    }

    UpstreamTimer(LongSupplier clock) {
        this.clock = clock;
    }

    /** The timer for the request currently being served on {@code channel}, or {@link #NONE}. */
    static UpstreamTimer of(Channel channel) {
        if (channel == null) {
            return NONE;
        }
        UpstreamTimer timer = channel.attr(KEY).get();
        return timer == null ? NONE : timer;
    }

    /** Installs a fresh timer for the request just read on {@code channel}. */
    static UpstreamTimer startRequest(Channel channel) {
        UpstreamTimer timer = new UpstreamTimer();
        Attribute<UpstreamTimer> attr = channel.attr(KEY);
        attr.set(timer);
        return timer;
    }

    /** Marks one upstream call as having started. End the returned span exactly when it is answered. */
    Span begin() {
        if (clock == null) {
            return Span.NOOP;
        }
        synchronized (this) {
            if (inFlight++ == 0) {
                busySince = clock.getAsLong();
            }
        }
        return new Span(this);
    }

    private synchronized void finish() {
        if (inFlight > 0 && --inFlight == 0) {
            totalNanos += clock.getAsLong() - busySince;
        }
    }

    /** Upstream time so far, in milliseconds — including any call still in flight up to now. */
    synchronized long upstreamMillis() {
        if (clock == null) {
            return 0;
        }
        long nanos = totalNanos;
        if (inFlight > 0) {
            nanos += clock.getAsLong() - busySince;
        }
        return nanos / 1_000_000L;
    }

    /** One upstream call. {@link #end} is idempotent, so every terminal path may call it. */
    static class Span {
        static final Span NOOP = new Span(null);

        private final UpstreamTimer timer;
        private final AtomicBoolean ended = new AtomicBoolean(false);

        private Span(UpstreamTimer timer) {
            this.timer = timer;
        }

        void end() {
            if (timer != null && ended.compareAndSet(false, true)) {
                timer.finish();
            }
        }
    }
}
