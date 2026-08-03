package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Rolling-window health computation, per CONTRACT.md's "Additional
 * proxy-side endpoints" {@code GET /health} section: within the last
 * {@code health.windowSize} recorded calls for a provider, {@code
 * rateLimited} = true if any was 429, {@code overBudget} = true if any was
 * 402 or 403, {@code healthy} = {@code !rateLimited && !overBudget}. A
 * provider with zero recorded calls defaults to all-clear.
 */
class ProviderHealthTrackerTest {

    @Test
    void zeroTrafficProviderDefaultsToAllClear() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);
        ProviderHealthTracker.Health health = tracker.healthFor("openai");
        assertTrue(health.isHealthy());
        assertFalse(health.isRateLimited());
        assertFalse(health.isOverBudget());
    }

    @Test
    void allSuccessfulCallsAreHealthy() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);
        for (int i = 0; i < 10; i++) {
            tracker.record("openai", 200);
        }
        ProviderHealthTracker.Health health = tracker.healthFor("openai");
        assertTrue(health.isHealthy());
        assertFalse(health.isRateLimited());
        assertFalse(health.isOverBudget());
    }

    @Test
    void a429InWindowMarksRateLimitedAndUnhealthy() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);
        tracker.record("openai", 200);
        tracker.record("openai", 429);
        tracker.record("openai", 200);

        ProviderHealthTracker.Health health = tracker.healthFor("openai");
        assertTrue(health.isRateLimited());
        assertFalse(health.isOverBudget());
        assertFalse(health.isHealthy());
    }

    @Test
    void a402InWindowMarksOverBudgetAndUnhealthy() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);
        tracker.record("anthropic", 200);
        tracker.record("anthropic", 402);

        ProviderHealthTracker.Health health = tracker.healthFor("anthropic");
        assertFalse(health.isRateLimited());
        assertTrue(health.isOverBudget());
        assertFalse(health.isHealthy());
    }

    @Test
    void a403InWindowAlsoMarksOverBudget() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);
        tracker.record("cohere", 200);
        tracker.record("cohere", 403);

        ProviderHealthTracker.Health health = tracker.healthFor("cohere");
        assertTrue(health.isOverBudget());
        assertFalse(health.isHealthy());
    }

    @Test
    void bothRateLimitedAndOverBudgetCanBeTrueSimultaneously() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);
        tracker.record("mistral", 429);
        tracker.record("mistral", 403);

        ProviderHealthTracker.Health health = tracker.healthFor("mistral");
        assertTrue(health.isRateLimited());
        assertTrue(health.isOverBudget());
        assertFalse(health.isHealthy());
    }

    @Test
    void windowEvictsOldEntriesOnceMoreThanWindowSizeCallsRecorded() {
        // windowSize=3: record a 429 first, then push it out with three
        // healthy 200s. Once the 429 has scrolled out of the window, health
        // must recover.
        ProviderHealthTracker tracker = new ProviderHealthTracker(3);

        tracker.record("google", 429);
        ProviderHealthTracker.Health afterRateLimit = tracker.healthFor("google");
        assertTrue(afterRateLimit.isRateLimited());
        assertFalse(afterRateLimit.isHealthy());

        // Two more 200s: window is now [429, 200, 200] (still contains the 429).
        tracker.record("google", 200);
        tracker.record("google", 200);
        assertTrue(tracker.healthFor("google").isRateLimited());

        // A fourth 200 evicts the original 429 out of the 3-slot window.
        tracker.record("google", 200);
        ProviderHealthTracker.Health afterEviction = tracker.healthFor("google");
        assertFalse(afterEviction.isRateLimited());
        assertTrue(afterEviction.isHealthy());
    }

    @Test
    void windowEvictionAppliesIndependentlyToOverBudget() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(2);

        tracker.record("openai", 402);
        assertTrue(tracker.healthFor("openai").isOverBudget());

        tracker.record("openai", 200);
        // window is now [402, 200] - still contains the 402.
        assertTrue(tracker.healthFor("openai").isOverBudget());

        tracker.record("openai", 200);
        // window is now [200, 200] - the 402 has been evicted.
        ProviderHealthTracker.Health health = tracker.healthFor("openai");
        assertFalse(health.isOverBudget());
        assertTrue(health.isHealthy());
    }

    @Test
    void providersAreTrackedIndependently() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);
        tracker.record("openai", 429);
        tracker.record("anthropic", 200);

        assertTrue(tracker.healthFor("openai").isRateLimited());
        assertFalse(tracker.healthFor("anthropic").isRateLimited());
        assertTrue(tracker.healthFor("anthropic").isHealthy());
    }
}
