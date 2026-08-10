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
