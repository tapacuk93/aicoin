package com.aicoin.proxy;

/**
 * One entry of the {@code iap.packages} config seed list (application.yaml), per CONTRACT.md's
 * "AICoin pricing (IAP packages)" section: an Apple in-app-purchase product id, how many aicoin it
 * grants, and a display-only USD price hint (the actual charged price always comes from whatever
 * App Store Connect has configured for that product id — Apple, not this server, collects payment).
 * Used only to lazily seed {@code aicoin:iap-packages} in Redis on first read; see {@link
 * IapPackages}.
 */
final class IapPackageConfig {

    private final String productId;
    private final int coins;
    private final double usdPriceHint;

    IapPackageConfig(String productId, int coins, double usdPriceHint) {
        this.productId = productId;
        this.coins = coins;
        this.usdPriceHint = usdPriceHint;
    }

    String getProductId() {
        return productId;
    }

    int getCoins() {
        return coins;
    }

    double getUsdPriceHint() {
        return usdPriceHint;
    }
}
