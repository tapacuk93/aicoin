package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * Pure-function pieces of the IAP coin-package list stored as a single JSON array in the Redis
 * string {@code aicoin:iap-packages}, per CONTRACT.md's "Coin packages" section: rendering the
 * config seed list to that JSON shape, validating an admin-submitted replacement list ({@code
 * POST /admin/iap/packages}), and looking up one package by {@code product_id} out of the raw
 * JSON (used by {@code POST /wallet/api/redeem-iap} to find how many coins a purchased product is
 * worth). No I/O, no Redis dependency — unit-testable on its own, same style as {@link
 * PriceCalculator}.
 */
final class IapPackages {

    /**
     * The three client apps' real bundle ids, per CONTRACT.md's "Coin packages" section — every
     * IAP product id is one of these bundle ids plus {@code .aicoin.<tier>}. {@code POST
     * /wallet/api/redeem-iap} rejects any verified transaction whose {@code bundleId} isn't one
     * of these, regardless of whether its signature is otherwise valid.
     */
    private static final Set<String> KNOWN_BUNDLE_IDS = Set.of(
            "com.tarasmaslov.infiniteairadio",
            "com.tarasmaslov.alllanguageslearner",
            "com.tarasmaslov.learn-it");

    private IapPackages() {
    }

    static boolean isKnownBundleId(String bundleId) {
        return bundleId != null && KNOWN_BUNDLE_IDS.contains(bundleId);
    }

    /** One coin package: an Apple product id, the aicoin it grants, and a display-only USD price hint. */
    static final class Entry {
        private final String productId;
        private final int coins;
        private final double usdPriceHint;

        Entry(String productId, int coins, double usdPriceHint) {
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

    /** Outcome of {@link #validate}: either a validated entry list, or a specific, safe-to-return-to-the-client error message. */
    static final class ValidationResult {
        private final boolean valid;
        private final List<Entry> entries;
        private final String error;

        private ValidationResult(boolean valid, List<Entry> entries, String error) {
            this.valid = valid;
            this.entries = entries;
            this.error = error;
        }

        static ValidationResult valid(List<Entry> entries) {
            return new ValidationResult(true, entries, null);
        }

        static ValidationResult invalid(String error) {
            return new ValidationResult(false, null, error);
        }

        boolean isValid() {
            return valid;
        }

        List<Entry> getEntries() {
            return entries;
        }

        String getError() {
            return error;
        }
    }

    /** Renders the config's {@code iap.packages} seed list to the same JSON array shape {@link #toJson} produces, for lazily seeding {@code aicoin:iap-packages} on first read. */
    static String seedJson(List<IapPackageConfig> configPackages) {
        List<Entry> entries = new ArrayList<>(configPackages.size());
        for (IapPackageConfig p : configPackages) {
            entries.add(new Entry(p.getProductId(), p.getCoins(), p.getUsdPriceHint()));
        }
        return toJson(entries);
    }

    /** {@code [{"product_id":"...","coins":N,"usd_price_hint":N}, ...]} — the exact shape {@code GET /iap/packages} wraps as its {@code packages} field and {@code aicoin:iap-packages} stores raw. */
    static String toJson(List<Entry> entries) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Entry e : entries) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("{\"product_id\":").append(jsonString(e.getProductId()))
                    .append(",\"coins\":").append(e.getCoins())
                    .append(",\"usd_price_hint\":").append(formatNumber(e.getUsdPriceHint()))
                    .append("}");
        }
        return sb.append("]").toString();
    }

    /**
     * Validates a {@code POST /admin/iap/packages} request body: must be a JSON array, every
     * entry a JSON object with a non-empty {@code product_id} string and a positive integer
     * {@code coins}. {@code usd_price_hint} is optional (defaults to {@code 0} if absent/not a
     * number) since it's informational display copy only, never something correctness depends on.
     */
    @SuppressWarnings("unchecked")
    static ValidationResult validate(String bodyJson) {
        Object parsed;
        try {
            parsed = new Yaml().load(bodyJson);
        } catch (Exception e) {
            return ValidationResult.invalid("invalid JSON body");
        }
        if (!(parsed instanceof List)) {
            return ValidationResult.invalid("expected a JSON array of packages");
        }
        List<Entry> entries = new ArrayList<>();
        for (Object item : (List<Object>) parsed) {
            if (!(item instanceof Map)) {
                return ValidationResult.invalid("every package must be a JSON object");
            }
            Map<?, ?> map = (Map<?, ?>) item;
            Object productId = map.get("product_id");
            Object coins = map.get("coins");
            Object usdPriceHint = map.get("usd_price_hint");
            if (!(productId instanceof String) || ((String) productId).isEmpty()) {
                return ValidationResult.invalid("every package needs a non-empty product_id");
            }
            if (!(coins instanceof Number) || !isPositiveInteger((Number) coins)) {
                return ValidationResult.invalid("every package needs a positive integer coins amount");
            }
            double priceHint = (usdPriceHint instanceof Number) ? ((Number) usdPriceHint).doubleValue() : 0.0;
            entries.add(new Entry((String) productId, ((Number) coins).intValue(), priceHint));
        }
        return ValidationResult.valid(entries);
    }

    /**
     * Looks up one package by {@code product_id} inside the raw JSON array as stored in {@code
     * aicoin:iap-packages} (the same shape {@link #toJson} produces) — used by {@code POST
     * /wallet/api/redeem-iap} to find how many coins a verified purchase's {@code productId} is
     * worth, and to reject a {@code productId} that isn't currently sold.
     */
    @SuppressWarnings("unchecked")
    static Optional<Entry> findByProductId(String packagesJson, String productId) {
        Object parsed;
        try {
            parsed = new Yaml().load(packagesJson);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!(parsed instanceof List)) {
            return Optional.empty();
        }
        for (Object item : (List<Object>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) item;
            Object candidateId = map.get("product_id");
            Object coins = map.get("coins");
            if (candidateId instanceof String && candidateId.equals(productId) && coins instanceof Number) {
                Object usdPriceHint = map.get("usd_price_hint");
                double priceHint = (usdPriceHint instanceof Number) ? ((Number) usdPriceHint).doubleValue() : 0.0;
                return Optional.of(new Entry((String) candidateId, ((Number) coins).intValue(), priceHint));
            }
        }
        return Optional.empty();
    }

    private static boolean isPositiveInteger(Number n) {
        double d = n.doubleValue();
        return d > 0 && d == Math.rint(d);
    }

    private static String formatNumber(double d) {
        if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
