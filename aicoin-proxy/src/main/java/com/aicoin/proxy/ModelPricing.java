package com.aicoin.proxy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-provider, per-model rates for turning a provider's usage numbers into a dollar figure.
 *
 * <p>Replaces a single blended {@code costPerTokenUsd} applied to every provider and model alike.
 * That one rate could not be right for more than one model at a time: Claude Sonnet 5 bills input
 * and output at $3 and $15 per million tokens, Claude Haiku 4.5 at $1 and $5 — a 15x spread between
 * the cheapest input and the dearest output, before considering that ElevenLabs bills by character
 * and image models bill by image and report no tokens at all. Recording every call at one flat rate
 * made {@code GET /price} a number with no relationship to what the calls actually cost, which
 * matters because the coin price and the IAP price ladder are both derived from it.
 *
 * <p>The proxy is client-agnostic: any wallet may route any supported provider's traffic
 * through it, so this table is keyed by what the response reports — provider and model — never
 * by which app sent the request.
 *
 * <p>Rates live in {@code application.yaml} under {@code pricing.providers} so they can be corrected
 * without a rebuild — provider list prices change more often than this code does.
 */
final class ModelPricing {

    /** Per-million-token rates for one model (or one provider's default). */
    static final class Rates {
        private final double inputUsdPerMillion;
        private final double outputUsdPerMillion;

        Rates(double inputUsdPerMillion, double outputUsdPerMillion) {
            this.inputUsdPerMillion = inputUsdPerMillion;
            this.outputUsdPerMillion = outputUsdPerMillion;
        }

        double getInputUsdPerMillion() {
            return inputUsdPerMillion;
        }

        double getOutputUsdPerMillion() {
            return outputUsdPerMillion;
        }
    }

    private final Map<String, Rates> providerDefaults;
    /** Keyed by provider, then by model-id prefix — see {@link #ratesFor}. */
    private final Map<String, Map<String, Rates>> modelRates;
    /** For providers that don't bill by token at all (speech, images). */
    private final Map<String, Double> perCallUsd;
    private final double fallbackCostPerTokenUsd;
    private final double fallbackCostUsdPerCall;

    ModelPricing(Map<String, Rates> providerDefaults,
                 Map<String, Map<String, Rates>> modelRates,
                 Map<String, Double> perCallUsd,
                 double fallbackCostPerTokenUsd,
                 double fallbackCostUsdPerCall) {
        this.providerDefaults = providerDefaults;
        this.modelRates = modelRates;
        this.perCallUsd = perCallUsd;
        this.fallbackCostPerTokenUsd = fallbackCostPerTokenUsd;
        this.fallbackCostUsdPerCall = fallbackCostUsdPerCall;
    }

    /**
     * The rates to price a call by, most specific first: the longest configured model prefix that
     * matches this response's {@code model} field, else the provider's default, else null (meaning
     * "no token rates configured — fall back to a flat per-call figure").
     *
     * <p>Prefix rather than exact match because providers version model ids in the response even
     * when the request didn't: ask for {@code claude-haiku-4-5} and the reply names
     * {@code claude-haiku-4-5-20251001}. Longest-match so a specific dated entry can override the
     * family default rather than being shadowed by it.
     */
    Rates ratesFor(String provider, String model) {
        Map<String, Rates> forProvider = modelRates.get(provider);
        if (forProvider != null && model != null) {
            String normalized = model.toLowerCase(Locale.ROOT);
            Rates best = null;
            int bestLength = -1;
            for (Map.Entry<String, Rates> entry : forProvider.entrySet()) {
                String prefix = entry.getKey();
                if (normalized.startsWith(prefix) && prefix.length() > bestLength) {
                    best = entry.getValue();
                    bestLength = prefix.length();
                }
            }
            if (best != null) {
                return best;
            }
        }
        return providerDefaults.get(provider);
    }

    /**
     * The flat charge for one call to a provider that reports no usage — ElevenLabs speech,
     * image generation. `null` when none is configured, leaving the global default.
     */
    Double perCallUsd(String provider) {
        return perCallUsd.get(provider);
    }

    double getFallbackCostPerTokenUsd() {
        return fallbackCostPerTokenUsd;
    }

    double getFallbackCostUsdPerCall() {
        return fallbackCostUsdPerCall;
    }

    /**
     * The rates shipped in {@code application.yaml}, duplicated here so a deployment whose config
     * predates the `pricing.providers` block still prices calls sensibly rather than silently
     * reverting to one blended rate.
     *
     * <p>Anthropic figures are list prices as published, seeded for the models clients currently
     * ask for; an unlisted model falls back to the provider's own rates.
     * Claude Sonnet 5 carries introductory pricing of $2/$10 through 2026-08-31; the standard
     * $3/$15 is used here on purpose, since under-recording cost is the failure this whole change
     * exists to fix and the introductory rate expires shortly.
     */
    static ModelPricing defaults(double fallbackCostPerTokenUsd, double fallbackCostUsdPerCall) {
        Map<String, Rates> providerDefaults = new LinkedHashMap<>();
        Map<String, Map<String, Rates>> models = new LinkedHashMap<>();
        Map<String, Double> perCall = new LinkedHashMap<>();

        Map<String, Rates> anthropic = new LinkedHashMap<>();
        anthropic.put("claude-opus-5", new Rates(5.00, 25.00));
        anthropic.put("claude-opus-4", new Rates(5.00, 25.00));
        anthropic.put("claude-sonnet-5", new Rates(3.00, 15.00));
        anthropic.put("claude-sonnet-4", new Rates(3.00, 15.00));
        anthropic.put("claude-haiku-4-5", new Rates(1.00, 5.00));
        models.put("anthropic", anthropic);
        providerDefaults.put("anthropic", new Rates(3.00, 15.00));

        // Non-Anthropic list prices move independently of this repo and are not verified here —
        // these are starting points to be corrected in `application.yaml`, not authoritative.
        providerDefaults.put("openai", new Rates(2.50, 10.00));
        providerDefaults.put("google", new Rates(0.30, 2.50));

        // Neither reports token usage: ElevenLabs bills characters, image models bill per image.
        perCall.put("elevenlabs", 0.03);
        perCall.put("stability", 0.03);

        return new ModelPricing(providerDefaults, models, perCall,
                fallbackCostPerTokenUsd, fallbackCostUsdPerCall);
    }
}
