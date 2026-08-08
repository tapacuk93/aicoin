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
public struct CoinBalanceBadge: View {
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
                Image(systemName: "bitcoinsign.circle.fill")
                    .imageScale(.medium)
                balanceLabel
            }
            .font(.subheadline.weight(.medium))
        }
        .buttonStyle(.plain)
        .disabled(onTap == nil)
        .task { await store.refresh() }
        .accessibilityLabel(accessibilityText)
    }

    @ViewBuilder
    private var balanceLabel: some View {
        if let balance = store.balance {
            Text(balance.formatted(.number.precision(.fractionLength(0...1))))
        } else if store.isLoading {
            ProgressView()
                .controlSize(.mini)
        } else {
            Text("—")
                .foregroundStyle(.secondary)
        }
    }

    private var accessibilityText: String {
        if let balance = store.balance {
            return "\(balance.formatted(.number.precision(.fractionLength(0...1)))) AICoin"
        }
        return "AICoin balance unavailable"
    }
}
