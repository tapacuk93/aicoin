package com.aicoin.proxy;

/**
 * Per-provider config entry (one of {@code providers.openai}, {@code
 * providers.anthropic}, etc. in application.yaml), per CONTRACT.md's
 * "Config" section: an upstream {@code baseUrl} plus the proxy's own {@code
 * apiKey} and instructions for how to inject it — either as a header
 * ({@code authHeader}+{@code authPrefix}) or as a URL query parameter
 * ({@code authAsQueryParam}+{@code authQueryParamName}).
 */
public final class ProviderConfig {

    private final String baseUrl;
    private final String apiKey;
    private final String authHeader;
    private final String authPrefix;
    private final boolean authAsQueryParam;
    private final String authQueryParamName;

    public ProviderConfig(String baseUrl, String apiKey, String authHeader, String authPrefix,
                           boolean authAsQueryParam, String authQueryParamName) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.authHeader = authHeader;
        this.authPrefix = authPrefix == null ? "" : authPrefix;
        this.authAsQueryParam = authAsQueryParam;
        this.authQueryParamName = authQueryParamName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    /** Header name to inject the apiKey into, e.g. "Authorization" or "x-api-key". Null when {@link #isAuthAsQueryParam()}. */
    public String getAuthHeader() {
        return authHeader;
    }

    /** Prefix prepended to the apiKey in the header value, e.g. "Bearer ". Never null (empty string if unset). */
    public String getAuthPrefix() {
        return authPrefix;
    }

    /** True when the apiKey must be injected as a URL query parameter instead of a header (e.g. Google). */
    public boolean isAuthAsQueryParam() {
        return authAsQueryParam;
    }

    /** Query parameter name to inject the apiKey into, e.g. "key". Null unless {@link #isAuthAsQueryParam()}. */
    public String getAuthQueryParamName() {
        return authQueryParamName;
    }
}
