package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxyConfigTest {

    @Test
    void bundledDefaultsMatchContract() {
        ProxyConfig config = ProxyConfig.load(new HashMap<>());
        assertEquals(8080, config.getPort());
        assertEquals("http://localhost:9944/events", config.getEventsUrl());
        assertEquals("http://localhost:9944/price", config.getPriceUrl());
        assertEquals("http://localhost:9944", config.getBalanceUrlBase());
        assertEquals("free-coins-counter.txt", config.getFreeCoinsCounterFile());

        assertEquals("https://api.openai.com", config.getProviderBaseUrl("openai"));
        assertEquals("https://api.anthropic.com", config.getProviderBaseUrl("anthropic"));
        assertEquals("https://generativelanguage.googleapis.com", config.getProviderBaseUrl("google"));
        assertEquals("https://api.mistral.ai", config.getProviderBaseUrl("mistral"));
        assertEquals("https://api.cohere.ai", config.getProviderBaseUrl("cohere"));

        ProviderConfig openai = config.getProvider("openai");
        assertEquals("", openai.getApiKey());
        assertEquals("Authorization", openai.getAuthHeader());
        assertEquals("Bearer ", openai.getAuthPrefix());
        assertFalse(openai.isAuthAsQueryParam());

        ProviderConfig anthropic = config.getProvider("anthropic");
        assertEquals("x-api-key", anthropic.getAuthHeader());
        assertEquals("", anthropic.getAuthPrefix());

        ProviderConfig google = config.getProvider("google");
        assertTrue(google.isAuthAsQueryParam());
        assertEquals("key", google.getAuthQueryParamName());

        assertEquals(0.000002, config.getCostPerTokenUsd(), 1e-12);
        assertEquals(0.001, config.getDefaultCostUsdPerCall(), 1e-12);
        assertEquals(50, config.getHealthWindowSize());
    }

    @Test
    void envVarsOverrideBundledDefaults() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_PORT", "9090");
        env.put("AICOIN_PROXY_OPENAI_BASEURL", "http://localhost:1234");
        env.put("AICOIN_PROXY_AICOIN_EVENTS_URL", "http://localhost:5555/events");
        env.put("AICOIN_PROXY_COST_PER_TOKEN_USD", "0.5");
        env.put("AICOIN_PROXY_DEFAULT_COST_USD", "1.5");

        ProxyConfig config = ProxyConfig.load(env);
        assertEquals(9090, config.getPort());
        assertEquals("http://localhost:1234", config.getProviderBaseUrl("openai"));
        assertEquals("http://localhost:5555/events", config.getEventsUrl());
        assertEquals(0.5, config.getCostPerTokenUsd(), 1e-12);
        assertEquals(1.5, config.getDefaultCostUsdPerCall(), 1e-12);

        // Unrelated providers stay at their defaults.
        assertEquals("https://api.anthropic.com", config.getProviderBaseUrl("anthropic"));
    }

    @Test
    void envVarsOverridePriceUrlAndFreeCoinsCounterFile() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_AICOIN_PRICE_URL", "http://localhost:6666/price");
        env.put("AICOIN_PROXY_FREE_COINS_COUNTER_FILE", "/tmp/some-counter.txt");

        ProxyConfig config = ProxyConfig.load(env);
        assertEquals("http://localhost:6666/price", config.getPriceUrl());
        assertEquals("/tmp/some-counter.txt", config.getFreeCoinsCounterFile());
    }

    @Test
    void envVarOverridesBalanceUrlBase() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_AICOIN_BALANCE_URL_BASE", "http://localhost:7777");

        ProxyConfig config = ProxyConfig.load(env);
        assertEquals("http://localhost:7777", config.getBalanceUrlBase());
    }

    @Test
    void envVarOverridesHealthWindowSize() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_HEALTH_WINDOW_SIZE", "25");

        ProxyConfig config = ProxyConfig.load(env);
        assertEquals(25, config.getHealthWindowSize());
    }

    @Test
    void envVarsOverrideProviderApiKeyAndAuthHeaderFields() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_OPENAI_APIKEY", "sk-test-123");
        env.put("AICOIN_PROXY_OPENAI_AUTHHEADER", "X-Custom-Auth");
        env.put("AICOIN_PROXY_OPENAI_AUTHPREFIX", "Token ");
        env.put("AICOIN_PROXY_ANTHROPIC_APIKEY", "anthropic-key-abc");

        ProxyConfig config = ProxyConfig.load(env);

        ProviderConfig openai = config.getProvider("openai");
        assertEquals("sk-test-123", openai.getApiKey());
        assertEquals("X-Custom-Auth", openai.getAuthHeader());
        assertEquals("Token ", openai.getAuthPrefix());

        assertEquals("anthropic-key-abc", config.getProvider("anthropic").getApiKey());
    }

    @Test
    void envVarsOverrideGoogleQueryParamFields() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_GOOGLE_APIKEY", "google-key-xyz");
        env.put("AICOIN_PROXY_GOOGLE_AUTHASQUERYPARAM", "false");
        env.put("AICOIN_PROXY_GOOGLE_AUTHQUERYPARAMNAME", "apikey");

        ProxyConfig config = ProxyConfig.load(env);

        ProviderConfig google = config.getProvider("google");
        assertEquals("google-key-xyz", google.getApiKey());
        assertFalse(google.isAuthAsQueryParam());
        assertEquals("apikey", google.getAuthQueryParamName());
    }
}
