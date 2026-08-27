package com.aicoin.proxy;

import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Pure-function cost computation from an upstream JSON response body, per
 * CONTRACT.md's "Forwarding" step 4:
 *
 * <pre>
 * parse JSON body for usage.total_tokens (OpenAI-style) or
 * usage.input_tokens + usage.output_tokens (Anthropic-style);
 * cost_usd = tokens * pricing.costPerTokenUsd if found,
 * else pricing.defaultCostUsdPerCall.
 * </pre>
 *
 * We parse the body with SnakeYAML rather than pulling in a separate JSON
 * library: valid JSON is (for the purposes of this contract's simple object
 * shapes) valid YAML, and SnakeYAML is already a project dependency for
 * config loading. This is a deliberate simplification; see README.md.
 */
public final class CostCalculator {

    private CostCalculator() {
    }

    public static double computeCostUsd(String jsonBody, double costPerTokenUsd, double defaultCostUsdPerCall) {
        Long tokens = extractTokens(jsonBody);
        if (tokens != null) {
            return tokens * costPerTokenUsd;
        }
        return defaultCostUsdPerCall;
    }

    /**
     * Cost priced at the calling provider's and model's own rates, rather than one blended
     * per-token figure for every provider alike.
     *
     * <p>Input and output are counted separately because every provider charges a multiple for
     * output — 5x on Anthropic's current models. Summing them and applying one rate, as the
     * blended path above does, misprices any call whose input/output ratio isn't the one the
     * blended rate was fitted to. Callers differ sharply here and the proxy serves all of them:
     * a chat turn is roughly balanced, a long-context or conversation-replaying client is heavily
     * input-weighted, a generation client heavily output-weighted. No single rate fits that range.
     *
     * <p>Falls back in order: model rates -> provider default rates -> the provider's flat
     * per-call figure (for speech and image APIs, which report no tokens at all) -> the global
     * blended rate over whatever token total could be parsed -> {@code defaultCostUsdPerCall}.
     */
    /**
     * A call's cost together with the token count it was derived from, for callers that need to
     * know not just what a call cost but how big it was.
     *
     * <p>The price signal needs both: averaging cost per call weights a 200-token lookup and a
     * 200,000-token long-context turn equally, which misstates what an aicoin is worth whenever
     * the mix of call sizes shifts. {@code tokensKnown} is false for the responses that report no
     * usage at all — speech and image APIs — where there is nothing to weight by and the caller
     * must decide what to do instead of being handed a fabricated zero.
     */
    public static final class Priced {
        private final double costUsd;
        private final long tokens;
        private final boolean tokensKnown;

        Priced(double costUsd, long tokens, boolean tokensKnown) {
            this.costUsd = costUsd;
            this.tokens = tokens;
            this.tokensKnown = tokensKnown;
        }

        public double getCostUsd() {
            return costUsd;
        }

        /** Total billable tokens for the call; meaningless unless {@link #isTokensKnown()}. */
        public long getTokens() {
            return tokens;
        }

        public boolean isTokensKnown() {
            return tokensKnown;
        }
    }

    /**
     * Cost and size in one parse, so the size is available to the price signal rather than being
     * computed and thrown away. Same fallback chain as {@link #computeCostUsd(String, String,
     * ModelPricing)}, which now delegates here.
     */
    public static Priced price(String provider, String jsonBody, ModelPricing pricing) {
        Usage usage = extractUsage(jsonBody);
        long tokens = usage == null ? 0
                : usage.inputTokens + usage.outputTokens + usage.cacheReadTokens + usage.cacheWriteTokens;
        return new Priced(computeCostUsd(provider, jsonBody, pricing), tokens, tokens > 0);
    }

    public static double computeCostUsd(String provider, String jsonBody, ModelPricing pricing) {
        Usage usage = extractUsage(jsonBody);
        if (usage != null) {
            // A model billed per call is billed per call whatever it reports. Image models answer
            // with a token count like any other, and charging them by it prices a four-cent image
            // at a fraction of a cent — which is then published as the coin price.
            Double perCallForModel = pricing.modelPerCallUsd(provider, usage.model);
            if (perCallForModel != null) {
                return perCallForModel;
            }
            ModelPricing.Rates rates = pricing.ratesFor(provider, usage.model);
            if (rates != null) {
                // Cache reads bill at a tenth of the input rate and cache writes at 1.25x, so a
                // conversation that reuses a cached prefix would be materially over-priced if its
                // cache tokens were counted as ordinary input.
                double inputUsd = (usage.inputTokens
                        + usage.cacheReadTokens * 0.1
                        + usage.cacheWriteTokens * 1.25) * rates.getInputUsdPerMillion() / 1_000_000.0;
                double outputUsd = usage.outputTokens * rates.getOutputUsdPerMillion() / 1_000_000.0;
                return inputUsd + outputUsd;
            }
            long total = usage.inputTokens + usage.outputTokens
                    + usage.cacheReadTokens + usage.cacheWriteTokens;
            if (total > 0) {
                return total * pricing.getFallbackCostPerTokenUsd();
            }
        }
        Double perCall = pricing.perCallUsd(provider);
        return perCall != null ? perCall : pricing.getFallbackCostUsdPerCall();
    }

    /** Token counts and the model that produced them, normalized across provider response shapes. */
    static final class Usage {
        final String model;
        final long inputTokens;
        final long outputTokens;
        final long cacheReadTokens;
        final long cacheWriteTokens;

        Usage(String model, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
            this.model = model;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.cacheReadTokens = cacheReadTokens;
            this.cacheWriteTokens = cacheWriteTokens;
        }
    }

    /**
     * Input/output token counts from any of the three response shapes this proxy forwards:
     * Anthropic's {@code usage.input_tokens}/{@code output_tokens}, OpenAI's
     * {@code usage.prompt_tokens}/{@code completion_tokens}, and Gemini's
     * {@code usageMetadata.promptTokenCount}/{@code candidatesTokenCount}.
     *
     * <p>{@code null} when the body carries no usage at all — a speech or image response, or
     * anything unparseable.
     */
    static Usage extractUsage(String jsonBody) {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            return null;
        }
        Object parsed;
        try {
            parsed = new Yaml().load(jsonBody);
        } catch (Exception e) {
            return null;
        }
        if (!(parsed instanceof Map)) {
            return null;
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        // Google names it `modelVersion`; everyone else uses `model`. Reading only the latter left
        // every Gemini response with no model at all, so per-model pricing could never apply to
        // one — which is how image generation came to be billed at Google's text rates.
        String model = root.get("model") instanceof String ? (String) root.get("model")
                : root.get("modelVersion") instanceof String ? (String) root.get("modelVersion")
                : null;

        Object usageObj = root.get("usage");
        if (usageObj instanceof Map) {
            Map<?, ?> usage = (Map<?, ?>) usageObj;
            long in = number(usage, "input_tokens") + number(usage, "prompt_tokens");
            long out = number(usage, "output_tokens") + number(usage, "completion_tokens");
            long cacheRead = number(usage, "cache_read_input_tokens");
            long cacheWrite = number(usage, "cache_creation_input_tokens");
            if (in + out + cacheRead + cacheWrite > 0) {
                return new Usage(model, in, out, cacheRead, cacheWrite);
            }
            // OpenAI-style bodies that report only a total: attribute it all to input, the
            // cheaper side, so an unknown split can never over-charge.
            long total = number(usage, "total_tokens");
            if (total > 0) {
                return new Usage(model, total, 0, 0, 0);
            }
        }

        Object geminiUsage = root.get("usageMetadata");
        if (geminiUsage instanceof Map) {
            Map<?, ?> usage = (Map<?, ?>) geminiUsage;
            long in = number(usage, "promptTokenCount");
            long out = number(usage, "candidatesTokenCount");
            long cacheRead = number(usage, "cachedContentTokenCount");
            if (in + out + cacheRead > 0) {
                // Gemini names the model in the request path, not the body; provider defaults apply.
                return new Usage(model, in, out, cacheRead, 0);
            }
        }
        return null;
    }

    private static long number(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * @return total token count found in a "usage" object (OpenAI-style
     *         total_tokens, or Anthropic-style input_tokens + output_tokens),
     *         or {@code null} if no such usage information is present/parseable.
     */
    public static Long extractTokens(String jsonBody) {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            return null;
        }

        Object parsed;
        try {
            parsed = new Yaml().load(jsonBody);
        } catch (Exception e) {
            return null;
        }

        if (!(parsed instanceof Map)) {
            return null;
        }
        Object usageObj = ((Map<?, ?>) parsed).get("usage");
        if (!(usageObj instanceof Map)) {
            return null;
        }
        Map<?, ?> usage = (Map<?, ?>) usageObj;

        Object totalTokens = usage.get("total_tokens");
        if (totalTokens instanceof Number) {
            return ((Number) totalTokens).longValue();
        }

        Object inputTokens = usage.get("input_tokens");
        Object outputTokens = usage.get("output_tokens");
        if (inputTokens instanceof Number || outputTokens instanceof Number) {
            long sum = 0;
            if (inputTokens instanceof Number) {
                sum += ((Number) inputTokens).longValue();
            }
            if (outputTokens instanceof Number) {
                sum += ((Number) outputTokens).longValue();
            }
            return sum;
        }

        return null;
    }
}
