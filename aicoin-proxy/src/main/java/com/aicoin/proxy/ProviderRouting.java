package com.aicoin.proxy;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Pure-function resolution of the {@code X-AI} request header to a known
 * provider name, per CONTRACT.md's "Routing" section: the client calls the
 * proxy at the exact same path a real provider would use; the {@code X-AI}
 * header (one of {@code
 * openai|anthropic|google|mistral|cohere|elevenlabs|stability|kimi},
 * case-insensitive) selects which {@code providers.<name>} config entry to
 * use. Missing or unknown values resolve to {@link Optional#empty()}, which
 * callers must turn into {@code 400 {"error":"missing or unknown X-AI header"}}.
 */
public final class ProviderRouting {

    public static final Set<String> KNOWN_PROVIDERS =
            Set.of("openai", "anthropic", "google", "mistral", "cohere", "elevenlabs", "stability", "kimi");

    private ProviderRouting() {
    }

    public static Optional<String> resolve(String xAiHeaderValue) {
        if (xAiHeaderValue == null) {
            return Optional.empty();
        }
        String trimmed = xAiHeaderValue.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return KNOWN_PROVIDERS.contains(lower) ? Optional.of(lower) : Optional.empty();
    }
}
