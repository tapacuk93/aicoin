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
}
