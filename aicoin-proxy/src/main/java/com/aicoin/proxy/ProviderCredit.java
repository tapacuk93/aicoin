package com.aicoin.proxy;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which providers' accounts cannot pay right now, as learned from real calls — per CONTRACT.md's
 * {@code GET /health} ({@code state: "out_of_credit"}).
 *
 * <p>The liveness probe cannot see this. It asks for the provider's free model list, and an
 * account with no credit left still lists its models: on 2026-10-27 Anthropic's balance ran out,
 * every paid call came back {@code 400 "Your credit balance is too low to access the Anthropic
 * API"}, and {@code /health} — and the admin app reading it — went on showing Anthropic
 * {@code alive, responded 200}, while routing kept sending it work it could only refuse. So a
 * credit refusal on a paid call is recorded here, the provider's state reads {@code out_of_credit}
 * until a paid call to it succeeds again, and routing skips it like a provider that is down.
 *
 * <p>The test for a credit refusal is narrower than "an error mentioning quota", on purpose. An
 * ordinary rate limit says "quota" too (Google's per-minute {@code RESOURCE_EXHAUSTED}), and
 * mistaking one for an empty account would take a working provider out of routing for as long
 * as nobody happened to call it. Each provider's own wording is matched instead — see
 * {@link #creditFailure}. The admin alert ({@link AdminAlerts#isCreditError}) stays broader: a
 * push that turns out to be a rate limit costs a glance, a provider taken out of routing costs
 * every call.
 */
final class ProviderCredit {

    private static final ProviderCredit INSTANCE = new ProviderCredit();

    /** One provider's account refusing to pay: since when, how recently, and in its own words. */
    static final class Failure {
        final long sinceMillis;
        final long lastSeenMillis;
        final int status;
        final String message;

        Failure(long sinceMillis, long lastSeenMillis, int status, String message) {
            this.sinceMillis = sinceMillis;
            this.lastSeenMillis = lastSeenMillis;
            this.status = status;
            this.message = message;
        }

        /** What {@code /health} says about it. */
        String detail() {
            return "out of credit since " + Instant.ofEpochMilli(sinceMillis) + " (HTTP " + status + "): " + message;
        }
    }

    /**
     * How long a refusal keeps a provider out without being seen again. Routing skips an empty
     * account, so with nothing else no call would ever reach it to find out it was topped up —
     * the probe cannot tell, and only a forwarded call that names the provider itself would. So
     * after this long with no new refusal the mark lapses and the next call is let through: it
     * either succeeds (and the provider is back) or is refused (and the mark returns, at the cost
     * of one refunded call).
     */
    static final long RECHECK_MILLIS = 10 * 60 * 1000L;

    private final Map<String, Failure> failures = new ConcurrentHashMap<>();

    static ProviderCredit get() {
        return INSTANCE;
    }

    /**
     * One answer to a paid call. A credit refusal marks the provider; any success clears it —
     * the account was topped up, and the next call should not wait for a probe to find out.
     */
    void record(String provider, int status, byte[] body) {
        if (status >= 200 && status < 300) {
            failures.remove(provider);
            return;
        }
        String text = body == null ? "" : new String(body, 0, Math.min(body.length, 4000),
                java.nio.charset.StandardCharsets.UTF_8);
        String message = creditFailure(provider, status, text);
        if (message == null) {
            return;
        }
        long now = System.currentTimeMillis();
        failures.compute(provider, (p, old) -> new Failure(
                old == null || now - old.lastSeenMillis > RECHECK_MILLIS ? now : old.sinceMillis, now, status, message));
    }

    /** The provider's current credit failure, or null when its last paid call did not report one. */
    Failure failureFor(String provider) {
        Failure failure = failures.get(provider);
        if (failure != null && System.currentTimeMillis() - failure.lastSeenMillis > RECHECK_MILLIS) {
            failures.remove(provider, failure);
            return null;
        }
        return failure;
    }

    boolean isOutOfCredit(String provider) {
        return failureFor(provider) != null;
    }

    void clear(String provider) {
        failures.remove(provider);
    }

    /**
     * The provider's message when {@code body} says its account cannot pay, or null.
     *
     * <ul>
     *   <li>Anthropic: {@code 400 invalid_request_error} "Your credit balance is too low to access
     *       the Anthropic API" — seen in production 2026-10-27. A 400, so the status alone says
     *       nothing; only the words do.</li>
     *   <li>OpenAI: {@code 429} with code {@code insufficient_quota} ("You exceeded your current
     *       quota…") or {@code credit_balance_exhausted} ("You have no credits remaining") — the
     *       latter seen 2026-10-26. A plain rate limit is also 429, but carries neither code.</li>
     *   <li>Google (Gemini API): prepaid credits used up ("prepayment credits are depleted") or
     *   billing turned off ({@code BILLING_DISABLED}, "requires billing to be enabled"). Not
     *       {@code RESOURCE_EXHAUSTED} by itself, which is also the per-minute rate limit.</li>
     *   <li>Kimi (Moonshot): {@code exceeded_current_quota_error}, or an account "suspended … due
     *       to insufficient balance".</li>
     *   <li>Anyone: {@code 402 Payment Required}.</li>
     * </ul>
     * The Google and Kimi wordings are taken from their documented errors and have not been seen
     * from this proxy yet.
     */
    static String creditFailure(String provider, int status, String body) {
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        boolean credit = status == 402
                || b.contains("credit balance is too low")
                || b.contains("insufficient_quota")
                || b.contains("credit_balance_exhausted")
                || b.contains("no credits remaining")
                || b.contains("prepayment credits")
                || b.contains("credits are depleted")
                || b.contains("billing_disabled")
                || b.contains("requires billing to be enabled")
                || b.contains("exceeded_current_quota_error")
                || b.contains("insufficient balance")
                || b.contains("insufficient_balance")
                || b.contains("insufficient credits");
        if (!credit) {
            return null;
        }
        return messageOf(body);
    }

    /** The {@code message} out of a provider's error body, or the body itself, trimmed and capped. */
    private static String messageOf(String body) {
        try {
            Object parsed = Json.load(body);
            String found = findMessage(parsed);
            if (found != null && !found.isBlank()) {
                return cap(found);
            }
        } catch (Exception e) {
            // Not JSON: the raw text is the message.
        }
        return cap(body == null ? "" : body.trim());
    }

    private static String findMessage(Object node) {
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            if (map.get("message") instanceof String) {
                return (String) map.get("message");
            }
            for (Object value : map.values()) {
                String found = findMessage(value);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof java.util.List) {
            for (Object value : (java.util.List<?>) node) {
                String found = findMessage(value);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String cap(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}
