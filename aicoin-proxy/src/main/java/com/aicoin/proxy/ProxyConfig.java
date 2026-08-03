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

    private static final String[] PROVIDERS = {"openai", "anthropic", "google", "mistral", "cohere"};

    /** Stable, canonical provider order used everywhere a full provider list must be reported (e.g. {@code GET /health}). */
    public static final List<String> PROVIDER_NAMES = Collections.unmodifiableList(Arrays.asList(PROVIDERS));

    private final int port;
    private final String eventsUrl;
    private final String priceUrl;
    private final String balanceUrlBase;
    private final Map<String, ProviderConfig> providers;
    private final double costPerTokenUsd;
    private final double defaultCostUsdPerCall;
    private final String freeCoinsCounterFile;
    private final int healthWindowSize;

    private ProxyConfig(int port, String eventsUrl, String priceUrl, String balanceUrlBase,
                         Map<String, ProviderConfig> providers,
                         double costPerTokenUsd, double defaultCostUsdPerCall, String freeCoinsCounterFile,
                         int healthWindowSize) {
        this.port = port;
        this.eventsUrl = eventsUrl;
        this.priceUrl = priceUrl;
        this.balanceUrlBase = balanceUrlBase;
        this.providers = providers;
        this.costPerTokenUsd = costPerTokenUsd;
        this.defaultCostUsdPerCall = defaultCostUsdPerCall;
        this.freeCoinsCounterFile = freeCoinsCounterFile;
        this.healthWindowSize = healthWindowSize;
    }

    public int getPort() {
        return port;
    }

    public String getEventsUrl() {
        return eventsUrl;
    }

    public String getPriceUrl() {
        return priceUrl;
    }

    /** @return {@code aicoin.balanceUrlBase}: used as {@code {balanceUrlBase}/balance/{walletId}} for wallet-id validation. */
    public String getBalanceUrlBase() {
        return balanceUrlBase;
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
        String eventsUrl = getString(yaml, "aicoin.eventsUrl", "http://localhost:9944/events");
        String priceUrl = getString(yaml, "aicoin.priceUrl", "http://localhost:9944/price");
        String balanceUrlBase = getString(yaml, "aicoin.balanceUrlBase", "http://localhost:9944");

        Map<String, ProviderConfig> defaults = new LinkedHashMap<>();
        defaults.put("openai", new ProviderConfig("https://api.openai.com", "", "Authorization", "Bearer ", false, null));
        defaults.put("anthropic", new ProviderConfig("https://api.anthropic.com", "", "x-api-key", "", false, null));
        defaults.put("google", new ProviderConfig("https://generativelanguage.googleapis.com", "", null, null, true, "key"));
        defaults.put("mistral", new ProviderConfig("https://api.mistral.ai", "", "Authorization", "Bearer ", false, null));
        defaults.put("cohere", new ProviderConfig("https://api.cohere.ai", "", "Authorization", "Bearer ", false, null));

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
        eventsUrl = envStr(env, "AICOIN_PROXY_AICOIN_EVENTS_URL", eventsUrl);
        priceUrl = envStr(env, "AICOIN_PROXY_AICOIN_PRICE_URL", priceUrl);
        balanceUrlBase = envStr(env, "AICOIN_PROXY_AICOIN_BALANCE_URL_BASE", balanceUrlBase);
        costPerTokenUsd = envDouble(env, "AICOIN_PROXY_COST_PER_TOKEN_USD", costPerTokenUsd);
        defaultCostUsdPerCall = envDouble(env, "AICOIN_PROXY_DEFAULT_COST_USD", defaultCostUsdPerCall);
        freeCoinsCounterFile = envStr(env, "AICOIN_PROXY_FREE_COINS_COUNTER_FILE", freeCoinsCounterFile);
        healthWindowSize = envInt(env, "AICOIN_PROXY_HEALTH_WINDOW_SIZE", healthWindowSize);

        return new ProxyConfig(port, eventsUrl, priceUrl, balanceUrlBase, providers, costPerTokenUsd, defaultCostUsdPerCall,
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
