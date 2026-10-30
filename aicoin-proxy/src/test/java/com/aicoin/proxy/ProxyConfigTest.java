package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxyConfigTest {

    @Test
    void bundledDefaultsMatchContract() {
        ProxyConfig config = ProxyConfig.load(new HashMap<>());
        assertEquals(8080, config.getPort());
        assertEquals("localhost", config.getRedisHost());
        assertEquals(6379, config.getRedisPort());
        assertEquals("", config.getRedisUsername());
        assertEquals("", config.getRedisPassword());
        assertFalse(config.isRedisSsl());
        assertEquals(110.0, config.getDecayHalflifeDays(), 1e-12);
        assertEquals(3600, config.getFreeClaimCooldownSeconds());
        assertEquals(120, config.getSignatureSkewSeconds());
        assertEquals(5000, config.getFreeCoinsPoolSize());
        assertEquals("", config.getAdminToken());

        assertEquals("https://api.openai.com", config.getProviderBaseUrl("openai"));
        assertEquals("https://api.anthropic.com", config.getProviderBaseUrl("anthropic"));
        assertEquals("https://generativelanguage.googleapis.com", config.getProviderBaseUrl("google"));
        assertEquals("https://api.mistral.ai", config.getProviderBaseUrl("mistral"));
        assertEquals("https://api.cohere.ai", config.getProviderBaseUrl("cohere"));
        assertEquals("https://api.elevenlabs.io", config.getProviderBaseUrl("elevenlabs"));
        assertEquals("https://api.stability.ai", config.getProviderBaseUrl("stability"));
        assertEquals("https://api.moonshot.ai", config.getProviderBaseUrl("kimi"));

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

        ProviderConfig kimi = config.getProvider("kimi");
        assertEquals("Authorization", kimi.getAuthHeader());
        assertEquals("Bearer ", kimi.getAuthPrefix());
        assertFalse(kimi.isAuthAsQueryParam());
        assertTrue(FreeTargets.isFree("GET", "/v1/models", kimi.getFreePaths()));

        assertEquals(0.000002, config.getCostPerTokenUsd(), 1e-12);
        assertEquals(0.001, config.getDefaultCostUsdPerCall(), 1e-12);
        assertEquals(50, config.getHealthWindowSize());
        assertEquals(60, config.getUpstreamReadTimeoutSeconds());

        assertEquals(16, config.getIapPackages().size());
        IapPackageConfig first = config.getIapPackages().get(0);
        assertEquals("com.tarasmaslov.infiniteairadio.aicoin.small", first.getProductId());
        assertEquals(50, first.getCoins());
        assertEquals(0.99, first.getUsdPriceHint(), 1e-12);
    }

    @Test
    void bundledIapPackagesCoverEveryAppAtAllFourTiers() {
        ProxyConfig config = ProxyConfig.load(new HashMap<>());
        List<IapPackageConfig> packages = config.getIapPackages();

        assertTrue(packages.stream().anyMatch(p -> p.getProductId().equals("com.tarasmaslov.infiniteairadio.aicoin.xl")));
        assertTrue(packages.stream().anyMatch(p -> p.getProductId().equals("com.tarasmaslov.alllanguageslearner.aicoin.medium")));
        assertTrue(packages.stream().anyMatch(p -> p.getProductId().equals("com.tarasmaslov.learnit.aicoin.large")));
        for (String tier : List.of("small", "medium", "large", "xl")) {
            String productId = "com.oeaio.infiniteaibook.aicoin." + tier;
            assertTrue(packages.stream().anyMatch(p -> p.getProductId().equals(productId)), productId);
        }

        for (IapPackageConfig p : packages) {
            assertTrue(p.getCoins() > 0, p.getProductId());
            assertTrue(p.getUsdPriceHint() > 0, p.getProductId());
        }
    }

    @Test
    void bundledFreePathsCoverEachProvidersNonBilledEndpoints() {
        ProxyConfig config = ProxyConfig.load(new HashMap<>());

        assertTrue(FreeTargets.isFree("GET", "/v1/models", config.getProvider("openai").getFreePaths()));
        assertTrue(FreeTargets.isFree("POST", "/v1/messages/count_tokens", config.getProvider("anthropic").getFreePaths()));
        assertTrue(FreeTargets.isFree("POST", "/v1beta/models/gemini-2.0-flash:countTokens",
                config.getProvider("google").getFreePaths()));
        assertTrue(FreeTargets.isFree("GET", "/v1/models", config.getProvider("mistral").getFreePaths()));
        assertTrue(FreeTargets.isFree("POST", "/v1/tokenize", config.getProvider("cohere").getFreePaths()));
        assertTrue(FreeTargets.isFree("GET", "/v1/voices", config.getProvider("elevenlabs").getFreePaths()));
        assertTrue(FreeTargets.isFree("GET", "/v1/user/balance", config.getProvider("stability").getFreePaths()));

        // The actual inference endpoints stay billed for every provider.
        assertFalse(FreeTargets.isFree("POST", "/v1/chat/completions", config.getProvider("openai").getFreePaths()));
        assertFalse(FreeTargets.isFree("POST", "/v1/messages", config.getProvider("anthropic").getFreePaths()));
        assertFalse(FreeTargets.isFree("POST", "/v1beta/models/gemini-2.0-flash:generateContent",
                config.getProvider("google").getFreePaths()));
        assertFalse(FreeTargets.isFree("POST", "/v1/chat/completions", config.getProvider("mistral").getFreePaths()));
        assertFalse(FreeTargets.isFree("POST", "/v1/chat", config.getProvider("cohere").getFreePaths()));
        assertFalse(FreeTargets.isFree("POST", "/v1/text-to-speech/voice-id",
                config.getProvider("elevenlabs").getFreePaths()));
        assertFalse(FreeTargets.isFree("POST", "/v1/generation/engine/text-to-image",
                config.getProvider("stability").getFreePaths()));
    }

    @Test
    void envVarOverridesFreePathsAndNoneDisablesThem() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_OPENAI_FREEPATHS", "GET /v1/models,GET /v1/files");
        env.put("AICOIN_PROXY_ANTHROPIC_FREEPATHS", "none");

        ProxyConfig config = ProxyConfig.load(env);

        assertEquals(List.of("GET /v1/models", "GET /v1/files"), config.getProvider("openai").getFreePaths());
        assertTrue(config.getProvider("anthropic").getFreePaths().isEmpty());
        // "none" means everything anthropic is billed again, count_tokens included.
        assertFalse(FreeTargets.isFree("POST", "/v1/messages/count_tokens",
                config.getProvider("anthropic").getFreePaths()));

        // Unrelated providers keep their bundled free paths.
        assertTrue(FreeTargets.isFree("GET", "/v1/voices", config.getProvider("elevenlabs").getFreePaths()));
    }

    @Test
    void envVarsOverrideBundledDefaults() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_PORT", "9090");
        env.put("AICOIN_PROXY_OPENAI_BASEURL", "http://localhost:1234");
        env.put("AICOIN_PROXY_REDIS_HOST", "redis.internal");
        env.put("AICOIN_PROXY_COST_PER_TOKEN_USD", "0.5");
        env.put("AICOIN_PROXY_DEFAULT_COST_USD", "1.5");
        env.put("AICOIN_PROXY_UPSTREAM_READ_TIMEOUT_SECONDS", "15");

        ProxyConfig config = ProxyConfig.load(env);
        assertEquals(9090, config.getPort());
        assertEquals("http://localhost:1234", config.getProviderBaseUrl("openai"));
        assertEquals("redis.internal", config.getRedisHost());
        assertEquals(0.5, config.getCostPerTokenUsd(), 1e-12);
        assertEquals(1.5, config.getDefaultCostUsdPerCall(), 1e-12);
        // Tunable without a rebuild: a provider that turns out to need longer than 60s, or a
        // deployment that wants to fail faster, is an env change on the running host.
        assertEquals(15, config.getUpstreamReadTimeoutSeconds());

        // Unrelated providers stay at their defaults.
        assertEquals("https://api.anthropic.com", config.getProviderBaseUrl("anthropic"));
    }

    @Test
    void envVarsOverrideRedisAndLedgerSettings() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_REDIS_PORT", "16379");
        env.put("AICOIN_PROXY_REDIS_USERNAME", "aicoin-proxy");
        env.put("AICOIN_PROXY_REDIS_PASSWORD", "s3cret");
        env.put("AICOIN_PROXY_REDIS_SSL", "true");
        env.put("AICOIN_PROXY_DECAY_HALFLIFE_DAYS", "30");
        env.put("AICOIN_PROXY_FREE_CLAIM_COOLDOWN_SECONDS", "60");
        env.put("AICOIN_PROXY_SIGNATURE_SKEW_SECONDS", "30");
        env.put("AICOIN_PROXY_FREE_COINS_POOL_SIZE", "5");
        env.put("AICOIN_PROXY_ADMIN_TOKEN", "s3cret-admin-token");

        ProxyConfig config = ProxyConfig.load(env);
        assertEquals(16379, config.getRedisPort());
        assertEquals("aicoin-proxy", config.getRedisUsername());
        assertEquals("s3cret", config.getRedisPassword());
        assertTrue(config.isRedisSsl());
        assertEquals(30.0, config.getDecayHalflifeDays(), 1e-12);
        assertEquals(60, config.getFreeClaimCooldownSeconds());
        assertEquals(30, config.getSignatureSkewSeconds());
        assertEquals(5, config.getFreeCoinsPoolSize());
        assertEquals("s3cret-admin-token", config.getAdminToken());
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

    @Test
    void consortiumDefaultsNameAModelForEveryChatProvider() {
        ProxyConfig config = ProxyConfig.load(new HashMap<>());
        ConsortiumConfig consortium = config.getConsortium();

        assertTrue(consortium.isEnabled());
        assertEquals(3, consortium.getMaxRounds());
        assertEquals(4000, consortium.getMaxOutputTokens());
        assertEquals(60000, consortium.getMaxContextChars());
        assertEquals(8000, consortium.getLeadContextChars());
        assertEquals("", consortium.getEditor(), "empty means the first panelist");
        for (String provider : ChatAdapter.CHAT_PROVIDERS) {
            assertNotNull(consortium.modelFor(provider), provider + " needs a model to be on a panel");
        }
        assertEquals("claude-sonnet-5", consortium.modelFor("anthropic"));
        assertEquals("kimi-k2.6", consortium.modelFor("kimi"));
    }

    @Test
    void envVarsOverrideConsortiumRoundsAndModels() {
        // The reason these are env-overridable at all: a model id that lapses upstream would
        // otherwise need a release to replace.
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_CONSORTIUM_MAX_ROUNDS", "1");
        env.put("AICOIN_PROXY_CONSORTIUM_KIMI_MODEL", "kimi-k2.7-code");
        env.put("AICOIN_PROXY_CONSORTIUM_LEAD_CONTEXT_CHARS", "500");
        env.put("AICOIN_PROXY_CONSORTIUM_ENABLED", "false");

        ConsortiumConfig consortium = ProxyConfig.load(env).getConsortium();

        assertEquals(1, consortium.getMaxRounds());
        assertEquals("kimi-k2.7-code", consortium.modelFor("kimi"));
        assertEquals(500, consortium.getLeadContextChars());
        assertFalse(consortium.isEnabled());
        assertEquals("claude-sonnet-5", consortium.modelFor("anthropic"), "unrelated models stay put");
    }

    @Test
    void aRoundCapBelowOneIsRaisedToOne() {
        // Zero rounds would mean drafting and merging an answer nobody then reviews, which is not
        // the thing the endpoint offers.
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_CONSORTIUM_MAX_ROUNDS", "0");
        assertEquals(1, ProxyConfig.load(env).getConsortium().getMaxRounds());
    }

    @Test
    void envVarsOverrideKimiFields() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_KIMI_APIKEY", "kimi-key-123");
        env.put("AICOIN_PROXY_KIMI_BASEURL", "http://localhost:3333");

        ProxyConfig config = ProxyConfig.load(env);

        ProviderConfig kimi = config.getProvider("kimi");
        assertEquals("kimi-key-123", kimi.getApiKey());
        assertEquals("http://localhost:3333", kimi.getBaseUrl());
    }

    @Test
    void bestModelsAreConfiguredAndFallBackToTheEverydayModel() {
        ConsortiumConfig consortium = ProxyConfig.load(new HashMap<>()).getConsortium();
        assertEquals("claude-opus-5-5", consortium.modelFor("anthropic", true));
        assertEquals("claude-sonnet-5", consortium.modelFor("anthropic", false));
        // No best model is configured for Mistral, so "best" keeps its seat with its usual model.
        assertEquals(consortium.modelFor("mistral"), consortium.modelFor("mistral", true));

        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_CONSORTIUM_MISTRAL_BEST_MODEL", "mistral-huge");
        assertEquals("mistral-huge", ProxyConfig.load(env).getConsortium().modelFor("mistral", true));
    }

    @Test
    void aCallerMayRaiseTheOutputCapOnlyUpToTheCeiling() {
        ConsortiumConfig consortium = ProxyConfig.load(new HashMap<>()).getConsortium();
        assertEquals(32000, consortium.getMaxOutputTokensCeiling());
        assertEquals(4000, consortium.outputTokensFor(null), "no ask: the default");
        assertEquals(20000, consortium.outputTokensFor(20000));
        assertEquals(32000, consortium.outputTokensFor(1_000_000), "clamped, not refused");
        assertEquals(600, consortium.getTurnReadTimeoutSeconds());
        assertEquals(600000, consortium.getMaxLibraryChars());
        assertEquals(2000, consortium.getMaxLibraryEntries());
        assertEquals(3, consortium.getMaxReadsPerTurn());
        assertEquals(20, consortium.getMaxLibraryReads());

        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_CONSORTIUM_MAX_OUTPUT_TOKENS_CEILING", "8000");
        env.put("AICOIN_PROXY_CONSORTIUM_MAX_LIBRARY_READS", "2");
        ConsortiumConfig narrowed = ProxyConfig.load(env).getConsortium();
        assertEquals(8000, narrowed.outputTokensFor(20000));
        assertEquals(2, narrowed.getMaxLibraryReads());
    }

    @Test
    void aModelSettingMayBeAListTriedInOrder() {
        ConsortiumConfig consortium = ProxyConfig.load(new HashMap<>()).getConsortium();
        assertEquals(java.util.List.of("kimi-k3.1-pro-highspeed", "kimi-k3.1-pro"), consortium.modelsFor("kimi", true));
        assertEquals("kimi-k3.1-pro-highspeed", consortium.modelFor("kimi", true), "the first is the one to try first");
        assertEquals(java.util.List.of("claude-sonnet-5"), consortium.modelsFor("anthropic", false),
                "a single id is still a valid setting");
        assertEquals("claude-sonnet-5", consortium.modelFor("anthropic"));

        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_CONSORTIUM_KIMI_MODEL", "kimi-k2.7-code, kimi-k2.6");
        ConsortiumConfig overridden = ProxyConfig.load(env).getConsortium();
        assertEquals(java.util.List.of("kimi-k2.7-code", "kimi-k2.6"), overridden.modelsFor("kimi", false),
                "an env override lists them comma-separated");
    }

    @Test
    void durableCallsAreKeptADayAndLeasedForThirtySeconds() {
        ConsortiumConfig consortium = ProxyConfig.load(new HashMap<>()).getConsortium();
        assertEquals(24, consortium.getCallRetentionHours());
        assertEquals(30, consortium.getCallLeaseSeconds());
    }

    @Test
    void hostedPhonesAreOffByDefaultWithTheGpuUnavailable() {
        AnyphoneConfig anyphone = ProxyConfig.load(new HashMap<>()).getAnyphone();
        assertFalse(anyphone.isEnabled());
        assertEquals("us-east-1", anyphone.getRegion());
        assertEquals(10, anyphone.getMinimumMinutes());
        assertEquals(0.009, anyphone.getCoinValueUsd(), 1e-12);
        assertEquals(0.5, anyphone.getFeeMargin(), 1e-12);
        assertEquals("com.oeaio.anyphone", anyphone.getPushTopic());
        assertEquals("", anyphone.getVersion(), "required, no default");
        assertEquals("", anyphone.getReleaseUrl(), "required: the script's default bucket is gone");
        assertTrue(anyphone.isBakedAmi());
        assertEquals(40, anyphone.getRootVolumeGb());
        assertEquals("nvidia", anyphone.tier("gpu").getImageGpu());
        assertEquals("none", anyphone.tier("cpu").getImageGpu());
        assertFalse(anyphone.tier("gpu").isNestedVirtualization());
        assertEquals(List.of("gpu", "cpu"), List.copyOf(anyphone.getTiers().keySet()));
        assertEquals("g4dn.metal", anyphone.tier("gpu").getInstanceType());
        assertFalse(anyphone.tier("gpu").isAvailable());
        assertEquals(7.82, anyphone.tier("gpu").getUsdPerHour(), 1e-12);
        assertEquals("c8i.2xlarge", anyphone.tier("cpu").getInstanceType());
        assertTrue(anyphone.tier("cpu").isAvailable());
        assertTrue(anyphone.tier("cpu").isNestedVirtualization());
        assertFalse(anyphone.tier("cpu").isSpot());
    }

    @Test
    void hostedPhoneSettingsTakeEnvOverrides() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_ANYPHONE_ENABLED", "true");
        env.put("AICOIN_PROXY_ANYPHONE_AMI_ID", "ami-1");
        env.put("AICOIN_PROXY_ANYPHONE_SUBNET_ID", "subnet-1");
        env.put("AICOIN_PROXY_ANYPHONE_SECURITY_GROUP_ID", "sg-1");
        env.put("AICOIN_PROXY_ANYPHONE_GPU_AVAILABLE", "true");
        env.put("AICOIN_PROXY_ANYPHONE_VERSION", "v0.2.0");
        env.put("AICOIN_PROXY_ANYPHONE_RELEASE_URL", "https://r.example.com/releases");
        env.put("AICOIN_PROXY_ANYPHONE_GPU_MARKET", "spot");
        env.put("AICOIN_PROXY_ANYPHONE_GPU_USD_PER_HOUR", "4.5");
        env.put("AICOIN_PROXY_COIN_VALUE_USD", "0.01");
        AnyphoneConfig anyphone = ProxyConfig.load(env).getAnyphone();
        assertTrue(anyphone.isEnabled());
        assertEquals("ami-1", anyphone.getAmiId());
        assertEquals("subnet-1", anyphone.getSubnetId());
        assertEquals("sg-1", anyphone.getSecurityGroupId());
        assertEquals("v0.2.0", anyphone.getVersion());
        assertEquals("https://r.example.com/releases", anyphone.getReleaseUrl());
        assertTrue(anyphone.tier("gpu").isAvailable());
        assertTrue(anyphone.tier("gpu").isSpot());
        assertEquals(4.5, anyphone.tier("gpu").getUsdPerHour(), 1e-12);
        assertEquals("ami-1", anyphone.amiFor(anyphone.tier("gpu")));
        assertEquals(0.01, anyphone.getCoinValueUsd(), 1e-12, "follows pricing.coinValueUsd unless set");
    }
}
