import SwiftUI

/// A small coin-balance badge suitable for a main-screen toolbar item. Auto-refreshes on
/// appearance and stays fresh afterwards via `WalletBalanceStore`'s `AICoinEventBus` subscription
/// — no manual refresh call needed at any call site that makes a paid AI call or a purchase.
///
/// ```swift
/// .toolbar {
///     ToolbarItem(placement: .primaryAction) {
///         CoinBalanceBadge(store: walletBalanceStore) { showBuySheet = true }
///     }
/// }
/// ```
///
/// ## Accessibility identifiers
///
/// Carries `aicoin.balanceBadge` on the button itself. Note that a host app
/// applying its own `.accessibilityIdentifier` to this view (as InfiniteAIRadio
/// does) overrides that one, so the *balance number* is additionally exposed
/// two ways that survive any such override: as the button's accessibility
/// **value** (just the number, e.g. `"1234"` — the stable thing to assert on),
/// and inside its accessibility **label** (`"1234 AICoin"`, which is what
/// VoiceOver reads).
public struct CoinBalanceBadge: View {
    /// The stable accessibility identifiers this badge exposes. Referenced by
    /// name from host apps' UI tests; treat these strings as API.
    public enum Identifiers {
        /// The badge button. Overridden if the host app sets its own.
        public static let root = "aicoin.balanceBadge"
        /// The balance text. Buttons merge their children into one
        /// accessibility element on most platforms, so prefer reading the
        /// badge's `.value` (see the type doc) over querying this directly.
        public static let value = "aicoin.balanceBadge.value"
    }

    @ObservedObject private var store: WalletBalanceStore
    private let onTap: (() -> Void)?

    public init(store: WalletBalanceStore, onTap: (() -> Void)? = nil) {
        self.store = store
        self.onTap = onTap
    }

    public var body: some View {
        Button {
            onTap?()
        } label: {
            HStack(spacing: 4) {
                AICoinMark()
                balanceLabel
            }
            .font(.subheadline.weight(.medium))
        }
        .buttonStyle(.plain)
        .disabled(onTap == nil)
        .task { await store.refresh() }
        .accessibilityIdentifier(Identifiers.root)
        .accessibilityLabel(accessibilityText)
        // The bare number, separate from the spoken label, so a UI test can
        // assert an exact seeded balance without parsing prose out of a label
        // whose wording is free to change.
        .accessibilityValue(accessibilityValueText)
    }

    @ViewBuilder
    private var balanceLabel: some View {
        if let balance = store.balance {
            Text(Self.format(balance))
                .accessibilityIdentifier(Identifiers.value)
        } else if store.isLoading {
            ProgressView()
                .controlSize(.mini)
        } else {
            Text("—")
                .foregroundStyle(.secondary)
                .accessibilityIdentifier(Identifiers.value)
        }
    }

    private static func format(_ balance: Double) -> String {
        balance.formatted(.number.precision(.fractionLength(0...1)))
    }

    private var accessibilityText: String {
        guard let balance = store.balance else { return "AICoin balance unavailable" }
        return "\(Self.format(balance)) AICoin"
    }

    private var accessibilityValueText: String {
        store.balance.map(Self.format) ?? ""
    }
}
