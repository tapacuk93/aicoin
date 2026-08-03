package com.aicoin.proxy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe per-provider rolling window of the last {@code
 * health.windowSize} forwarded-call upstream HTTP status codes, per
 * CONTRACT.md's "Additional proxy-side endpoints" {@code GET /health}
 * section:
 *
 * <ul>
 *   <li>{@code rateLimited} = true if any status in the window was 429</li>
 *   <li>{@code overBudget} = true if any status in the window was 402 or 403</li>
 *   <li>{@code healthy} = {@code !rateLimited && !overBudget}</li>
 * </ul>
 *
 * A provider with no calls recorded yet has no window at all and reports
 * the all-clear default ({@code healthy:true, rateLimited:false,
 * overBudget:false}) via {@link #healthFor}.
 *
 * One instance is shared across all requests for the process lifetime;
 * {@link #record} is called once per forwarded call, regardless of
 * whether the upstream status was 2xx or not.
 */
public final class ProviderHealthTracker {

    private final int windowSize;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public ProviderHealthTracker(int windowSize) {
        this.windowSize = Math.max(1, windowSize);
    }

    /** Records the upstream HTTP status code of one forwarded call to the given provider. */
    public void record(String provider, int statusCode) {
        windows.computeIfAbsent(provider, p -> new Window(windowSize)).record(statusCode);
    }

    /** @return the current health snapshot for the given provider; the all-clear default if it has no recorded calls yet. */
    public Health healthFor(String provider) {
        Window w = windows.get(provider);
        return w != null ? w.health() : Health.ALL_CLEAR;
    }

    /** Immutable healthy/rateLimited/overBudget snapshot for one provider. */
    public static final class Health {
        static final Health ALL_CLEAR = new Health(true, false, false);

        private final boolean healthy;
        private final boolean rateLimited;
        private final boolean overBudget;

        Health(boolean healthy, boolean rateLimited, boolean overBudget) {
            this.healthy = healthy;
            this.rateLimited = rateLimited;
            this.overBudget = overBudget;
        }

        public boolean isHealthy() {
            return healthy;
        }

        public boolean isRateLimited() {
            return rateLimited;
        }

        public boolean isOverBudget() {
            return overBudget;
        }
    }

    /** Fixed-size ring buffer of the most recent status codes for one provider, guarded by its own lock. */
    private static final class Window {
        private final int[] statusCodes;
        private int nextIndex = 0;
        private int filled = 0;
        private final ReentrantLock lock = new ReentrantLock();

        Window(int windowSize) {
            this.statusCodes = new int[windowSize];
        }

        void record(int statusCode) {
            lock.lock();
            try {
                statusCodes[nextIndex] = statusCode;
                nextIndex = (nextIndex + 1) % statusCodes.length;
                filled = Math.min(filled + 1, statusCodes.length);
            } finally {
                lock.unlock();
            }
        }

        Health health() {
            lock.lock();
            try {
                boolean rateLimited = false;
                boolean overBudget = false;
                for (int i = 0; i < filled; i++) {
                    int code = statusCodes[i];
                    if (code == 429) {
                        rateLimited = true;
                    } else if (code == 402 || code == 403) {
                        overBudget = true;
                    }
                }
                return new Health(!rateLimited && !overBudget, rateLimited, overBudget);
            } finally {
                lock.unlock();
            }
        }
    }
}
