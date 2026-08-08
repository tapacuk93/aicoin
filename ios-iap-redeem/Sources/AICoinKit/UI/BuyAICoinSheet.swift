import SwiftUI

/// The "Buy AICoin" sheet: a dynamic, server-driven list of coin packages (via `IAPManager`) with
/// a prominent, literal statement of the exchange rate CONTRACT.md enforces server-side ("1
/// aicoin is worth 1 paid AI call — enforced, not just a tagline"), plus a secondary "Send /
/// Receive" tab for the peer-transfer feature. This is the surface an app should present when
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
public struct BuyAICoinSheet: View {
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
                Picker("", selection: $selectedTab) {
                    ForEach(Tab.allCases) { tab in
                        Text(tab.rawValue).tag(tab)
                    }
                }
                .pickerStyle(.segmented)
                .padding([.horizontal, .top])

                switch selectedTab {
                case .buy:
                    buyTab
                case .transfer:
                    SendReceiveView(identity: identity, walletClient: walletClient, walletStore: walletStore)
                }
            }
            .navigationTitle("AICoin")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .task { await iapManager.loadPackages() }
        }
    }

    private var buyTab: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("1 AICoin = 1 AI API call")
                .font(.title3.bold())
                .padding(.horizontal)
            Text("Buy AICoin to keep generating — no personal API keys needed.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.horizontal)

            if let balance = walletStore.balance {
                Text("Current balance: \(balance.formatted(.number.precision(.fractionLength(0...1)))) AICoin")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .padding(.horizontal)
            }

            if iapManager.isLoading && iapManager.packages.isEmpty {
                Spacer()
                ProgressView()
                Spacer()
            } else if iapManager.packages.isEmpty {
                Spacer()
                Text("No AICoin packages are available right now.")
                    .foregroundStyle(.secondary)
                Spacer()
            } else {
                List(iapManager.packages) { package in
                    packageRow(package)
                }
                .listStyle(.plain)
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
                    Text("≈ \(package.coins) AI calls")
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
    }

    private func buy(_ package: AICoinPackage) async {
        purchasingProductId = package.productId
        errorMessage = nil
        defer { purchasingProductId = nil }
        do {
            let outcome = try await iapManager.purchase(package, address: identity.address)
            switch outcome {
            case .success:
                await walletStore.refresh()
            case .pending, .userCancelled:
                break
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
