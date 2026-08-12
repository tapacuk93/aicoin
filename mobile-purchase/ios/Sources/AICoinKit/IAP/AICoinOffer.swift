import Foundation

/// Derives the **product-id prefix** an app's IAP products use from its bundle ID.
///
/// These are not always the same string. Per CONTRACT.md's IAP section, Apple in-app purchase
/// product IDs may contain only alphanumerics, underscores and periods — no hyphen — so Learn It,
/// whose real bundle ID is `com.tarasmaslov.learn-it`, has to name its products
/// `com.tarasmaslov.learnit.aicoin.*`. Matching product IDs against a raw `Bundle.main
/// .bundleIdentifier` therefore finds nothing at all for that app; every lookup has to go through
/// this derivation instead.
public enum AICoinProductID {
    /// Apple's allowed product-id alphabet: alphanumerics, underscore, period.
    private static let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._"))

    /// `com.tarasmaslov.learn-it` → `com.tarasmaslov.learnit`; already-legal ids pass through
    /// unchanged. An empty bundle ID stays empty, which callers treat as "don't filter".
    public static func prefix(forBundleID bundleID: String) -> String {
        String(String.UnicodeScalarView(bundleID.unicodeScalars.filter { allowed.contains($0) }))
    }
}

/// The current offer, per CONTRACT.md's "The current offer" section: the single coin amount every
/// app is selling right now, and the fixed price point that sells it.
///
/// The offer's `coins` is what a purchase credits — deliberately *not* the `coins` on the
/// `AICoinPackage` catalog entry for the same product, which under this model is only a record of
/// what that product used to be sold as.
public struct AICoinOffer: Decodable, Sendable, Equatable {
    /// How many AICoin this purchase grants.
    public let coins: Int
    /// The price-point tier (`small`/`medium`/`large`/`xl`) — display-irrelevant, useful for logs.
    public let tier: String
    /// The fixed USD price of that point. Display `Product.displayPrice` instead where StoreKit
    /// has resolved the product: it is localized and authoritative, this is not.
    public let usdPrice: Double
    /// Every app's product ID at this price point — one per app; pick yours with `productID(forBundleID:)`.
    public let productIds: [String]
    /// When the operator set this offer.
    public let setAt: Date?

    enum CodingKeys: String, CodingKey {
        case coins, tier
        case usdPrice = "usd_price"
        case productIds = "product_ids"
        case setAt = "set_at"
    }

    public init(coins: Int, tier: String, usdPrice: Double, productIds: [String], setAt: Date? = nil) {
        self.coins = coins
        self.tier = tier
        self.usdPrice = usdPrice
        self.productIds = productIds
        self.setAt = setAt
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        coins = try container.decode(Int.self, forKey: .coins)
        tier = try container.decodeIfPresent(String.self, forKey: .tier) ?? ""
        usdPrice = try container.decodeIfPresent(Double.self, forKey: .usdPrice) ?? 0
        productIds = try container.decodeIfPresent([String].self, forKey: .productIds) ?? []
        // Epoch milliseconds on the wire, matching every other timestamp in the contract.
        let millis = try container.decodeIfPresent(Double.self, forKey: .setAt)
        setAt = millis.map { Date(timeIntervalSince1970: $0 / 1000) }
    }

    /// This app's product ID for the offer, or nil if the offer doesn't include one for it (an app
    /// whose products were dropped from the catalog — its paywall must then show nothing to buy
    /// rather than trying to charge another app's product).
    public func productID(forBundleID bundleID: String) -> String? {
        let prefix = AICoinProductID.prefix(forBundleID: bundleID)
        guard !prefix.isEmpty else { return productIds.first }
        return productIds.first { $0.hasPrefix(prefix) }
    }
}

/// `GET /iap/offer` → `{"offer": {...}}`, or `{"offer": null}` when nothing is on sale.
struct AICoinOfferResponse: Decodable {
    let offer: AICoinOffer?
}

/// `POST /iap/offer/check` → the offer as it stands right now, plus the `offerId` pinning that
/// coin amount for `expiresIn` seconds so a purchase started against it credits what was shown.
public struct AICoinPinnedOffer: Sendable, Equatable {
    public let offer: AICoinOffer
    public let offerID: String
    public let expiresIn: Int

    public init(offer: AICoinOffer, offerID: String, expiresIn: Int) {
        self.offer = offer
        self.offerID = offerID
        self.expiresIn = expiresIn
    }
}

struct AICoinOfferCheckResponse: Decodable {
    let offer: AICoinOffer?
    let offerID: String?
    let expiresIn: Int?

    enum CodingKeys: String, CodingKey {
        case offer
        case offerID = "offer_id"
        case expiresIn = "expires_in"
    }
}
