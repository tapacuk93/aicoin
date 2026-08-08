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

    public init(address: String, walletClient: WalletClient = WalletClient(), eventBus: AICoinEventBus = .shared) {
        self.address = address
        self.walletClient = walletClient
        cancellable = eventBus.events
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                Task { await self?.refresh() }
            }
    }

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
    }
}
