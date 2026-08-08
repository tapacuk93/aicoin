package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Exercises {@link IapPackages}'s pure-function JSON rendering, admin-body validation, and by-product-id lookup. */
class IapPackagesTest {

    // ---- seedJson / toJson ----

    @Test
    void seedJsonRendersConfigListToTheContractShape() {
        List<IapPackageConfig> config = List.of(
                new IapPackageConfig("com.tarasmaslov.infiniteairadio.aicoin.small", 50, 0.99),
                new IapPackageConfig("com.tarasmaslov.infiniteairadio.aicoin.xl", 5000, 44.99));

        String json = IapPackages.seedJson(config);

        assertEquals("[{\"product_id\":\"com.tarasmaslov.infiniteairadio.aicoin.small\",\"coins\":50,\"usd_price_hint\":0.99},"
                + "{\"product_id\":\"com.tarasmaslov.infiniteairadio.aicoin.xl\",\"coins\":5000,\"usd_price_hint\":44.99}]", json);
    }

    @Test
    void toJsonRendersWholeNumberPriceHintsWithoutTrailingZero() {
        String json = IapPackages.toJson(List.of(new IapPackages.Entry("p", 10, 5.0)));
        assertTrue(json.contains("\"usd_price_hint\":5}"), json);
    }

    @Test
    void emptyListRendersAsEmptyJsonArray() {
        assertEquals("[]", IapPackages.toJson(List.of()));
    }

    // ---- validate ----

    @Test
    void validateAcceptsAWellFormedPackagesArray() {
        String body = "[{\"product_id\":\"p.small\",\"coins\":50,\"usd_price_hint\":0.99},"
                + "{\"product_id\":\"p.large\",\"coins\":1000,\"usd_price_hint\":9.99}]";
        IapPackages.ValidationResult result = IapPackages.validate(body);
        assertTrue(result.isValid());
        assertEquals(2, result.getEntries().size());
        assertEquals("p.small", result.getEntries().get(0).getProductId());
        assertEquals(50, result.getEntries().get(0).getCoins());
    }

    @Test
    void validateDefaultsMissingUsdPriceHintToZero() {
        IapPackages.ValidationResult result = IapPackages.validate("[{\"product_id\":\"p\",\"coins\":10}]");
        assertTrue(result.isValid());
        assertEquals(0.0, result.getEntries().get(0).getUsdPriceHint());
    }

    @Test
    void validateRejectsNonArrayBody() {
        assertFalse(IapPackages.validate("{\"product_id\":\"p\",\"coins\":10}").isValid());
        assertFalse(IapPackages.validate("not json at all {{{").isValid());
    }

    @Test
    void validateRejectsMissingOrEmptyProductId() {
        assertFalse(IapPackages.validate("[{\"coins\":10}]").isValid());
        assertFalse(IapPackages.validate("[{\"product_id\":\"\",\"coins\":10}]").isValid());
        assertFalse(IapPackages.validate("[{\"product_id\":5,\"coins\":10}]").isValid());
    }

    @Test
    void validateRejectsMissingZeroNegativeOrNonIntegerCoins() {
        assertFalse(IapPackages.validate("[{\"product_id\":\"p\"}]").isValid());
        assertFalse(IapPackages.validate("[{\"product_id\":\"p\",\"coins\":0}]").isValid());
        assertFalse(IapPackages.validate("[{\"product_id\":\"p\",\"coins\":-5}]").isValid());
        assertFalse(IapPackages.validate("[{\"product_id\":\"p\",\"coins\":10.5}]").isValid());
    }

    @Test
    void validateRejectsWhenAnyEntryIsNotAJsonObject() {
        assertFalse(IapPackages.validate("[{\"product_id\":\"p\",\"coins\":10}, \"oops\"]").isValid());
    }

    // ---- findByProductId ----

    @Test
    void findByProductIdReturnsTheMatchingEntry() {
        String packagesJson = "[{\"product_id\":\"a\",\"coins\":50,\"usd_price_hint\":0.99},"
                + "{\"product_id\":\"b\",\"coins\":200,\"usd_price_hint\":2.99}]";
        Optional<IapPackages.Entry> found = IapPackages.findByProductId(packagesJson, "b");
        assertTrue(found.isPresent());
        assertEquals(200, found.get().getCoins());
    }

    @Test
    void findByProductIdReturnsEmptyForUnknownProductOrMalformedJson() {
        String packagesJson = "[{\"product_id\":\"a\",\"coins\":50}]";
        assertFalse(IapPackages.findByProductId(packagesJson, "unknown").isPresent());
        assertFalse(IapPackages.findByProductId("not json", "a").isPresent());
        assertFalse(IapPackages.findByProductId("{}", "a").isPresent());
    }

    // ---- isKnownBundleId ----

    @Test
    void isKnownBundleIdAcceptsExactlyTheThreeContractApps() {
        assertTrue(IapPackages.isKnownBundleId("com.tarasmaslov.infiniteairadio"));
        assertTrue(IapPackages.isKnownBundleId("com.tarasmaslov.alllanguageslearner"));
        assertTrue(IapPackages.isKnownBundleId("com.tarasmaslov.learn-it"));
    }

    @Test
    void isKnownBundleIdRejectsAnythingElse() {
        assertFalse(IapPackages.isKnownBundleId("com.someone.else"));
        assertFalse(IapPackages.isKnownBundleId(null));
        assertFalse(IapPackages.isKnownBundleId(""));
    }
}
