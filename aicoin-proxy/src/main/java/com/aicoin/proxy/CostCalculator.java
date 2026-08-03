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
