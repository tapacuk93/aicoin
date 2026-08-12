import Foundation

/// One entry from `GET /iap/packages`, per CONTRACT.md's IAP section:
/// `{"product_id":"...","coins":N,"usd_price_hint":N}`. `usdPriceHint` is informational display
/// copy only — the actual charged price always comes from StoreKit's own `Product.displayPrice`,
/// since Apple (not this server) collects payment.
public struct AICoinPackage: Decodable, Sendable, Equatable, Identifiable {
    public let productId: String
    public let coins: Int
    public let usdPriceHint: Double

    public var id: String { productId }

    enum CodingKeys: String, CodingKey {
        case productId = "product_id"
        case coins
        case usdPriceHint = "usd_price_hint"
    }

    public init(productId: String, coins: Int, usdPriceHint: Double) {
        self.productId = productId
        self.coins = coins
        self.usdPriceHint = usdPriceHint
    }
}

struct AICoinPackagesResponse: Decodable {
    let packages: [AICoinPackage]
}

public extension Array where Element == AICoinPackage {
    /// Narrows the full, cross-app `/iap/packages` list down to the subset the calling app can
    /// actually offer, per CONTRACT.md: "Because Apple in-app purchase product IDs are scoped to
    /// one app each, the same four coin tiers exist as separate product IDs per app ... A client
    /// only ever needs the subset whose `product_id` starts with its own bundle ID." An empty
    /// prefix returns every package unfiltered (defensive default — better to show every tier than
    /// silently show none if the bundle ID couldn't be determined).
    ///
    /// The prefix is run through `AICoinProductID.prefix(forBundleID:)` first, because a bundle ID
    /// is not always a legal product-id prefix: Learn It's `com.tarasmaslov.learn-it` has to name
    /// its products `com.tarasmaslov.learnit.*` (Apple forbids the hyphen), so matching the raw
    /// bundle ID against the real server catalog would find nothing and leave that app's paywall
    /// permanently empty.
    func filtered(byBundleIDPrefix prefix: String) -> [AICoinPackage] {
        let normalized = AICoinProductID.prefix(forBundleID: prefix)
        guard !normalized.isEmpty else { return self }
        return filter { $0.productId.hasPrefix(normalized) }
    }
}
