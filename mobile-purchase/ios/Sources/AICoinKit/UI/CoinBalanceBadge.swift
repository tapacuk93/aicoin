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
/// and inside its accessibility **label** (`"1234 aicoins"`, which is what
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
            // A tap on a badge showing "—" is, more often than not, someone
            // asking why it says that — so take it as a retry as well as
            // whatever the host wired `onTap` to. Harmless when a balance is
            // already known (`WalletBalanceStore.refresh` is idempotent), and
            // it means the failed-read state is never more than one tap from
            // resolving itself even if every automatic retry has been spent.
            Task { await store.refresh() }
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
                .balanceTextLayout()
                .accessibilityIdentifier(Identifiers.value)
        } else if store.isLoading {
            ProgressView()
                .controlSize(.mini)
        } else {
            Text("—")
                .foregroundStyle(.secondary)
                .balanceTextLayout()
                .accessibilityIdentifier(Identifiers.value)
        }
    }

    private static func format(_ balance: Double) -> String {
        balance.formatted(.number.precision(.fractionLength(0...1)))
    }

    private var accessibilityText: String {
        guard let balance = store.balance else { return "aicoins balance unavailable" }
        return "\(Self.format(balance)) aicoins"
    }

    private var accessibilityValueText: String {
        store.balance.map(Self.format) ?? ""
    }
}

private extension View {
    /// Makes the balance render at its natural width or not at all — never squeezed down to an
    /// ellipsis.
    ///
    /// Host apps put this badge in a toolbar, and a toolbar is the one container that hands its
    /// children less room than they asked for rather than growing. All Languages Learner has five
    /// `.primaryAction` items beside an inline title; the badge is the only one holding free-form
    /// text, so it is the only one with anything to give up, and SwiftUI's default response to a
    /// `Text` that doesn't fit is to truncate it.
    ///
    /// What that looked like: the badge rendered fine at small balances and turned into a bare "…"
    /// next to the coin mark once a purchase made the number wide enough to overflow — reported as
    /// the balance not updating after buying coins, when in fact it had updated and could no longer
    /// be drawn. The accessibility label carried the full number throughout, which is why nothing
    /// reading the hierarchy (tests included) could see it.
    ///
    /// `fixedSize` is what actually fixes it: it makes the text report its ideal width as
    /// non-negotiable, so the toolbar takes the space from its own spacing instead. `lineLimit(1)`
    /// pairs with it to keep an unexpectedly long value on one line rather than growing the bar's
    /// height.
    func balanceTextLayout() -> some View {
        lineLimit(1).fixedSize(horizontal: true, vertical: false)
    }
}
