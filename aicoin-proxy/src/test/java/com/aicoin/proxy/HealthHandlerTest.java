package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code GET /health} response construction, per CONTRACT.md's "Additional
 * proxy-side endpoints" section: all 7 configured providers are always
 * listed, in a stable order, even ones with zero recorded calls (which
 * default to healthy:true/rateLimited:false/overBudget:false).
 */
class HealthHandlerTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return (Map<String, Object>) new Yaml().load(json);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> providersOf(String json) {
        return (List<Map<String, Object>>) parse(json).get("providers");
    }

    @Test
    void listsAllSevenProvidersEvenWithZeroTraffic() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);

        List<Map<String, Object>> providers = providersOf(HealthHandler.buildJson(tracker));

        assertEquals(7, providers.size());
        assertEquals(
                List.of("openai", "anthropic", "google", "mistral", "cohere", "elevenlabs", "stability"),
                providers.stream().map(p -> (String) p.get("name")).collect(java.util.stream.Collectors.toList()));

        for (Map<String, Object> p : providers) {
            assertEquals(Boolean.TRUE, p.get("healthy"), p.get("name") + " should default to healthy");
            assertEquals(Boolean.FALSE, p.get("rateLimited"), p.get("name") + " should default to not rate-limited");
            assertEquals(Boolean.FALSE, p.get("overBudget"), p.get("name") + " should default to not over-budget");
        }
    }

    @Test
    void reflectsRecordedRateLimitForOnlyThatProvider() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);
        tracker.record("openai", 429);

        List<Map<String, Object>> providers = providersOf(HealthHandler.buildJson(tracker));
        assertEquals(7, providers.size());

        Map<String, Object> openai = findByName(providers, "openai");
        assertEquals(Boolean.FALSE, openai.get("healthy"));
        assertEquals(Boolean.TRUE, openai.get("rateLimited"));
        assertEquals(Boolean.FALSE, openai.get("overBudget"));

        Map<String, Object> anthropic = findByName(providers, "anthropic");
        assertEquals(Boolean.TRUE, anthropic.get("healthy"));
        assertEquals(Boolean.FALSE, anthropic.get("rateLimited"));
    }

    @Test
    void reflectsRecordedOverBudgetStatus() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);
        tracker.record("cohere", 403);

        Map<String, Object> cohere = findByName(providersOf(HealthHandler.buildJson(tracker)), "cohere");
        assertFalse((Boolean) cohere.get("healthy"));
        assertTrue((Boolean) cohere.get("overBudget"));
        assertFalse((Boolean) cohere.get("rateLimited"));
    }

    @Test
    void reflectsRecordedStatusForElevenLabsAndStability() {
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);
        tracker.record("elevenlabs", 429);
        tracker.record("stability", 402);

        List<Map<String, Object>> providers = providersOf(HealthHandler.buildJson(tracker));

        Map<String, Object> elevenlabs = findByName(providers, "elevenlabs");
        assertFalse((Boolean) elevenlabs.get("healthy"));
        assertTrue((Boolean) elevenlabs.get("rateLimited"));
        assertFalse((Boolean) elevenlabs.get("overBudget"));

        Map<String, Object> stability = findByName(providers, "stability");
        assertFalse((Boolean) stability.get("healthy"));
        assertFalse((Boolean) stability.get("rateLimited"));
        assertTrue((Boolean) stability.get("overBudget"));
    }

    private static Map<String, Object> findByName(List<Map<String, Object>> providers, String name) {
        return providers.stream()
                .filter(p -> name.equals(p.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("provider not found: " + name));
    }
}
