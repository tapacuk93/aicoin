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
        assertEquals("localhost", config.getRedisHost());
        assertEquals(6379, config.getRedisPort());
        assertEquals("", config.getRedisPassword());
        assertFalse(config.isRedisSsl());
        assertEquals(110.0, config.getDecayHalflifeDays(), 1e-12);
        assertEquals(3600, config.getFreeClaimCooldownSeconds());
        assertEquals("free-coins-counter.txt", config.getFreeCoinsCounterFile());

        assertEquals("https://api.openai.com", config.getProviderBaseUrl("openai"));
        assertEquals("https://api.anthropic.com", config.getProviderBaseUrl("anthropic"));
        assertEquals("https://generativelanguage.googleapis.com", config.getProviderBaseUrl("google"));
        assertEquals("https://api.mistral.ai", config.getProviderBaseUrl("mistral"));
        assertEquals("https://api.cohere.ai", config.getProviderBaseUrl("cohere"));
        assertEquals("https://api.elevenlabs.io", config.getProviderBaseUrl("elevenlabs"));
        assertEquals("https://api.stability.ai", config.getProviderBaseUrl("stability"));

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

        ProviderConfig elevenlabs = config.getProvider("elevenlabs");
        assertEquals("xi-api-key", elevenlabs.getAuthHeader());
        assertEquals("", elevenlabs.getAuthPrefix());
        assertFalse(elevenlabs.isAuthAsQueryParam());

        ProviderConfig stability = config.getProvider("stability");
        assertEquals("Authorization", stability.getAuthHeader());
        assertEquals("Bearer ", stability.getAuthPrefix());
        assertFalse(stability.isAuthAsQueryParam());

        assertEquals(0.000002, config.getCostPerTokenUsd(), 1e-12);
        assertEquals(0.001, config.getDefaultCostUsdPerCall(), 1e-12);
        assertEquals(50, config.getHealthWindowSize());
    }

    @Test
    void envVarsOverrideBundledDefaults() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_PORT", "9090");
        env.put("AICOIN_PROXY_OPENAI_BASEURL", "http://localhost:1234");
        env.put("AICOIN_PROXY_REDIS_HOST", "redis.internal");
        env.put("AICOIN_PROXY_COST_PER_TOKEN_USD", "0.5");
        env.put("AICOIN_PROXY_DEFAULT_COST_USD", "1.5");

        ProxyConfig config = ProxyConfig.load(env);
        assertEquals(9090, config.getPort());
        assertEquals("http://localhost:1234", config.getProviderBaseUrl("openai"));
        assertEquals("redis.internal", config.getRedisHost());
        assertEquals(0.5, config.getCostPerTokenUsd(), 1e-12);
        assertEquals(1.5, config.getDefaultCostUsdPerCall(), 1e-12);

        // Unrelated providers stay at their defaults.
        assertEquals("https://api.anthropic.com", config.getProviderBaseUrl("anthropic"));
    }

    @Test
    void envVarsOverrideRedisAndLedgerSettings() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_REDIS_PORT", "16379");
        env.put("AICOIN_PROXY_REDIS_PASSWORD", "s3cret");
        env.put("AICOIN_PROXY_REDIS_SSL", "true");
        env.put("AICOIN_PROXY_DECAY_HALFLIFE_DAYS", "30");
        env.put("AICOIN_PROXY_FREE_CLAIM_COOLDOWN_SECONDS", "60");
        env.put("AICOIN_PROXY_FREE_COINS_COUNTER_FILE", "/tmp/some-counter.txt");

        ProxyConfig config = ProxyConfig.load(env);
        assertEquals(16379, config.getRedisPort());
        assertEquals("s3cret", config.getRedisPassword());
        assertTrue(config.isRedisSsl());
        assertEquals(30.0, config.getDecayHalflifeDays(), 1e-12);
        assertEquals(60, config.getFreeClaimCooldownSeconds());
        assertEquals("/tmp/some-counter.txt", config.getFreeCoinsCounterFile());
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

    @Test
    void envVarsOverrideElevenLabsFields() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_ELEVENLABS_APIKEY", "elevenlabs-key-abc");
        env.put("AICOIN_PROXY_ELEVENLABS_BASEURL", "http://localhost:1111");
        env.put("AICOIN_PROXY_ELEVENLABS_AUTHHEADER", "X-Custom-Voice-Auth");
        env.put("AICOIN_PROXY_ELEVENLABS_AUTHPREFIX", "Token ");

        ProxyConfig config = ProxyConfig.load(env);

        ProviderConfig elevenlabs = config.getProvider("elevenlabs");
        assertEquals("elevenlabs-key-abc", elevenlabs.getApiKey());
        assertEquals("http://localhost:1111", elevenlabs.getBaseUrl());
        assertEquals("X-Custom-Voice-Auth", elevenlabs.getAuthHeader());
        assertEquals("Token ", elevenlabs.getAuthPrefix());
    }

    @Test
    void envVarsOverrideStabilityFields() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_STABILITY_APIKEY", "stability-key-xyz");
        env.put("AICOIN_PROXY_STABILITY_BASEURL", "http://localhost:2222");

        ProxyConfig config = ProxyConfig.load(env);

        ProviderConfig stability = config.getProvider("stability");
        assertEquals("stability-key-xyz", stability.getApiKey());
        assertEquals("http://localhost:2222", stability.getBaseUrl());

        // Unrelated providers stay at their defaults.
        assertEquals("https://api.elevenlabs.io", config.getProviderBaseUrl("elevenlabs"));
    }
}
