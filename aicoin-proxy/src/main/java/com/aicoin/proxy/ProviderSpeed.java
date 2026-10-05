package com.aicoin.proxy;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long each provider takes to answer, per capability.
 *
 * <p>{@link ProviderSkills} says how good a provider is at something and {@link ProviderLiveness}
 * says whether it is there at all. Neither says how long it makes the caller wait, and waiting is
 * most of what using one of these feels like. A model that is marginally better and four times
 * slower is the wrong choice for an editor asking about the line under the cursor and the right
 * one for a question worth waiting on. The duration of every forwarded call has been written to
 * the access log all along; nothing read it back, so it could not be part of the decision.
 *
 * <p><b>Median, not mean.</b> Upstreams stall. One call that took ninety seconds because a
 * provider was quietly rate-limiting should not move its standing much, and against a mean it
 * moves it a great deal. The median of the recent window answers the question actually being
 * asked, which is what the next call will probably cost.
 *
 * <p><b>Only answers count.</b> A call is recorded when the provider returned 2xx. A provider that
 * refuses in fifty milliseconds is not fast, and letting failures in would rank it first exactly
 * when it is broken - the same trap {@link ProviderHealthTracker} exists to catch from the other
 * side.
 *
 * <p><b>Silence until there is evidence.</b> Below {@link #MIN_SAMPLES} answers this has no
 * opinion rather than a confident one drawn from a single call. A provider nobody has used yet is
 * not slow, it is unmeasured, and those two must not look alike to the router.
 *
 * <p>One instance is shared for the process lifetime, and nothing here is persisted: this is a
 * measurement of this deployment's network as it is now, and a week-old median taken from a
 * different network is not evidence about this one. The durable record is the access log, which
 * carries every observation this was built from.
 */
public final class ProviderSpeed {

    /** Answers needed before this will say anything about a provider. */
    static final int MIN_SAMPLES = 3;

    /**
     * The most a provider can lose for being slow, in rating points. Under one, on purpose: see
     * {@link #penalty}.
     */
    static final double MAX_PENALTY = 0.9;

    private final int windowSize;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public ProviderSpeed(int windowSize) {
        this.windowSize = Math.max(MIN_SAMPLES, windowSize);
    }

    /** Record that {@code provider} answered a {@code capability} call in {@code millis}. */
    public void record(String provider, Capability capability, int statusCode, long millis) {
        if (provider == null || capability == null) {
            return;
        }
        if (statusCode / 100 != 2 || millis < 0) {
            return;
        }
        windows.computeIfAbsent(key(provider, capability), k -> new Window(windowSize))
                .record(millis);
    }

    /**
     * @return the median time this provider has been taking for this capability, or empty if it
     * has not answered often enough to say.
     */
    public OptionalDouble medianMillis(String provider, Capability capability) {
        Window window = windows.get(key(provider, capability));
        return window == null ? OptionalDouble.empty() : window.median();
    }

    /**
     * What the router should subtract from a provider's rating for being slow: zero for the
     * quickest provider under consideration, rising towards {@link #MAX_PENALTY} for the slowest.
     *
     * <p>Bounded below one whole rating point, deliberately. Speed reorders providers the ratings
     * already call roughly equal; it never lets a provider rated 3 for code overtake one rated 5
     * by being quick, because a fast wrong answer is not a cheaper right one. Something here has
     * to be arbitrary, and a bound is the honest place to put it: the alternative is a weighting
     * that looks principled and is the same guess with more arithmetic in front of it.
     *
     * <p>The scale is relative to the quickest provider being considered rather than to a fixed
     * number of seconds, because "slow" has no absolute meaning for a model call. On a good day
     * every provider answers in two seconds and on a bad one every provider takes thirty, and in
     * both cases the useful question is which of them is quickest now.
     *
     * <p>Twice as slow costs half the maximum and four times costs three quarters, so each further
     * doubling matters less than the one before it. That is how waiting is actually experienced,
     * and it keeps one bad afternoon from burying a provider.
     */
    public double penalty(String provider, Capability capability, List<String> among) {
        OptionalDouble mine = medianMillis(provider, capability);
        if (mine.isEmpty()) {
            return 0.0;
        }
        double best = Double.MAX_VALUE;
        for (String other : among) {
            OptionalDouble theirs = medianMillis(other, capability);
            if (theirs.isPresent() && theirs.getAsDouble() < best) {
                best = theirs.getAsDouble();
            }
        }
        if (best == Double.MAX_VALUE || best <= 0) {
            return 0.0;
        }
        double ratio = mine.getAsDouble() / best;
        if (ratio <= 1.0) {
            return 0.0;
        }
        return MAX_PENALTY * (1.0 - 1.0 / ratio);
    }

    /**
     * The medians this has, for {@code GET /skills} - so a routing decision can be audited rather
     * than guessed at. Providers with too few answers are absent rather than zero.
     */
    public Map<String, Long> observed(Capability capability) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String provider : ProxyConfig.PROVIDER_NAMES) {
            OptionalDouble median = medianMillis(provider, capability);
            if (median.isPresent()) {
                out.put(provider, Math.round(median.getAsDouble()));
            }
        }
        return out;
    }

    private static String key(String provider, Capability capability) {
        return provider + " " + capability.path();
    }

    /** A fixed-size ring of the most recent answers, oldest overwritten. */
    private static final class Window {
        private final long[] samples;
        private int next;
        private int filled;

        Window(int size) {
            this.samples = new long[size];
        }

        synchronized void record(long millis) {
            samples[next] = millis;
            next = (next + 1) % samples.length;
            if (filled < samples.length) {
                filled++;
            }
        }

        synchronized OptionalDouble median() {
            if (filled < MIN_SAMPLES) {
                return OptionalDouble.empty();
            }
            long[] sorted = Arrays.copyOf(samples, filled);
            Arrays.sort(sorted);
            int mid = sorted.length / 2;
            return OptionalDouble.of(sorted.length % 2 == 1
                    ? sorted[mid]
                    : (sorted[mid - 1] + sorted[mid]) / 2.0);
        }
    }
}
