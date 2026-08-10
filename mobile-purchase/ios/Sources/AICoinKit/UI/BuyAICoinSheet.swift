import SwiftUI

/// The "Buy AICoin" sheet: a dynamic, server-driven list of coin packages (via `IAPManager`) with
/// a prominent statement of how coins are spent — metered per call against what the call cost
/// to run, floored at one coin, as CONTRACT.md defines — plus a secondary "Send / Receive" tab
/// for the peer-transfer feature. This is the surface an app should present when
/// `AICoinRouter` throws `AICoinError.insufficientBalance` — e.g.:
///
/// ```swift
/// } catch AICoinError.insufficientBalance {
///     showBuySheet = true
/// }
/// ...
/// .sheet(isPresented: $showBuySheet) {
///     BuyAICoinSheet(iapManager: iapManager, walletStore: walletStore, identity: identity)
/// }
/// ```
///
/// ## Accessibility identifiers
///
/// Every addressable control here carries a stable, namespaced
/// `aicoin.buySheet.*` accessibility identifier so host apps can drive this
/// sheet from a UI test without matching on display copy (which is
/// localized/server-driven and therefore not a stable test anchor). See
/// `Identifiers` below for the full vocabulary. The root uses
/// `.accessibilityElement(children: .contain)` rather than a bare identifier
/// on the container, because a bare `.accessibilityIdentifier` on a non-element
/// container propagates down onto every unnamed descendant — `.contain` keeps
/// the root a single addressable element while leaving children individually
/// queryable.
public struct BuyAICoinSheet: View {
    /// The stable accessibility identifiers this sheet exposes. Referenced by
    /// name from host apps' UI tests; treat these strings as API.
    public enum Identifiers {
        /// The sheet's root container element.
        public static let root = "aicoin.buySheet"
        /// The Buy / Send-Receive segmented control.
        public static let tabPicker = "aicoin.buySheet.tabPicker"
        /// The headline stating how coins are spent.
        public static let exchangeRate = "aicoin.buySheet.exchangeRate"
        /// "Current balance: N AICoin" (absent until a balance is known).
        public static let balance = "aicoin.buySheet.balance"
        /// The purchase-failure message (absent unless a purchase failed).
        public static let error = "aicoin.buySheet.error"
        /// The list of purchasable packages (absent while loading/empty).
        public static let packageList = "aicoin.buySheet.packageList"
        /// Shown instead of `packageList` when the server returned no packages.
        public static let emptyPackages = "aicoin.buySheet.empty"
        /// The toolbar's dismiss button.
        public static let close = "aicoin.buySheet.close"

        /// One package row / buy button, keyed by its App Store product ID.
        public static func package(_ productId: String) -> String {
            "aicoin.buySheet.package.\(productId)"
        }
    }

    public enum Tab: String, CaseIterable, Identifiable {
        case buy = "Buy"
        case transfer = "Send / Receive"
        public var id: String { rawValue }
    }

    @ObservedObject private var iapManager: IAPManager
    @ObservedObject private var walletStore: WalletBalanceStore
    private let identity: WalletIdentity
    private let walletClient: WalletClient

    @Environment(\.dismiss) private var dismiss
    @State private var selectedTab: Tab = .buy
    @State private var purchasingProductId: String?
    @State private var errorMessage: String?

    public init(
        iapManager: IAPManager,
        walletStore: WalletBalanceStore,
        identity: WalletIdentity,
        walletClient: WalletClient = WalletClient()
    ) {
        self.iapManager = iapManager
        self.walletStore = walletStore
        self.identity = identity
        self.walletClient = walletClient
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // A one-tab segmented control is just a mislabelled header, so
                // the picker disappears entirely with the transfer tab rather
                // than sitting there with nothing to switch between. See
                // `AICoinConfig.isPeerTransferEnabled` for why it's off.
                if AICoinConfig.isPeerTransferEnabled {
                    Picker("", selection: $selectedTab) {
                        ForEach(Tab.allCases) { tab in
                            Text(tab.rawValue).tag(tab)
                        }
                    }
                    .pickerStyle(.segmented)
                    .padding([.horizontal, .top])
                    .accessibilityIdentifier(Identifiers.tabPicker)
                }

                switch selectedTab {
                case .buy:
                    buyTab
                case .transfer:
                    // Unreachable while transfers are off — `selectedTab`
                    // starts on `.buy` and nothing can move it without the
                    // picker above — but kept exhaustive rather than
                    // force-unwrapped so re-enabling is a one-line change.
                    if AICoinConfig.isPeerTransferEnabled {
                        SendReceiveView(identity: identity, walletClient: walletClient, walletStore: walletStore)
                    } else {
                        buyTab
                    }
                }
            }
            // `.contain` (not a bare identifier on the VStack) so the root is
            // one addressable element without its identifier leaking down onto
            // every unnamed descendant.
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(Identifiers.root)
            .navigationTitle("AICoin")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .accessibilityIdentifier(Identifiers.close)
                }
            }
            .task { await iapManager.loadPackages() }
        }
    }

    private var buyTab: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("AICoin pays for AI calls")
                .font(.title3.bold())
                .padding(.horizontal)
                .accessibilityIdentifier(Identifiers.exchangeRate)
            Text("A call costs from 1 AICoin, more if it's a long one — you're charged what it costs to run, never a subscription and never your own API key.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.horizontal)

            if let balance = walletStore.balance {
                Text("Current balance: \(balance.formatted(.number.precision(.fractionLength(0...1)))) AICoin")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)
                    .accessibilityIdentifier(Identifiers.balance)
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .padding(.horizontal)
                    .accessibilityIdentifier(Identifiers.error)
            }

            if iapManager.isLoading && iapManager.packages.isEmpty {
                Spacer()
                ProgressView()
                Spacer()
            } else if iapManager.packages.isEmpty {
                Spacer()
                Text("No AICoin packages are available right now.")
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier(Identifiers.emptyPackages)
                Spacer()
            } else {
                List(iapManager.packages) { package in
                    packageRow(package)
                }
                .listStyle(.plain)
                .accessibilityIdentifier(Identifiers.packageList)
            }
        }
    }

    private func packageRow(_ package: AICoinPackage) -> some View {
        Button {
            Task { await buy(package) }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(package.coins) AICoin")
                        .font(.body.weight(.semibold))
                    Text("from \(package.coins) AI calls")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if purchasingProductId == package.productId {
                    ProgressView()
                } else if let product = iapManager.products[package.productId] {
                    Text(product.displayPrice)
                        .font(.body.weight(.semibold))
                } else {
                    Text(package.usdPriceHint, format: .currency(code: "USD"))
                        .foregroundStyle(.secondary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(purchasingProductId != nil)
        // Keyed by product ID so a UI test can address one specific tier
        // without depending on row order or on the server-driven coin counts.
        .accessibilityIdentifier(Identifiers.package(package.productId))
    }

    private func buy(_ package: AICoinPackage) async {
        purchasingProductId = package.productId
        errorMessage = nil
        defer { purchasingProductId = nil }
        do {
            let outcome = try await iapManager.purchase(package, address: identity.address)
            switch outcome {
            case .success:
                // Refresh first, then close: the sheet is usually presented
                // *because* something ran out of coins, so the thing the user
                // came back to — the badge, the segment that 402'd — should
                // already be showing the new balance as the sheet goes away.
                await walletStore.refresh()
                // Bought and credited, so this screen has nothing left to say.
                // Leaving it up made the purchase feel unfinished — the one
                // clear signal of success was a number changing behind a sheet
                // the user then had to dismiss by hand.
                dismiss()
            case .pending, .userCancelled:
                // `.pending` is Ask-to-Buy/SCA — the purchase isn't done and
                // may never be, so the sheet stays; `IAPManager`'s unfinished-
                // transaction observer credits it whenever it does clear.
                // `.userCancelled` means they backed out of Apple's own sheet,
                // which shouldn't take this one down with it.
                break
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
