package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * How a liveness probe's outcome becomes a state, per {@link ProviderLiveness}: the mapping is the
 * whole judgement call in there, and it is the difference between a status page that is right and
 * one that cries wolf.
 */
class ProviderLivenessTest {

    @Test
    void aSuccessfulListingIsAlive() {
        ProviderLiveness.Probe probe = ProviderLiveness.fromStatus(200, 42);
        assertTrue(probe.isAlive());
        assertEquals("alive", probe.getState());
        assertEquals(42, probe.getLatencyMs());
        assertTrue(probe.getCheckedAtMillis() > 0);
    }

    @Test
    void rateLimitingIsAliveBecauseTheProviderIsAnswering() {
        // 429 means the provider is up and busy. Painting it as down would tell a user the backend
        // is gone when it will serve the next call a moment later.
        ProviderLiveness.Probe probe = ProviderLiveness.fromStatus(429, 10);
        assertTrue(probe.isAlive());
        assertEquals("rate limited", probe.getDetail());
    }

    @Test
    void aRejectedKeyIsDownHoweverWellTheHostIsRunning() {
        for (int status : new int[] {401, 403}) {
            ProviderLiveness.Probe probe = ProviderLiveness.fromStatus(status, 5);
            assertFalse(probe.isAlive(), status + " should be down");
            assertEquals("down", probe.getState());
            assertTrue(probe.getDetail().contains("key rejected"));
        }
    }

    @Test
    void outOfCreditIsDown() {
        ProviderLiveness.Probe probe = ProviderLiveness.fromStatus(402, 5);
        assertFalse(probe.isAlive());
        assertEquals("out of credit (402)", probe.getDetail());
    }

    @Test
    void providerErrorsAreDown() {
        assertFalse(ProviderLiveness.fromStatus(500, 5).isAlive());
        assertFalse(ProviderLiveness.fromStatus(503, 5).isAlive());
    }

    @Test
    void anUnexpectedClientStatusIsStillAnAnswer() {
        // A 404 means this proxy is probing the wrong path — its own bug. The provider answered,
        // which is what was asked, so it is not reported as an outage.
        ProviderLiveness.Probe probe = ProviderLiveness.fromStatus(404, 7);
        assertTrue(probe.isAlive());
        assertEquals("responded 404", probe.getDetail());
    }

    @Test
    void everyProviderHasAFreeProbePathConfigured() {
        ProxyConfig config = ProxyConfig.load(new HashMap<>());
        for (String provider : ProxyConfig.PROVIDER_NAMES) {
            String path = config.getHealthProbePath(provider);
            assertFalse(path.isEmpty(), provider + " should have a probe path");
            // Probing runs forever on a timer, so the path must be one the provider does not bill
            // for — which is exactly what freePaths declares.
            assertTrue(FreeTargets.isFree("GET", path, config.getProvider(provider).getFreePaths()),
                    provider + " probe path " + path + " should be one of its freePaths");
        }
    }

    @Test
    void probingCanBeTurnedOffAndRetimed() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_HEALTH_PROBE_ENABLED", "false");
        env.put("AICOIN_PROXY_HEALTH_PROBE_INTERVAL_SECONDS", "300");
        env.put("AICOIN_PROXY_HEALTH_PROBE_TIMEOUT_SECONDS", "3");
        ProxyConfig config = ProxyConfig.load(env);
        assertFalse(config.isHealthProbeEnabled());
        assertEquals(300, config.getHealthProbeIntervalSeconds());
        assertEquals(3, config.getHealthProbeTimeoutSeconds());
    }

    @Test
    void aProviderWithNoKeyIsNeverProbed() {
        ProviderLiveness liveness = new ProviderLiveness(ProxyConfig.load(new HashMap<>()));
        liveness.start();
        assertEquals("unconfigured", liveness.probeFor("openai").getState());
        liveness.stop();
    }
}
