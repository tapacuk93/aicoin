package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CostCalculatorTest {

    private static final double COST_PER_TOKEN = 0.000002;
    private static final double DEFAULT_COST = 0.001;

    @Test
    void openAiStyleUsageComputesCostFromTotalTokens() {
        String body = "{\"usage\":{\"total_tokens\":100},\"choices\":[{\"message\":\"hi\"}]}";
        double cost = CostCalculator.computeCostUsd(body, COST_PER_TOKEN, DEFAULT_COST);
        assertEquals(100 * COST_PER_TOKEN, cost, 1e-12);
    }

    @Test
    void anthropicStyleUsageComputesCostFromInputPlusOutputTokens() {
        String body = "{\"usage\":{\"input_tokens\":10,\"output_tokens\":25},\"content\":[]}";
        double cost = CostCalculator.computeCostUsd(body, COST_PER_TOKEN, DEFAULT_COST);
        assertEquals(35 * COST_PER_TOKEN, cost, 1e-12);
    }

    @Test
    void fallsBackToDefaultWhenNoUsageObjectPresent() {
        String body = "{\"choices\":[{\"message\":\"hi\"}]}";
        double cost = CostCalculator.computeCostUsd(body, COST_PER_TOKEN, DEFAULT_COST);
        assertEquals(DEFAULT_COST, cost, 1e-12);
    }

    @Test
    void fallsBackToDefaultOnUnparseableBody() {
        String body = "not json at all {{{";
        double cost = CostCalculator.computeCostUsd(body, COST_PER_TOKEN, DEFAULT_COST);
        assertEquals(DEFAULT_COST, cost, 1e-12);
    }

    @Test
    void fallsBackToDefaultOnEmptyBody() {
        double cost = CostCalculator.computeCostUsd("", COST_PER_TOKEN, DEFAULT_COST);
        assertEquals(DEFAULT_COST, cost, 1e-12);
    }

    @Test
    void fallsBackToDefaultOnNullBody() {
        double cost = CostCalculator.computeCostUsd(null, COST_PER_TOKEN, DEFAULT_COST);
        assertEquals(DEFAULT_COST, cost, 1e-12);
    }

    @Test
    void totalTokensTakesPrecedenceOverInputOutputWhenBothPresent() {
        String body = "{\"usage\":{\"total_tokens\":50,\"input_tokens\":10,\"output_tokens\":25}}";
        double cost = CostCalculator.computeCostUsd(body, COST_PER_TOKEN, DEFAULT_COST);
        assertEquals(50 * COST_PER_TOKEN, cost, 1e-12);
    }

    /**
     * An image model reports a token count like any other Google model, and pricing it by those
     * tokens charges a fraction of a cent for a four-cent image. Every such call then drags down
     * the published coin price, and the whole catalogue ends up sold below what it costs to serve.
     */
    @Test
    void imageModelsArePricedPerCallDespiteReportingTokens() {
        ModelPricing pricing = ModelPricing.defaults(0.000002, 0.001);
        String body = "{\"modelVersion\":\"gemini-2.5-flash-image\","
                + "\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":1290}}";

        double cost = CostCalculator.computeCostUsd("google", body, pricing);

        assertEquals(0.039, cost, 1e-9,
                "an image is billed per image, whatever its response says about tokens");
    }

    /** Google's text models keep their token pricing — the flat charge is for image models alone. */
    @Test
    void textModelsKeepTheirTokenPricing() {
        ModelPricing pricing = ModelPricing.defaults(0.000002, 0.001);
        String body = "{\"modelVersion\":\"gemini-2.5-flash\","
                + "\"usageMetadata\":{\"promptTokenCount\":1000,\"candidatesTokenCount\":1000}}";

        double cost = CostCalculator.computeCostUsd("google", body, pricing);

        assertEquals((1000 * 0.30 + 1000 * 2.50) / 1_000_000.0, cost, 1e-9);
    }
}
