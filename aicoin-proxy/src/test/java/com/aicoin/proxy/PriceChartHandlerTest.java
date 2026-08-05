package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PriceChartHandlerTest {

    @Test
    void parsePointsParamDefaultsWhenMissing() {
        assertEquals(60, PriceChartHandler.parsePointsParam("/price/history"));
    }

    @Test
    void parsePointsParamHonorsAValidValue() {
        assertEquals(30, PriceChartHandler.parsePointsParam("/price/history?points=30"));
    }

    @Test
    void parsePointsParamClampsBelowTheMinimum() {
        assertEquals(2, PriceChartHandler.parsePointsParam("/price/history?points=1"));
        assertEquals(2, PriceChartHandler.parsePointsParam("/price/history?points=0"));
        assertEquals(2, PriceChartHandler.parsePointsParam("/price/history?points=-5"));
    }

    @Test
    void parsePointsParamClampsAboveTheMaximum() {
        assertEquals(500, PriceChartHandler.parsePointsParam("/price/history?points=100000"));
    }

    @Test
    void parsePointsParamFallsBackOnGarbage() {
        assertEquals(60, PriceChartHandler.parsePointsParam("/price/history?points=not-a-number"));
    }
}
