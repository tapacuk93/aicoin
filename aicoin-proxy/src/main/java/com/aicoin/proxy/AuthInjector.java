package com.aicoin.proxy;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Pure-function computation of how the proxy injects its own {@code apiKey}
 * for a provider into the forwarded upstream request, per CONTRACT.md's
 * "Routing" section: either as a header ({@code authHeader}+{@code
 * authPrefix}, e.g. {@code Authorization: Bearer <key>}) or as a URL query
 * parameter ({@code authQueryParamName}, e.g. Google's {@code ?key=<key>})
 * when {@code authAsQueryParam} is set.
 */
public final class AuthInjector {

    private AuthInjector() {
    }

    /** The single header-or-query-param name/value pair to inject for a given {@link ProviderConfig}. */
    public static final class Injection {
        private final boolean queryParam;
        private final String name;
        private final String value;

        private Injection(boolean queryParam, String name, String value) {
            this.queryParam = queryParam;
            this.name = name;
            this.value = value;
        }

        /** True if this must be applied as a URL query parameter rather than a header. */
        public boolean isQueryParam() {
            return queryParam;
        }

        /** Header name (or query parameter name when {@link #isQueryParam()}). */
        public String getName() {
            return name;
        }

        /** Header value (or raw, unencoded query parameter value when {@link #isQueryParam()}). */
        public String getValue() {
            return value;
        }
    }

    public static Injection compute(ProviderConfig cfg) {
        if (cfg.isAuthAsQueryParam()) {
            return new Injection(true, cfg.getAuthQueryParamName(), cfg.getApiKey());
        }
        return new Injection(false, cfg.getAuthHeader(), cfg.getAuthPrefix() + cfg.getApiKey());
    }

    /**
     * Appends {@code name=value} (URL-encoded) to {@code uri} (a path[+query]
     * string), joining with {@code ?} or {@code &} depending on whether a
     * query string is already present.
     */
    public static String appendQueryParam(String uri, String name, String value) {
        String separator = uri.contains("?") ? "&" : "?";
        return uri + separator + name + "=" + urlEncode(value);
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always supported; unreachable in practice.
            return s;
        }
    }
}
