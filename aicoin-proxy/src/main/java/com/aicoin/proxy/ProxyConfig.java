package com.aicoin.proxy;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads aicoin-proxy configuration per CONTRACT.md's "Config" section.
 *
 * Precedence (highest wins): specific env var override &gt; YAML file value
 * &gt; hardcoded default. The YAML file itself comes from AICOIN_PROXY_CONFIG
 * if set, else the bundled src/main/resources/application.yaml (whose values
 * mirror the hardcoded defaults exactly).
 */
public final class ProxyConfig {

    private static final String[] PROVIDERS =
            {"openai", "anthropic", "google", "mistral", "cohere", "elevenlabs", "stability"};

    /** Stable, canonical provider order used everywhere a full provider list must be reported (e.g. {@code GET /health}). */
    public static final List<String> PROVIDER_NAMES = Collections.unmodifiableList(Arrays.asList(PROVIDERS));

    private final int port;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final boolean redisSsl;
    private final double decayHalflifeDays;
    private final int freeClaimCooldownSeconds;
    private final Map<String, ProviderConfig> providers;
    private final double costPerTokenUsd;
    private final double defaultCostUsdPerCall;
    private final String freeCoinsCounterFile;
    private final int healthWindowSize;

    private ProxyConfig(int port, String redisHost, int redisPort, String redisPassword, boolean redisSsl,
                         double decayHalflifeDays, int freeClaimCooldownSeconds,
                         Map<String, ProviderConfig> providers,
                         double costPerTokenUsd, double defaultCostUsdPerCall, String freeCoinsCounterFile,
                         int healthWindowSize) {
        this.port = port;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisPassword = redisPassword;
        this.redisSsl = redisSsl;
        this.decayHalflifeDays = decayHalflifeDays;
        this.freeClaimCooldownSeconds = freeClaimCooldownSeconds;
        this.providers = providers;
        this.costPerTokenUsd = costPerTokenUsd;
        this.defaultCostUsdPerCall = defaultCostUsdPerCall;
        this.freeCoinsCounterFile = freeCoinsCounterFile;
        this.healthWindowSize = healthWindowSize;
    }

    public int getPort() {
        return port;
    }

    public String getRedisHost() {
        return redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public String getRedisPassword() {
        return redisPassword;
    }

    public boolean isRedisSsl() {
        return redisSsl;
    }

    /** @return {@code aicoin.decayHalflifeDays}: the price formula's recency-decay half-life, in days. */
    public double getDecayHalflifeDays() {
        return decayHalflifeDays;
    }

    /** @return {@code aicoin.freeClaimCooldownSeconds}: minimum seconds between free-coin claims for the same wallet. */
    public int getFreeClaimCooldownSeconds() {
        return freeClaimCooldownSeconds;
    }

    /** @return the full config entry for the given (already-lowercased, known) provider, or null if unknown. */
    public ProviderConfig getProvider(String provider) {
        return providers.get(provider);
    }

    /** @return baseUrl for the given (already-lowercased, known) provider, or null if unknown. */
    public String getProviderBaseUrl(String provider) {
        ProviderConfig p = providers.get(provider);
        return p != null ? p.getBaseUrl() : null;
    }

    public double getCostPerTokenUsd() {
        return costPerTokenUsd;
    }

    public double getDefaultCostUsdPerCall() {
        return defaultCostUsdPerCall;
    }

    public String getFreeCoinsCounterFile() {
        return freeCoinsCounterFile;
    }

    /** @return {@code health.windowSize}: how many of a provider's most recent forwarded calls to track for {@code GET /health}. */
    public int getHealthWindowSize() {
        return healthWindowSize;
    }

    public static ProxyConfig load() {
        return load(System.getenv());
    }

    /** Package-visible for testing with a synthetic env map. */
    static ProxyConfig load(Map<String, String> env) {
        Map<String, Object> yaml = loadYaml(env);

        int port = getInt(yaml, "server.port", 8080);
        String redisHost = getString(yaml, "redis.host", "localhost");
        int redisPort = getInt(yaml, "redis.port", 6379);
        String redisPassword = getString(yaml, "redis.password", "");
        boolean redisSsl = getBoolean(yaml, "redis.ssl", false);
        double decayHalflifeDays = getDouble(yaml, "aicoin.decayHalflifeDays", 110.0);
        int freeClaimCooldownSeconds = getInt(yaml, "aicoin.freeClaimCooldownSeconds", 3600);

        Map<String, ProviderConfig> defaults = new LinkedHashMap<>();
        defaults.put("openai", new ProviderConfig("https://api.openai.com", "", "Authorization", "Bearer ", false, null));
        defaults.put("anthropic", new ProviderConfig("https://api.anthropic.com", "", "x-api-key", "", false, null));
        defaults.put("google", new ProviderConfig("https://generativelanguage.googleapis.com", "", null, null, true, "key"));
        defaults.put("mistral", new ProviderConfig("https://api.mistral.ai", "", "Authorization", "Bearer ", false, null));
        defaults.put("cohere", new ProviderConfig("https://api.cohere.ai", "", "Authorization", "Bearer ", false, null));
        defaults.put("elevenlabs", new ProviderConfig("https://api.elevenlabs.io", "", "xi-api-key", "", false, null));
        defaults.put("stability", new ProviderConfig("https://api.stability.ai", "", "Authorization", "Bearer ", false, null));

        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        for (String provider : PROVIDERS) {
            ProviderConfig def = defaults.get(provider);
            String prefix = "providers." + provider + ".";

            String baseUrl = getString(yaml, prefix + "baseUrl", def.getBaseUrl());
            String apiKey = getString(yaml, prefix + "apiKey", def.getApiKey());
            String authHeader = getString(yaml, prefix + "authHeader", def.getAuthHeader());
            String authPrefix = getString(yaml, prefix + "authPrefix", def.getAuthPrefix());
            boolean authAsQueryParam = getBoolean(yaml, prefix + "authAsQueryParam", def.isAuthAsQueryParam());
            String authQueryParamName = getString(yaml, prefix + "authQueryParamName", def.getAuthQueryParamName());

            String envPrefix = "AICOIN_PROXY_" + provider.toUpperCase(java.util.Locale.ROOT) + "_";
            baseUrl = envStr(env, envPrefix + "BASEURL", baseUrl);
            apiKey = envStr(env, envPrefix + "APIKEY", apiKey);
            authHeader = envStr(env, envPrefix + "AUTHHEADER", authHeader);
            authPrefix = envStr(env, envPrefix + "AUTHPREFIX", authPrefix);
            authAsQueryParam = envBool(env, envPrefix + "AUTHASQUERYPARAM", authAsQueryParam);
            authQueryParamName = envStr(env, envPrefix + "AUTHQUERYPARAMNAME", authQueryParamName);

            providers.put(provider, new ProviderConfig(baseUrl, apiKey, authHeader, authPrefix,
                    authAsQueryParam, authQueryParamName));
        }

        double costPerTokenUsd = getDouble(yaml, "pricing.costPerTokenUsd", 0.000002);
        double defaultCostUsdPerCall = getDouble(yaml, "pricing.defaultCostUsdPerCall", 0.001);
        String freeCoinsCounterFile = getString(yaml, "freeCoins.counterFile", "free-coins-counter.txt");
        int healthWindowSize = getInt(yaml, "health.windowSize", 50);

        // Env var overrides (highest precedence).
        port = envInt(env, "AICOIN_PROXY_PORT", port);
        redisHost = envStr(env, "AICOIN_PROXY_REDIS_HOST", redisHost);
        redisPort = envInt(env, "AICOIN_PROXY_REDIS_PORT", redisPort);
        redisPassword = envStr(env, "AICOIN_PROXY_REDIS_PASSWORD", redisPassword);
        redisSsl = envBool(env, "AICOIN_PROXY_REDIS_SSL", redisSsl);
        decayHalflifeDays = envDouble(env, "AICOIN_PROXY_DECAY_HALFLIFE_DAYS", decayHalflifeDays);
        freeClaimCooldownSeconds = envInt(env, "AICOIN_PROXY_FREE_CLAIM_COOLDOWN_SECONDS", freeClaimCooldownSeconds);
        costPerTokenUsd = envDouble(env, "AICOIN_PROXY_COST_PER_TOKEN_USD", costPerTokenUsd);
        defaultCostUsdPerCall = envDouble(env, "AICOIN_PROXY_DEFAULT_COST_USD", defaultCostUsdPerCall);
        freeCoinsCounterFile = envStr(env, "AICOIN_PROXY_FREE_COINS_COUNTER_FILE", freeCoinsCounterFile);
        healthWindowSize = envInt(env, "AICOIN_PROXY_HEALTH_WINDOW_SIZE", healthWindowSize);

        return new ProxyConfig(port, redisHost, redisPort, redisPassword, redisSsl,
                decayHalflifeDays, freeClaimCooldownSeconds, providers, costPerTokenUsd, defaultCostUsdPerCall,
                freeCoinsCounterFile, healthWindowSize);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Map<String, String> env) {
        String configPath = env.get("AICOIN_PROXY_CONFIG");
        try (InputStream in = openConfigStream(configPath)) {
            if (in == null) {
                return new LinkedHashMap<>();
            }
            Object loaded = new Yaml().load(in);
            if (loaded instanceof Map) {
                return (Map<String, Object>) loaded;
            }
            return new LinkedHashMap<>();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load aicoin-proxy config from "
                    + (configPath != null ? configPath : "bundled application.yaml"), e);
        }
    }

    private static InputStream openConfigStream(String configPath) throws IOException {
        if (configPath != null && !configPath.isEmpty()) {
            return new FileInputStream(configPath);
        }
        return ProxyConfig.class.getClassLoader().getResourceAsStream("application.yaml");
    }

    @SuppressWarnings("unchecked")
    private static Object getNested(Map<String, Object> root, String dottedPath) {
        String[] parts = dottedPath.split("\\.");
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    private static String getString(Map<String, Object> root, String dottedPath, String fallback) {
        Object v = getNested(root, dottedPath);
        return v != null ? String.valueOf(v) : fallback;
    }

    private static int getInt(Map<String, Object> root, String dottedPath, int fallback) {
        Object v = getNested(root, dottedPath);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v != null) {
            return Integer.parseInt(String.valueOf(v).trim());
        }
        return fallback;
    }

    private static double getDouble(Map<String, Object> root, String dottedPath, double fallback) {
        Object v = getNested(root, dottedPath);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v != null) {
            return Double.parseDouble(String.valueOf(v).trim());
        }
        return fallback;
    }

    private static boolean getBoolean(Map<String, Object> root, String dottedPath, boolean fallback) {
        Object v = getNested(root, dottedPath);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v != null) {
            return Boolean.parseBoolean(String.valueOf(v).trim());
        }
        return fallback;
    }

    private static String envStr(Map<String, String> env, String name, String fallback) {
        String v = env.get(name);
        return (v != null && !v.isEmpty()) ? v : fallback;
    }

    private static int envInt(Map<String, String> env, String name, int fallback) {
        String v = env.get(name);
        return (v != null && !v.isEmpty()) ? Integer.parseInt(v.trim()) : fallback;
    }

    private static double envDouble(Map<String, String> env, String name, double fallback) {
        String v = env.get(name);
        return (v != null && !v.isEmpty()) ? Double.parseDouble(v.trim()) : fallback;
    }

    private static boolean envBool(Map<String, String> env, String name, boolean fallback) {
        String v = env.get(name);
        return (v != null && !v.isEmpty()) ? Boolean.parseBoolean(v.trim()) : fallback;
    }
}
