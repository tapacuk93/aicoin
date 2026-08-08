import Foundation
import StoreKit

/// Errors specific to the purchase flow, distinct from `AICoinError` (which covers the
/// AI-proxy/wallet-management server calls).
public enum AICoinPurchaseError: Error, LocalizedError, Sendable {
    case productNotFound(String)
    case verificationFailed
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
        }
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

    private let walletClient: WalletClient
    private let bundleIDPrefix: String
    private let eventBus: AICoinEventBus
    private var updatesTask: Task<Void, Never>?

    /// - Parameters:
    ///   - walletClient: Used for `GET /iap/packages` and `POST /wallet/api/redeem-iap`.
    ///   - bundleIDPrefix: Defaults to the running app's own `Bundle.main.bundleIdentifier`; pass
    ///     an explicit value in tests or previews.
    ///   - eventBus: Where `.purchaseCredited` is published so a balance badge elsewhere refreshes.
    public init(
        walletClient: WalletClient = WalletClient(),
        bundleIDPrefix: String = Bundle.main.bundleIdentifier ?? "",
        eventBus: AICoinEventBus = .shared
    ) {
        self.walletClient = walletClient
        self.bundleIDPrefix = bundleIDPrefix
        self.eventBus = eventBus
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

    /// Purchases `package` and, on success, credits `address`'s wallet before finishing the
    /// transaction. `address` is the buyer's own wallet address (`WalletIdentity.address`) — the
    /// destination `to_user_id` for `/wallet/api/redeem-iap`.
    public func purchase(_ package: AICoinPackage, address: String) async throws -> AICoinPurchaseOutcome {
        guard let product = products[package.productId] else {
            throw AICoinPurchaseError.productNotFound(package.productId)
        }

        let result = try await product.purchase()
        switch result {
        case .success(let verification):
            // `jwsRepresentation` lives on the `VerificationResult` wrapper itself (available
            // regardless of verified/unverified status), not on the unwrapped `Transaction` —
            // must be read before/alongside `checkVerified` unwraps it.
            let jws = verification.jwsRepresentation
            let transaction = try Self.checkVerified(verification)
            do {
                let redeemed = try await walletClient.redeemIAP(to: address, signedTransaction: jws)
                await transaction.finish()
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
        updatesTask = Task.detached(priority: .background) {
            for await update in Transaction.updates {
                let jws = update.jwsRepresentation
                guard let transaction = try? Self.checkVerified(update) else { continue }
                do {
                    let redeemed = try await client.redeemIAP(to: address, signedTransaction: jws)
                    await transaction.finish()
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
