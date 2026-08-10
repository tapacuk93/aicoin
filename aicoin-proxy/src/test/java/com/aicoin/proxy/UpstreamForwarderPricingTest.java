package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Pins the decode step {@link CostCalculator} depends on.
 *
 * <p>This is the regression that made every recorded price wrong in production. Clients send
 * {@code Accept-Encoding: gzip} by default — URLSession does, so every call from the apps did —
 * the provider compresses, the proxy forwards the bytes untouched, and the cost calculator was
 * handed gzip. No {@code usage} object can be parsed out of that, so every single call fell back
 * to {@code defaultCostUsdPerCall}. Measured against production: an Anthropic call of 16 tokens
 * recorded $0.001000 compressed and $0.000032 uncompressed.
 *
 * <p>It stayed invisible because nothing was obviously broken — {@code GET /price} answered, the
 * number just happened to be the default every time. `CostCalculatorTest` covers the parsing and
 * passed throughout; the bug was in what the parser was being fed.
 */
class UpstreamForwarderPricingTest {

    private static final double COST_PER_TOKEN = 0.000002;
    private static final double DEFAULT_COST = 0.001;
    /** The shape Anthropic actually returns, trimmed to what pricing reads. */
    private static final String ANTHROPIC_BODY =
            "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":6}}";

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static HttpHeaders headersWith(String contentEncoding) {
        HttpHeaders headers = new DefaultHttpHeaders();
        if (contentEncoding != null) {
            headers.set(HttpHeaderNames.CONTENT_ENCODING, contentEncoding);
        }
        return headers;
    }

    @Test
    void gzippedResponseIsPricedFromItsTokensNotTheFlatDefault() throws IOException {
        byte[] compressed = gzip(ANTHROPIC_BODY);
        String decoded = UpstreamForwarder.decodedForPricing(headersWith("gzip"), compressed);

        double cost = CostCalculator.computeCostUsd(decoded, COST_PER_TOKEN, DEFAULT_COST);
        assertEquals(16 * COST_PER_TOKEN, cost, 1e-12,
                "a gzipped body must be decoded before pricing, or every call records the default");
        assertTrue(cost < DEFAULT_COST, "16 tokens should cost far less than one flat default call");
    }

    @Test
    void uncompressedResponseIsUnaffected() {
        String decoded = UpstreamForwarder.decodedForPricing(
                headersWith(null), ANTHROPIC_BODY.getBytes(StandardCharsets.UTF_8));
        assertEquals(16 * COST_PER_TOKEN,
                CostCalculator.computeCostUsd(decoded, COST_PER_TOKEN, DEFAULT_COST), 1e-12);
    }

    @Test
    void bodyThatIsNotActuallyCompressedFallsBackToRawBytes() {
        // A mislabelled or already-decoded body must not take the response down
        // with it — pricing degrades to the old behaviour instead.
        String decoded = UpstreamForwarder.decodedForPricing(
                headersWith("gzip"), ANTHROPIC_BODY.getBytes(StandardCharsets.UTF_8));
        assertEquals(ANTHROPIC_BODY, decoded);
    }

    @Test
    void unparseableBodyStillPricesAtTheDefault() throws IOException {
        String decoded = UpstreamForwarder.decodedForPricing(headersWith("gzip"), gzip("not json at all"));
        assertEquals(DEFAULT_COST, CostCalculator.computeCostUsd(decoded, COST_PER_TOKEN, DEFAULT_COST), 1e-12);
    }
}
