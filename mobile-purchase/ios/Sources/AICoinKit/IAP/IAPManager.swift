import Foundation
import StoreKit

/// Errors specific to the purchase flow, distinct from `AICoinError` (which covers the
/// AI-proxy/wallet-management server calls).
public enum AICoinPurchaseError: Error, LocalizedError, Sendable {
    case productNotFound(String)
    case verificationFailed
    /// Nothing is on sale right now — the operator has closed sales, or this app has no product
    /// at the offer's price point. The paywall must not open Apple's purchase sheet.
    case offerUnavailable
    /// The pre-purchase re-check came back with a different amount than the one displayed. Nothing
    /// was charged; show the new amount and let the user decide again.
    case offerChanged(from: Int, to: Int)
    /// The purchase itself succeeded (StoreKit has a verified transaction) but crediting the
    /// wallet failed — network failure or a non-200 from `/wallet/api/redeem-iap`. The underlying
    /// transaction is deliberately **not** finished in this case (see `IAPManager.purchase`), so
    /// StoreKit will re-deliver it for another redemption attempt.
    case redeemFailed(underlying: Error)

    public var errorDescription: String? {
        switch self {
        case .productNotFound(let id):
            return "AICoin package \(id) isn't available for purchase right now."
        case .verificationFailed:
            return "Apple could not verify this purchase."
        case .redeemFailed:
            return "Your purchase went through, but we couldn't credit your AICoin wallet yet. It will be retried automatically."
        case .offerUnavailable:
            return "AICoin isn't available to buy right now. Please try again later."
        case .offerChanged(_, let to):
            return "The current offer just changed to \(to) AICoin. Nothing was charged — tap buy again to take it."
        }
    }
}

/// Remembers which pinned offer a purchase was started against, keyed by StoreKit transaction id,
/// so a transaction StoreKit redelivers after an app restart still redeems at the amount the user
/// was shown (see `IAPManager.startObservingUnfinishedTransactions`).
///
/// Deliberately tiny and lossy: entries expire locally well after the server-side pin does, and
/// losing one only means falling back to the live offer — the same thing that happens for a client
/// that never pinned at all. `UserDefaults` is the right store precisely because nothing here is
/// sensitive or worth a Keychain round-trip.
/// `@unchecked Sendable` because `UserDefaults` isn't formally `Sendable` while being documented
/// as thread-safe — and this store genuinely has to cross isolation, since the redelivery observer
/// reads it from a detached task.
struct OfferPinStore: @unchecked Sendable {
    private static let key = "aicoin.offerPins"
    /// Comfortably longer than the server's 15-minute pin TTL — no point keeping what it will refuse.
    private static let maxAge: TimeInterval = 60 * 60
    private static let maxEntries = 32

    private let defaults: UserDefaults

    init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    func record(offerID: String, forTransactionID transactionID: String) {
        var entries = pruned()
        entries[transactionID] = [offerID, String(Date().timeIntervalSince1970)]
        if entries.count > Self.maxEntries {
            // Oldest first — a bounded store that drops the least useful entry under pressure.
            let sorted = entries.sorted { timestamp($0.value) < timestamp($1.value) }
            for (key, _) in sorted.prefix(entries.count - Self.maxEntries) {
                entries.removeValue(forKey: key)
            }
        }
        defaults.set(entries, forKey: Self.key)
    }

    func offerID(forTransactionID transactionID: String) -> String? {
        pruned()[transactionID]?.first
    }

    func clear(transactionID: String) {
        var entries = pruned()
        entries.removeValue(forKey: transactionID)
        defaults.set(entries, forKey: Self.key)
    }

    private func pruned() -> [String: [String]] {
        let stored = defaults.dictionary(forKey: Self.key) as? [String: [String]] ?? [:]
        let cutoff = Date().timeIntervalSince1970 - Self.maxAge
        return stored.filter { timestamp($0.value) >= cutoff }
    }

    private func timestamp(_ entry: [String]) -> TimeInterval {
        entry.count > 1 ? (TimeInterval(entry[1]) ?? 0) : 0
    }
}

/// Outcome of a single `purchase(_:address:)` call.
public enum AICoinPurchaseOutcome: Sendable, Equatable {
    case success(newBalance: Double)
    case pending
    case userCancelled
}

/// Drives the consumable-IAP purchase flow described in CONTRACT.md's "IAP: buying aicoin with
/// real money" section, using StoreKit 2 (`Product`, `Transaction`, `Transaction
/// .jwsRepresentation`) end to end:
///
/// 1. `loadPackages()` fetches `GET /iap/packages`, filters to the calling app's own bundle ID
///    prefix, and resolves each surviving `product_id` to a StoreKit `Product` via
///    `Product.products(for:)`.
/// 2. `purchase(_:address:)` runs `Product.purchase()`, verifies the resulting transaction, POSTs
///    its `jwsRepresentation` to `/wallet/api/redeem-iap` to credit the wallet, and **only then**
///    calls `Transaction.finish()` — never on a network/redeem failure, so StoreKit keeps
///    re-delivering the transaction (via `Transaction.updates`) until it's actually been credited.
/// 3. `startObservingUnfinishedTransactions(address:)` should be called once at app launch to pick
///    up exactly those still-unfinished transactions from a previous launch (app killed mid-flow,
///    a redeem call that failed, etc.) and retry crediting them.
///
/// `@MainActor`-isolated since its `@Published` properties drive SwiftUI directly (`BuyAICoinSheet`).
@MainActor
public final class IAPManager: ObservableObject {
    @Published public private(set) var packages: [AICoinPackage] = []
    @Published public private(set) var products: [String: Product] = [:]
    @Published public private(set) var isLoading = false
    @Published public private(set) var lastError: Error?

    /// The single amount on sale right now (`GET /iap/offer`), or nil when sales are closed or
    /// `loadOffer()` hasn't run. This is what a paywall shows; `offerProduct` is what it charges.
    @Published public private(set) var offer: AICoinOffer?
    /// The StoreKit product backing `offer` for *this* app, resolved during `loadOffer()`.
    @Published public private(set) var offerProduct: Product?

    private let walletClient: WalletClient
    private let bundleIDPrefix: String
    private let eventBus: AICoinEventBus
    private let pinStore: OfferPinStore
    private var updatesTask: Task<Void, Never>?

    /// - Parameters:
    ///   - walletClient: Used for `GET /iap/packages` and `POST /wallet/api/redeem-iap`.
    ///   - bundleIDPrefix: Defaults to the running app's own `Bundle.main.bundleIdentifier`; pass
    ///     an explicit value in tests or previews.
    ///   - eventBus: Where `.purchaseCredited` is published so a balance badge elsewhere refreshes.
    public init(
        walletClient: WalletClient = WalletClient(),
        bundleIDPrefix: String = Bundle.main.bundleIdentifier ?? "",
        eventBus: AICoinEventBus = .shared,
        pinDefaults: UserDefaults = .standard
    ) {
        self.walletClient = walletClient
        self.bundleIDPrefix = bundleIDPrefix
        self.eventBus = eventBus
        self.pinStore = OfferPinStore(defaults: pinDefaults)
    }

    deinit {
        updatesTask?.cancel()
    }

    /// Fetches the server-configured packages, narrows them to this app's own product IDs, and
    /// resolves each to a StoreKit `Product` (for live, localized pricing via `displayPrice`).
    /// Safe to call repeatedly (e.g. every time the paywall sheet opens) — packages can change
    /// server-side at any time per CONTRACT.md.
    public func loadPackages() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let all = try await walletClient.iapPackages()
            let mine = all.filtered(byBundleIDPrefix: bundleIDPrefix)
            packages = mine
            guard !mine.isEmpty else {
                products = [:]
                return
            }
            let storeProducts = try await Product.products(for: mine.map(\.productId))
            products = Dictionary(uniqueKeysWithValues: storeProducts.map { ($0.id, $0) })
        } catch {
            lastError = error
        }
    }

    /// Fetches the current offer (`GET /iap/offer`) and resolves this app's product for it. Safe
    /// to call repeatedly — the offer changes server-side at any time, so a paywall should call
    /// this every time it opens, exactly like `loadPackages()`.
    public func loadOffer() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let current = try await walletClient.currentOffer()
            offer = current
            guard let current, let productID = current.productID(forBundleID: bundleIDPrefix) else {
                offerProduct = nil
                return
            }
            offerProduct = try await Product.products(for: [productID]).first
        } catch {
            lastError = error
        }
    }

    /// Buys whatever is currently on offer, crediting `address`.
    ///
    /// Re-checks the offer against the server first (`POST /iap/offer/check`) and purchases what
    /// *that* returns, not the possibly-stale `offer` the paywall is displaying — so a user who
    /// left the sheet open across an offer change is charged for, and credited with, what is
    /// actually on sale at the moment they tap buy. The check's pin travels through to redemption,
    /// which is what makes the amount they saw at that instant the amount they get.
    ///
    /// Throws `AICoinPurchaseError.offerUnavailable` when sales have closed, and
    /// `.offerChanged(from:to:)` when the re-check came back with a different coin amount than was
    /// displayed — the caller should show the new amount and let the user decide, rather than
    /// silently charging for something they didn't agree to.
    public func purchaseCurrentOffer(address: String, confirmedCoins: Int? = nil) async throws -> AICoinPurchaseOutcome {
        guard let pinned = try await walletClient.checkOffer() else {
            offer = nil
            offerProduct = nil
            throw AICoinPurchaseError.offerUnavailable
        }
        if let confirmedCoins, confirmedCoins != pinned.offer.coins {
            // Refresh what's displayed before handing back, so the retry the user is about to be
            // asked for is against the new number.
            offer = pinned.offer
            if let productID = pinned.offer.productID(forBundleID: bundleIDPrefix) {
                offerProduct = try? await Product.products(for: [productID]).first
            }
            throw AICoinPurchaseError.offerChanged(from: confirmedCoins, to: pinned.offer.coins)
        }
        guard let productID = pinned.offer.productID(forBundleID: bundleIDPrefix) else {
            throw AICoinPurchaseError.offerUnavailable
        }
        let product: Product
        if let cached = offerProduct, cached.id == productID {
            product = cached
        } else {
            guard let resolved = try await Product.products(for: [productID]).first else {
                throw AICoinPurchaseError.productNotFound(productID)
            }
            product = resolved
            offerProduct = resolved
        }
        offer = pinned.offer
        return try await run(product: product, address: address, offerID: pinned.offerID)
    }

    /// Purchases `package` and, on success, credits `address`'s wallet before finishing the
    /// transaction. `address` is the buyer's own wallet address (`WalletIdentity.address`) — the
    /// destination `to_user_id` for `/wallet/api/redeem-iap`.
    public func purchase(_ package: AICoinPackage, address: String) async throws -> AICoinPurchaseOutcome {
        let product = try await resolvedProduct(for: package.productId)
        return try await run(product: product, address: address, offerID: nil)
    }

    /// The StoreKit product for an id, loading it first if the catalog isn't there yet.
    ///
    /// A paywall raised the instant a wallet runs dry opens *before* `loadPackages` has come back
    /// — it is opened by the empty-balance error itself, not by a user who has been sitting on the
    /// screen — so the first tap on a package found `products` still empty and failed with
    /// `productNotFound`. By the second tap the load had landed and the same tap worked, which is
    /// exactly the "first press errors, second press works" a reader sees, and why it never
    /// happens when the sheet is opened with coins already in hand.
    ///
    /// Waiting for the load in flight is the fix rather than disabling the buttons until it
    /// finishes: the tap is a perfectly good instruction either way, and it should be honoured
    /// rather than refused for being early.
    private func resolvedProduct(for productId: String) async throws -> Product {
        if let product = products[productId] { return product }
        await loadPackages()
        guard let product = products[productId] else {
            throw AICoinPurchaseError.productNotFound(productId)
        }
        return product
    }

    /// The shared purchase → verify → redeem → finish sequence behind both `purchase(_:address:)`
    /// and `purchaseCurrentOffer(address:)`; `offerID` is the pin to redeem against, if any.
    private func run(product: Product, address: String, offerID: String?) async throws -> AICoinPurchaseOutcome {
        let result = try await product.purchase()
        switch result {
        case .success(let verification):
            // `jwsRepresentation` lives on the `VerificationResult` wrapper itself (available
            // regardless of verified/unverified status), not on the unwrapped `Transaction` —
            // must be read before/alongside `checkVerified` unwraps it.
            let jws = verification.jwsRepresentation
            let transaction = try Self.checkVerified(verification)
            // Persist the pin against this transaction id *before* attempting to redeem: if the
            // app dies between the purchase and a successful redeem, StoreKit redelivers the
            // transaction on a later launch and `startObservingUnfinishedTransactions` needs the
            // pin to still credit what the user was shown.
            if let offerID {
                pinStore.record(offerID: offerID, forTransactionID: String(transaction.id))
            }
            do {
                let redeemed = try await walletClient.redeemIAP(to: address, signedTransaction: jws, offerID: offerID)
                await transaction.finish()
                pinStore.clear(transactionID: String(transaction.id))
                eventBus.events.send(.purchaseCredited(newBalance: redeemed.balance))
                return .success(newBalance: redeemed.balance)
            } catch {
                // Deliberately not calling transaction.finish() here: the purchase is real and
                // StoreKit owns redelivery of unfinished transactions, so the right move on a
                // failed redeem is to let it come back through Transaction.updates and retry, not
                // to finish (and thus lose track of) a transaction that was never actually
                // credited to the wallet.
                throw AICoinPurchaseError.redeemFailed(underlying: error)
            }
        case .pending:
            return .pending
        case .userCancelled:
            return .userCancelled
        @unknown default:
            return .pending
        }
    }

    /// Call once at app launch (as soon as the wallet address is known) to resume crediting any
    /// transaction that was purchased but never finished — see the type-level doc comment. Keeps
    /// listening for the lifetime of the returned task; cancel it (or let `deinit` cancel it) to
    /// stop.
    public func startObservingUnfinishedTransactions(address: String) {
        updatesTask?.cancel()
        let client = walletClient
        let bus = eventBus
        let pins = pinStore
        updatesTask = Task.detached(priority: .background) {
            for await update in Transaction.updates {
                let jws = update.jwsRepresentation
                guard let transaction = try? Self.checkVerified(update) else { continue }
                // A pin recorded before the app died still names what the user was shown. The
                // server ignores it once it has expired, falling back to the live offer, so
                // sending a stale one is harmless.
                let offerID = pins.offerID(forTransactionID: String(transaction.id))
                do {
                    let redeemed = try await client.redeemIAP(to: address, signedTransaction: jws, offerID: offerID)
                    await transaction.finish()
                    pins.clear(transactionID: String(transaction.id))
                    bus.events.send(.purchaseCredited(newBalance: redeemed.balance))
                } catch {
                    // Leave unfinished; StoreKit will offer it again on a future launch/update.
                }
            }
        }
    }

    nonisolated static func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .unverified:
            throw AICoinPurchaseError.verificationFailed
        case .verified(let safe):
            return safe
        }
    }
}
