import SwiftUI

/// The "Buy aicoins" sheet: a dynamic, server-driven list of coin packages (via `IAPManager`) with
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
        /// "Current balance: N aicoins" (absent until a balance is known).
        public static let balance = "aicoin.buySheet.balance"
        /// The purchase-failure message (absent unless a purchase failed).
        public static let error = "aicoin.buySheet.error"
        /// The list of purchasable packages (absent while loading/empty).
        public static let packageList = "aicoin.buySheet.packageList"
        /// Shown instead of `packageList` when the server returned no packages.
        public static let emptyPackages = "aicoin.buySheet.empty"
        /// The single-offer buy button, shown when the server has an offer set.
        public static let offer = "aicoin.buySheet.offer"
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
            .navigationTitle("aicoins")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .accessibilityIdentifier(Identifiers.close)
                }
            }
            .task {
                // Both: the offer is what's displayed when the server has one set, and the
                // package list is the fallback for a server that doesn't yet.
                await iapManager.loadOffer()
                await iapManager.loadPackages()
            }
        }
        // A macOS sheet takes its size from what its content asks for, and this content asks for
        // almost nothing: `List` reports a small ideal height regardless of how many rows it holds,
        // so the sheet opened barely taller than its own title bar. Everything below the balance
        // line was cut off mid-row — the first package was sliced through horizontally and no
        // package could be read, let alone bought, which made the sheet a dead end on the Mac.
        //
        // The vertical squeeze also reached the copy above the list: with no room to wrap, the
        // "A call costs from 1 aicoin…" paragraph collapsed to a single truncated line, so the
        // pricing explanation was unreadable too.
        //
        // Sized here rather than at each call site because the sheet is presented from several
        // places across three apps and none of them should have to know what it needs. iOS is
        // deliberately untouched: a sheet there is already full-height (or detent-driven) and a
        // fixed frame would fight it.
        #if os(macOS)
        .frame(minWidth: 460, idealWidth: 520, minHeight: 560, idealHeight: 640)
        #endif
    }

    private var buyTab: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("aicoins pay for AI calls")
                .font(.title3.bold())
                .padding(.horizontal)
                // The heading is the first thing in the tab and was sitting flush against the
                // top edge, which reads as clipped rather than as a title.
                .padding(.top)
                .accessibilityIdentifier(Identifiers.exchangeRate)
            Text("A call costs from 1 aicoin, more if it's a long one — you're charged what it costs to run, never a subscription and never your own API key.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.horizontal)

            if let balance = walletStore.balance {
                Text("Current balance: \(balance.formatted(.number.precision(.fractionLength(0...1)))) aicoins")
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

            if iapManager.isLoading && iapManager.offer == nil && iapManager.packages.isEmpty {
                Spacer()
                // Centered across the sheet, not left-aligned with the paragraphs above it. The
                // enclosing stack is `.leading` so that the copy reads as a column, but a spinner
                // standing alone in the middle of an otherwise empty sheet belongs in the middle
                // of it — pinned to the left edge it looks like a stalled row rather than the
                // whole sheet loading.
                ProgressView()
                    .frame(maxWidth: .infinity)
                Spacer()
            } else if let offer = iapManager.offer {
                // The offer model: one server-set amount, the same for every app. The package
                // list below is the pre-offer fallback, kept live so an app running against a
                // server with no offer set still has something to sell.
                Spacer()
                offerButton(offer)
                    .frame(maxWidth: .infinity)
                Spacer()
            } else if iapManager.packages.isEmpty {
                Spacer()
                Text("No aicoins packages are available right now.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
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

    private func offerButton(_ offer: AICoinOffer) -> some View {
        Button {
            Task { await buyOffer(offer) }
        } label: {
            VStack(spacing: 6) {
                Text("\(offer.coins) aicoins")
                    .font(.title2.bold())
                if purchasingProductId != nil {
                    ProgressView()
                } else if let product = iapManager.offerProduct {
                    // StoreKit's localized price is authoritative; the offer's usdPrice is the
                    // server's own view of the same fixed price point and only stands in when
                    // StoreKit hasn't resolved the product.
                    Text(product.displayPrice).font(.title3.weight(.semibold))
                } else {
                    Text(offer.usdPrice, format: .currency(code: "USD"))
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 20)
            .contentShape(Rectangle())
        }
        .buttonStyle(.borderedProminent)
        .padding(.horizontal)
        .disabled(purchasingProductId != nil || iapManager.offerProduct == nil)
        .accessibilityIdentifier(Identifiers.offer)
    }

    private func buyOffer(_ offer: AICoinOffer) async {
        purchasingProductId = iapManager.offerProduct?.id ?? offer.tier
        errorMessage = nil
        defer { purchasingProductId = nil }
        do {
            // Passing the displayed amount as `confirmedCoins` is what turns a mid-purchase offer
            // change into a visible "it just changed, tap again" rather than a silent charge for
            // something other than what's on screen.
            let outcome = try await iapManager.purchaseCurrentOffer(
                address: identity.address, confirmedCoins: offer.coins)
            await handle(outcome)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func packageRow(_ package: AICoinPackage) -> some View {
        Button {
            Task { await buy(package) }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(package.coins) aicoins")
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
            await handle(try await iapManager.purchase(package, address: identity.address))
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Shared post-purchase handling for both the single offer and the legacy package rows.
    private func handle(_ outcome: AICoinPurchaseOutcome) async {
        switch outcome {
        case .success(let newBalance):
            // Take the redeem response's own total before reading anything:
            // it is the authoritative post-credit balance, and it arrived on
            // the request that just succeeded. Leaving this to `refresh()`
            // alone made a visibly credited purchase depend on a *second*
            // round trip, which on a flaky connection is how the badge ended
            // up still showing its placeholder after paying.
            walletStore.apply(newBalance)
            // Then refresh, then close: the sheet is usually presented
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
    }
}
