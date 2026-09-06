package com.aicoin.proxy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code health.probe} block: whether {@link ProviderLiveness} asks each provider whether it
 * is there, how often, how long it waits, and which path it asks on.
 *
 * <p>The path must be one the provider does not bill for — a model listing, an engine listing —
 * because this runs forever on a timer whether or not anybody is using the proxy. The defaults are
 * all drawn from the provider's own {@code freePaths}, which is the list of endpoints already
 * established as costing nothing upstream.
 */
final class HealthProbeConfig {

    private final boolean enabled;
    private final int intervalSeconds;
    private final int timeoutSeconds;
    private final Map<String, String> paths;

    HealthProbeConfig(boolean enabled, int intervalSeconds, int timeoutSeconds, Map<String, String> paths) {
        this.enabled = enabled;
        this.intervalSeconds = intervalSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.paths = paths == null ? Map.of() : new LinkedHashMap<>(paths);
    }

    boolean isEnabled() {
        return enabled;
    }

    /** Seconds between probe rounds. Every provider is asked once per round. */
    int getIntervalSeconds() {
        return intervalSeconds;
    }

    /** How long one probe may take, connecting and responding, before it counts as no answer. */
    int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /** @return the path to probe this provider on, or "" for a provider that has none configured. */
    String pathFor(String provider) {
        return paths.getOrDefault(provider, "");
    }
}
