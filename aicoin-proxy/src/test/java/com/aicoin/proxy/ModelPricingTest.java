package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins per-provider, per-model pricing against the shapes the text providers actually return.
 *
 * <p>The behaviour under test is that two calls of identical token count but different model, or
 * identical token count split differently between input and output, cost different amounts — which
 * a single blended per-token rate could never express.
 */
class ModelPricingTest {

    private static final double FALLBACK_PER_TOKEN = 0.000002;
    private static final double FALLBACK_PER_CALL = 0.001;
    private final ModelPricing pricing = ModelPricing.defaults(FALLBACK_PER_TOKEN, FALLBACK_PER_CALL);

    private static String anthropicBody(String model, int in, int out) {
        return "{\"model\":\"" + model + "\",\"usage\":{\"input_tokens\":" + in
                + ",\"output_tokens\":" + out + "}}";
    }

    @Test
    void anthropicCallPricesAtItsModelsRates() {
        // 10,000 in / 1,000 out — a long-context turn, the input-heavy end of the range.
        double cost = CostCalculator.computeCostUsd("anthropic",
                anthropicBody("claude-sonnet-5", 10_000, 1_000), pricing);
        // 10k * $3/M + 1k * $15/M
        assertEquals(0.030 + 0.015, cost, 1e-9);
    }

    @Test
    void haikuIsCheaperThanSonnetForTheSameTokens() {
        double sonnet = CostCalculator.computeCostUsd("anthropic",
                anthropicBody("claude-sonnet-5", 10_000, 1_000), pricing);
        double haiku = CostCalculator.computeCostUsd("anthropic",
                anthropicBody("claude-haiku-4-5", 10_000, 1_000), pricing);
        assertTrue(haiku < sonnet, "haiku must not be priced at sonnet's rates");
        assertEquals(0.010 + 0.005, haiku, 1e-9);
    }

    @Test
    void outputCostsMoreThanInput() {
        // Same 4,000 tokens either way — a blended per-token rate would price these identically.
        double inputHeavy = CostCalculator.computeCostUsd("anthropic",
                anthropicBody("claude-sonnet-5", 3_000, 1_000), pricing);
        double outputHeavy = CostCalculator.computeCostUsd("anthropic",
                anthropicBody("claude-sonnet-5", 1_000, 3_000), pricing);
        assertNotEquals(inputHeavy, outputHeavy, 1e-9);
        assertTrue(outputHeavy > inputHeavy);
    }

    @Test
    void datedModelIdMatchesItsFamilyByPrefix() {
        // Requests name `claude-haiku-4-5`; responses come back dated.
        double cost = CostCalculator.computeCostUsd("anthropic",
                anthropicBody("claude-haiku-4-5-20251001", 1_000, 0), pricing);
        assertEquals(0.001, cost, 1e-9);
    }

    @Test
    void openAiPromptCompletionShapeIsUnderstood() {
        String body = "{\"model\":\"gpt-4o\",\"usage\":{\"prompt_tokens\":1000,"
                + "\"completion_tokens\":500,\"total_tokens\":1500}}";
        double cost = CostCalculator.computeCostUsd("openai", body, pricing);
        assertEquals(1_000 * 2.50 / 1e6 + 500 * 10.00 / 1e6, cost, 1e-9);
    }

    @Test
    void kimiPricesAtItsModelsRatesOnTheOpenAiShape() {
        // Kimi answers on the OpenAI-compatible surface, so it reports prompt/completion tokens.
        String body = "{\"model\":\"kimi-k2.6\",\"usage\":{\"prompt_tokens\":10000,"
                + "\"completion_tokens\":1000,\"total_tokens\":11000}}";
        double cost = CostCalculator.computeCostUsd("kimi", body, pricing);
        assertEquals(10_000 * 0.95 / 1e6 + 1_000 * 4.00 / 1e6, cost, 1e-9);
    }

    @Test
    void kimiHighSpeedCodeModelBeatsTheShorterPrefix() {
        // "kimi-k2.7-code" is a prefix of "kimi-k2.7-code-highspeed"; the longer entry must win,
        // since the high-speed variant bills output at twice the standard rate.
        String highspeed = "{\"model\":\"kimi-k2.7-code-highspeed\",\"usage\":"
                + "{\"prompt_tokens\":1000,\"completion_tokens\":1000}}";
        String standard = "{\"model\":\"kimi-k2.7-code\",\"usage\":"
                + "{\"prompt_tokens\":1000,\"completion_tokens\":1000}}";
        assertEquals(1_000 * 0.95 / 1e6 + 1_000 * 8.00 / 1e6,
                CostCalculator.computeCostUsd("kimi", highspeed, pricing), 1e-9);
        assertEquals(1_000 * 0.95 / 1e6 + 1_000 * 4.00 / 1e6,
                CostCalculator.computeCostUsd("kimi", standard, pricing), 1e-9);
    }

    @Test
    void geminiUsageMetadataShapeIsUnderstood() {
        String body = "{\"usageMetadata\":{\"promptTokenCount\":1000,\"candidatesTokenCount\":500}}";
        double cost = CostCalculator.computeCostUsd("google", body, pricing);
        assertEquals(1_000 * 0.30 / 1e6 + 500 * 2.50 / 1e6, cost, 1e-9);
    }

    @Test
    void cachedInputIsNotPricedAsFreshInput() {
        String uncached = anthropicBody("claude-sonnet-5", 10_000, 0);
        String cached = "{\"model\":\"claude-sonnet-5\",\"usage\":{\"input_tokens\":0,"
                + "\"output_tokens\":0,\"cache_read_input_tokens\":10000}}";
        double cachedCost = CostCalculator.computeCostUsd("anthropic", cached, pricing);
        assertEquals(CostCalculator.computeCostUsd("anthropic", uncached, pricing) * 0.1,
                cachedCost, 1e-9, "cache reads bill at a tenth of the input rate");
    }

    @Test
    void providersThatReportNoTokensGetTheirPerCallRate() {
        // An ElevenLabs speech response carries audio, not usage.
        double cost = CostCalculator.computeCostUsd("elevenlabs",
                "{\"audio_base64\":\"AAAA\",\"alignment\":{}}", pricing);
        assertEquals(0.03, cost, 1e-9);
        assertNotEquals(FALLBACK_PER_CALL, cost, 1e-9);
    }

    @Test
    void anUnknownProviderStillFallsBackRatherThanFailing() {
        double cost = CostCalculator.computeCostUsd("mistral",
                "{\"usage\":{\"total_tokens\":1000}}", pricing);
        assertEquals(1_000 * FALLBACK_PER_TOKEN, cost, 1e-9);
        assertEquals(FALLBACK_PER_CALL,
                CostCalculator.computeCostUsd("mistral", "{\"nothing\":true}", pricing), 1e-9);
    }

    @Test
    void aGzippedBodyStillPricesByModel() throws Exception {
        // Ties the two fixes together: the decode step feeds the per-model table.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(anthropicBody("claude-haiku-4-5", 1_000, 0).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        io.netty.handler.codec.http.HttpHeaders headers = new io.netty.handler.codec.http.DefaultHttpHeaders();
        headers.set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING, "gzip");
        String decoded = UpstreamForwarder.decodedForPricing(headers, out.toByteArray());
        assertEquals(0.001, CostCalculator.computeCostUsd("anthropic", decoded, pricing), 1e-9);
    }
}
