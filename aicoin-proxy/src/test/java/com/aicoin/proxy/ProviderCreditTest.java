package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An account that cannot pay must show as such, and be routed around — learned from real calls,
 * because the liveness probe (a free models list) succeeds for an empty account. And it must not
 * be confused with an ordinary rate limit, which would take a working provider out of routing.
 */
class ProviderCreditTest {

    /** Anthropic's refusal as production saw it on 2026-10-27: a 400, not a 402. */
    static final String ANTHROPIC_EMPTY = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to"
            + " upgrade or purchase credits.\"},\"request_id\":\"req_011\"}";
    /** OpenAI's, as this proxy saw it on 2026-10-26. */
    static final String OPENAI_EMPTY = "{\"error\":{\"message\":\"You have no credits remaining. Add credits to continue"
            + " using the API at https://platform.openai.com/settings/organization/billing/.\",\"type\":\"insufficient_quota\","
            + "\"param\":null,\"code\":\"credit_balance_exhausted\"}}";

    @AfterEach
    void clear() {
        for (String provider : ProxyConfig.PROVIDER_NAMES) {
            ProviderCredit.get().clear(provider);
        }
    }

    @Test
    void eachProvidersOwnCreditRefusalIsRecognised() {
        assertEquals("Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to"
                + " upgrade or purchase credits.", ProviderCredit.creditFailure("anthropic", 400, ANTHROPIC_EMPTY));
        assertNotNull(ProviderCredit.creditFailure("openai", 429, OPENAI_EMPTY));
        assertNotNull(ProviderCredit.creditFailure("openai", 429,
                "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\"}}"));
        assertNotNull(ProviderCredit.creditFailure("kimi", 429, "{\"error\":{\"message\":\"Your account is suspended, "
                + "please check your plan and billing details\",\"type\":\"exceeded_current_quota_error\"}}"));
        assertNotNull(ProviderCredit.creditFailure("google", 429,
                "{\"error\":{\"code\":429,\"message\":\"Your prepayment credits are depleted.\",\"status\":\"RESOURCE_EXHAUSTED\"}}"));
        assertNotNull(ProviderCredit.creditFailure("mistral", 402, "Payment Required"));
    }

    @Test
    void anOrdinaryRateLimitIsNotAnEmptyAccount() {
        assertNull(ProviderCredit.creditFailure("openai", 429,
                "{\"error\":{\"message\":\"Rate limit reached for gpt-5\",\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}"));
        assertNull(ProviderCredit.creditFailure("google", 429, "{\"error\":{\"code\":429,\"message\":\"Quota exceeded for"
                + " metric: generate_content_requests per minute\",\"status\":\"RESOURCE_EXHAUSTED\"}}"));
        assertNull(ProviderCredit.creditFailure("kimi", 429,
                "{\"error\":{\"message\":\"The engine is currently overloaded\",\"type\":\"engine_overloaded_error\"}}"));
        assertNull(ProviderCredit.creditFailure("anthropic", 400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: too large\"}}"));
    }

    @Test
    void healthShowsItUntilAPaidCallSucceeds() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_ANTHROPIC_APIKEY", "k");
        ProxyConfig config = ProxyConfig.load(env);
        ProviderLiveness liveness = new ProviderLiveness(config);
        ProviderHealthTracker tracker = new ProviderHealthTracker(50);

        ProviderCredit.get().record("anthropic", 400, ANTHROPIC_EMPTY.getBytes(StandardCharsets.UTF_8));
        String json = HealthHandler.buildJson(tracker, liveness, config);
        Map<?, ?> anthropic = providers(json).get(1);
        assertEquals("anthropic", anthropic.get("name"));
        assertEquals("out_of_credit", anthropic.get("state"));
        assertEquals(false, anthropic.get("healthy"));
        assertEquals(true, anthropic.get("overBudget"));
        String detail = (String) anthropic.get("detail");
        assertTrue(detail.startsWith("out of credit since 20"), detail);
        assertTrue(detail.contains("credit balance is too low"), detail);
        assertTrue(ProviderLiveness.isUnavailable(liveness.probeFor("anthropic").getState()));

        // Refused again: still out, and still out since the first refusal.
        ProviderCredit.Failure first = ProviderCredit.get().failureFor("anthropic");
        ProviderCredit.get().record("anthropic", 400, ANTHROPIC_EMPTY.getBytes(StandardCharsets.UTF_8));
        assertEquals(first.sinceMillis, ProviderCredit.get().failureFor("anthropic").sinceMillis);

        ProviderCredit.get().record("anthropic", 200, "{}".getBytes(StandardCharsets.UTF_8));
        assertFalse(ProviderCredit.get().isOutOfCredit("anthropic"), "topped up: the next paid call clears it");
        assertEquals("unknown", providers(HealthHandler.buildJson(tracker, liveness, config)).get(1).get("state"));
    }

    @Test
    void routingAndThePanelSkipAnAccountThatCannotPay() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_ANTHROPIC_APIKEY", "k");
        env.put("AICOIN_PROXY_KIMI_APIKEY", "k");
        ProxyConfig config = ProxyConfig.load(env);
        ProviderCredit.get().record("anthropic", 400, ANTHROPIC_EMPTY.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("kimi"), ConsortiumHandler.panel(config, null, "writing", null, true));
        assertFalse(CapabilityHandler.rank(config, null, Capability.TEXT, "writing").contains("anthropic"));
        assertEquals("kimi", ConsortiumHandler.editor(config, ConsortiumHandler.panel(config, null), null),
                "an empty account is never the editor");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> providers(String json) {
        return (List<Map<?, ?>>) ((Map<?, ?>) Json.load(json)).get("providers");
    }

    @Test
    void aMarkLapsesSoTheNextCallCanFindOutTheAccountWasToppedUp() {
        // Routing skips an empty account, so nothing would ever call it again to clear the mark.
        ProviderCredit credit = ProviderCredit.get();
        credit.record("kimi", 429, "{\"error\":{\"type\":\"exceeded_current_quota_error\"}}"
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(credit.isOutOfCredit("kimi"));
        assertTrue(ProviderCredit.RECHECK_MILLIS <= 15 * 60 * 1000L, "minutes, not hours");
    }
}
