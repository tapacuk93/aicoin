package com.aicoin.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks each provider, on a timer, whether it is actually there.
 *
 * <p>{@link ProviderHealthTracker} answers a different question: of the calls this proxy has
 * recently forwarded, did any come back 429 or 402/403. That is worth knowing, but it is silent
 * about a provider nobody has called — a revoked key, an expired card or a dead host looks exactly
 * like a healthy backend right up until the first customer call fails on it. So this probes
 * independently: one GET per provider per {@code health.probeIntervalSeconds}, to a path the
 * provider does not bill for (its model listing, usually — the same paths already declared as
 * {@code freePaths}), with the proxy's own key attached the way a real call would attach it.
 *
 * <p>What the answer means:
 * <ul>
 *   <li>2xx — alive.</li>
 *   <li>429 — alive: the provider is up and throttling us, which is a capacity problem, not an
 *       outage.</li>
 *   <li>401/403 — down: the key is rejected, so no call can be served no matter how well the host
 *       is running.</li>
 *   <li>402 — down: reachable but out of credit.</li>
 *   <li>5xx, timeout, connection failure — down.</li>
 *   <li>anything else (a 404 on the probe path, say) — alive, with the status in the detail: the
 *       provider answered, which is what was being asked; a wrong probe path is this proxy's bug
 *       and should not be reported as the provider being down.</li>
 * </ul>
 *
 * <p>A provider with no key configured is never probed and reports {@code unconfigured}. Before
 * the first round completes every provider reports {@code unknown} — not {@code alive}, because
 * "we have not looked yet" and "we looked and it answered" are different statements and a status
 * page that conflates them is worse than no status page.
 */
public final class ProviderLiveness {

    private static final Logger LOG = Logger.getLogger(ProviderLiveness.class.getName());

    /** State names as they appear in {@code GET /health}. */
    public static final String STATE_ALIVE = "alive";
    public static final String STATE_DOWN = "down";
    public static final String STATE_UNCONFIGURED = "unconfigured";
    public static final String STATE_UNKNOWN = "unknown";

    private final ProxyConfig config;
    private final HttpClient http;
    private final Duration timeout;
    private final Map<String, Probe> probes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "provider-liveness");
        thread.setDaemon(true);
        return thread;
    });

    public ProviderLiveness(ProxyConfig config) {
        this.config = config;
        this.timeout = Duration.ofSeconds(Math.max(1, config.getHealthProbeTimeoutSeconds()));
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Begins probing, immediately and then every {@code health.probeIntervalSeconds}. No-op when probing is off. */
    public void start() {
        if (!config.isHealthProbeEnabled()) {
            LOG.info("provider liveness probing disabled; /health will report state=unknown");
            return;
        }
        long interval = Math.max(5, config.getHealthProbeIntervalSeconds());
        scheduler.scheduleWithFixedDelay(this::probeAll, 0, interval, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    /** @return the last probe result for the given provider; {@code unconfigured} or {@code unknown} if it has none. */
    public Probe probeFor(String provider) {
        Probe probe = probes.get(provider);
        if (probe != null) {
            return probe;
        }
        ProviderConfig providerConfig = config.getProvider(provider);
        String apiKey = providerConfig == null ? null : providerConfig.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return Probe.unconfigured();
        }
        return Probe.unknown();
    }

    private void probeAll() {
        for (String provider : ProxyConfig.PROVIDER_NAMES) {
            try {
                probe(provider);
            } catch (RuntimeException e) {
                // One provider's probe must never stop the round: the whole point is the other
                // seven still get reported.
                LOG.log(Level.FINE, "liveness probe failed to start for " + provider, e);
                probes.put(provider, Probe.down(0, 0, "probe failed: " + e.getClass().getSimpleName()));
            }
        }
    }

    private void probe(String provider) {
        ProviderConfig providerConfig = config.getProvider(provider);
        String apiKey = providerConfig.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            probes.put(provider, Probe.unconfigured());
            return;
        }
        String path = config.getHealthProbePath(provider);
        if (path == null || path.isEmpty()) {
            probes.put(provider, Probe.unknown());
            return;
        }
        HttpRequest request = buildRequest(providerConfig, path, apiKey, provider);
        long started = System.nanoTime();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, error) -> {
                    long latencyMs = (System.nanoTime() - started) / 1_000_000L;
                    if (error != null) {
                        probes.put(provider, Probe.down(0, latencyMs, describe(error)));
                        return;
                    }
                    probes.put(provider, fromStatus(response.statusCode(), latencyMs));
                });
    }

    private HttpRequest buildRequest(ProviderConfig providerConfig, String path, String apiKey, String provider) {
        String url = providerConfig.getBaseUrl() + path;
        if (providerConfig.isAuthAsQueryParam()) {
            url += (url.contains("?") ? "&" : "?") + providerConfig.getAuthQueryParamName() + "=" + apiKey;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(timeout)
                .header("Accept", "application/json");
        if (!providerConfig.isAuthAsQueryParam() && providerConfig.getAuthHeader() != null) {
            builder.header(providerConfig.getAuthHeader(), providerConfig.getAuthPrefix() + apiKey);
        }
        // Anthropic rejects an unversioned request outright, listings included, so a probe without
        // this reports a live provider as down.
        if ("anthropic".equals(provider)) {
            builder.header("anthropic-version", ChatAdapter.ANTHROPIC_VERSION);
        }
        return builder.build();
    }

    /** Maps an upstream status to a probe result. Package-private for testing without a network. */
    static Probe fromStatus(int status, long latencyMs) {
        if (status >= 200 && status < 300) {
            return new Probe(STATE_ALIVE, status, latencyMs, "responded " + status, System.currentTimeMillis());
        }
        if (status == 429) {
            return new Probe(STATE_ALIVE, status, latencyMs, "rate limited", System.currentTimeMillis());
        }
        if (status == 401 || status == 403) {
            return Probe.down(status, latencyMs, "key rejected (" + status + ")");
        }
        if (status == 402) {
            return Probe.down(status, latencyMs, "out of credit (402)");
        }
        if (status >= 500) {
            return Probe.down(status, latencyMs, "provider error " + status);
        }
        return new Probe(STATE_ALIVE, status, latencyMs, "responded " + status, System.currentTimeMillis());
    }

    /** A connection failure said in words a status page can print. */
    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String name = cause.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (name.contains("timeout")) {
            return "no response before timeout";
        }
        if (name.contains("connect")) {
            return "could not connect";
        }
        if (name.contains("unknownhost")) {
            return "host not found";
        }
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? "unreachable" : message;
    }

    /** One provider's last answer: the state, what produced it, and when. */
    public static final class Probe {
        private final String state;
        private final int status;
        private final long latencyMs;
        private final String detail;
        private final long checkedAtMillis;

        Probe(String state, int status, long latencyMs, String detail, long checkedAtMillis) {
            this.state = state;
            this.status = status;
            this.latencyMs = latencyMs;
            this.detail = detail;
            this.checkedAtMillis = checkedAtMillis;
        }

        static Probe down(int status, long latencyMs, String detail) {
            return new Probe(STATE_DOWN, status, latencyMs, detail, System.currentTimeMillis());
        }

        static Probe unconfigured() {
            return new Probe(STATE_UNCONFIGURED, 0, 0, "no key configured", 0);
        }

        static Probe unknown() {
            return new Probe(STATE_UNKNOWN, 0, 0, "not checked yet", 0);
        }

        public String getState() {
            return state;
        }

        public boolean isAlive() {
            return STATE_ALIVE.equals(state);
        }

        public int getStatus() {
            return status;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public String getDetail() {
            return detail;
        }

        /** Epoch millis of the last probe, or 0 when it has never been probed. */
        public long getCheckedAtMillis() {
            return checkedAtMillis;
        }
    }
}
