import Foundation
import Combine

/// Holds the current balance for one address and keeps it fresh by subscribing to
/// `AICoinEventBus` — refreshes automatically after any paid AI call (`.paidCallSucceeded`), any
/// completed purchase (`.purchaseCredited`), and any insufficient-balance signal
/// (`.insufficientBalance`, since the user is likely about to buy more and the badge should track
/// that). `ObservableObject`/`@Published` rather than the newer `Observation` macro (`@Observable`)
/// deliberately — this package's deployment target is iOS 16/macOS 13 (a hard constraint from
/// InfiniteAIRadio's `project.yml`), and `@Observable` requires iOS 17/macOS 14.
@MainActor
public final class WalletBalanceStore: ObservableObject {
    @Published public private(set) var balance: Double?
    @Published public private(set) var isLoading = false
    @Published public private(set) var lastError: Error?

    private let address: String
    private let walletClient: WalletClient
    private var cancellable: AnyCancellable?

    #if DEBUG
    /// Set to enable the debug-only top-up described on `debugAutoReloadWhenEmpty`.
    private let debugAutoReloadIdentity: WalletIdentity?
    /// Guards against a claim/refresh feedback loop, and against firing a second claim
    /// while one is still in flight.
    private var debugReloadInFlight = false
    /// When the faucet last told us "no" (cooldown or an exhausted pool). Re-asking on
    /// every single refresh would hammer the endpoint for an answer that cannot change
    /// for up to an hour.
    private var debugReloadDeclinedAt: Date?
    private static let debugReloadRetryInterval: TimeInterval = 60
    #endif

    public init(address: String, walletClient: WalletClient = WalletClient(), eventBus: AICoinEventBus = .shared) {
        self.address = address
        self.walletClient = walletClient
        #if DEBUG
        self.debugAutoReloadIdentity = nil
        #endif
        cancellable = eventBus.events
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                Task { await self?.refresh() }
            }
    }

    #if DEBUG
    /// Debug-build convenience: keep a test wallet usable by re-claiming the free faucet
    /// automatically whenever the balance runs dry, so a developer testing on a device
    /// isn't stopped by a purchase sheet mid-flow.
    ///
    /// **This exists only in `DEBUG` builds** — the whole initializer is compiled out of
    /// Release, so no App Store build can reach it. That matters: an automatic top-up
    /// shipped to users would be a free-coin farm, and the faucet is real money.
    ///
    /// It grants nothing the user couldn't already get by waiting: it calls the same
    /// `POST /wallet/api/claim` endpoint, so the server's own one-hour per-wallet cooldown
    /// and the shared global pool still apply. When the faucet declines, this backs off
    /// rather than retrying on every refresh.
    ///
    /// - Parameter identity: the wallet's keypair, needed because a claim is live-signed.
    public convenience init(
        debugAutoReloadWhenEmpty identity: WalletIdentity,
        walletClient: WalletClient = WalletClient(),
        eventBus: AICoinEventBus = .shared
    ) {
        self.init(address: identity.address, walletClient: walletClient, eventBus: eventBus,
                  debugAutoReloadIdentity: identity)
    }

    private init(
        address: String,
        walletClient: WalletClient,
        eventBus: AICoinEventBus,
        debugAutoReloadIdentity: WalletIdentity?
    ) {
        self.address = address
        self.walletClient = walletClient
        self.debugAutoReloadIdentity = debugAutoReloadIdentity
        cancellable = eventBus.events
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                Task { await self?.refresh() }
            }
    }
    #endif

    /// Re-fetches the balance from `GET /wallet/api/balance/{address}`. Errors leave the last
    /// known `balance` in place (a stale-but-present number is more useful on screen than nothing)
    /// while recording `lastError` for callers that want to surface it.
    public func refresh() async {
        isLoading = true
        defer { isLoading = false }
        do {
            balance = try await walletClient.balance(address: address)
            lastError = nil
        } catch {
            lastError = error
        }
        #if DEBUG
        await debugAutoReloadIfEmpty()
        #endif
    }

    #if DEBUG
    /// See `init(debugAutoReloadWhenEmpty:...)`. Compiled out of Release entirely.
    ///
    /// Triggers below 1.0 rather than at exactly 0 because 1 aicoin is the cost of a
    /// single call — a wallet holding 0.4 is already unable to do anything.
    private func debugAutoReloadIfEmpty() async {
        guard let identity = debugAutoReloadIdentity,
              let balance,
              balance < 1.0,
              !debugReloadInFlight
        else { return }

        if let declinedAt = debugReloadDeclinedAt,
           Date().timeIntervalSince(declinedAt) < Self.debugReloadRetryInterval {
            return
        }

        debugReloadInFlight = true
        defer { debugReloadInFlight = false }

        do {
            let result = try await walletClient.claimFreeCoins(identity: identity)
            if result.granted {
                debugReloadDeclinedAt = nil
                // Re-read rather than adding the granted amount locally, so the badge
                // shows what the ledger actually holds.
                self.balance = try? await walletClient.balance(address: address)
            } else {
                // Cooldown or an exhausted pool — both mean "not now", so back off.
                debugReloadDeclinedAt = Date()
            }
        } catch {
            debugReloadDeclinedAt = Date()
        }
    }
    #endif
}
