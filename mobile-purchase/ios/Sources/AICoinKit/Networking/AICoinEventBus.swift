import Foundation
import Combine

/// Fires whenever something happens that should make an on-screen balance stale: a paid AI call
/// went through the proxy, a purchase was credited, or a call came back with insufficient balance
/// (the balance is about to change from whatever the user does next, e.g. buying more). SwiftUI
/// views like `CoinBalanceBadge` subscribe to this instead of every call site having to remember
/// to poke a shared view model — this is what makes "auto-refreshing after any paid call or
/// purchase" (this package's spec) work without every provider adapter knowing about the UI layer.
public enum AICoinEvent: Sendable, Equatable {
    case paidCallSucceeded
    case insufficientBalance(balance: Double?)
    case purchaseCredited(newBalance: Double?)

    /// The balance the server itself reported alongside this event, when it reported one — a
    /// redeem response's post-credit total, or the balance a `402` body named.
    ///
    /// Subscribers should prefer this over waiting on their own read: it is the same number the
    /// next `GET /wallet/api/balance` would return, already in hand and already paid for, so a
    /// balance that is displayable *now* doesn't depend on a second round trip succeeding.
    /// `paidCallSucceeded` carries nothing — the proxy's success path doesn't report a balance —
    /// so it stays a plain "your number is stale, go re-read" signal.
    public var reportedBalance: Double? {
        switch self {
        case .paidCallSucceeded: return nil
        case .insufficientBalance(let balance): return balance
        case .purchaseCredited(let newBalance): return newBalance
        }
    }
}

/// A process-wide bus by default (`.shared`), but every type in this package that publishes or
/// subscribes takes an instance as a constructor parameter rather than reaching for `.shared`
/// directly — tests inject a fresh, private instance so parallel test runs (or repeated runs in
/// the same process) never see events from unrelated tests.
public final class AICoinEventBus: @unchecked Sendable {
    public static let shared = AICoinEventBus()

    public let events = PassthroughSubject<AICoinEvent, Never>()

    public init() {}
}
