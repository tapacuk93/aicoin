import SwiftUI

/// Mirrors wallet.html's `#walletScreen`: balance/price/address, the free
/// coin faucet, send coins, and API token issuance — all in one scroll view.
struct WalletView: View {
    @ObservedObject var store: WalletStore
    let keys: WalletKeys

    @State private var balance: Double?
    @State private var priceUsd: Double?
    @State private var freeCoinsRemaining: Int?
    @State private var showBackup = false
    @State private var standing: ProxyAPI.Reputation?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                balanceCard
                privateKeyCard
                FaucetSectionView(keys: keys, freeCoinsRemaining: $freeCoinsRemaining, onClaimed: { refreshBalance() })
                SendCoinsView(keys: keys, onSent: { refreshBalance() })
                APITokensView(keys: keys)
            }
            .padding(20)
        }
        .background(WalletTheme.background)
        .accessibilityIdentifier("walletScreen")
        .task {
            refreshBalance()
            refreshPrice()
            refreshFreeCoinsRemaining()
        }
    }

    private var balanceCard: some View {
        WalletCard(title: "Balance") {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(balance.map(formatNumber) ?? "—")
                    .font(.system(size: 34, weight: .bold))
                    .accessibilityIdentifier("balanceValue")
                Text("aicoin").font(.subheadline).foregroundColor(WalletTheme.muted)
            }
            Text("1 aicoin \u{2248} $\(priceUsd.map { String(format: "%.6f", $0) } ?? "—")")
                .font(.caption)
                .foregroundColor(WalletTheme.muted)
            Text("your address (safe to share — used to receive coins):")
                .font(.caption2)
                .foregroundColor(WalletTheme.muted)
            // Shown as a code and as text: the code is for scanning, the text is so anybody can
            // check that the code says what it claims to.
            QRCodeView(text: keys.address)
                .frame(maxWidth: .infinity, alignment: .center)
            Text(keys.address)
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .accessibilityIdentifier("addressLabel")
            // Your own standing, because it is what the person you are about to receive from will
            // be shown about you.
            if let standing {
                RatingView(rating: standing.rating, reasons: standing.reasons, compact: true)
            }
            Button("use a different wallet") {
                store.useADifferentWallet()
            }
            .font(.caption)
            .foregroundColor(WalletTheme.muted)
        }
    }

    private var privateKeyCard: some View {
        WalletCard(title: "Private key") {
            DisclosureGroup("Show / re-save backup", isExpanded: $showBackup) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Never share this. Anyone with it controls this wallet.")
                        .font(.caption2)
                        .padding(8)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.danger))
                    Text(keys.backupBlob)
                        .font(.system(.caption2, design: .monospaced))
                        .textSelection(.enabled)
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(WalletTheme.background)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.border))
                }
                .padding(.top, 8)
            }
            .font(.caption)
            .foregroundColor(WalletTheme.muted)
        }
    }

    private func refreshBalance() {
        Task {
            balance = try? await ProxyAPI.fetchBalance(address: keys.address)
            standing = try? await ProxyAPI.fetchReputation(address: keys.address)
        }
    }

    private func refreshPrice() {
        Task {
            priceUsd = try? await ProxyAPI.fetchPrice().price_usd
        }
    }

    private func refreshFreeCoinsRemaining() {
        Task {
            freeCoinsRemaining = try? await ProxyAPI.fetchFreeCoinsAvailable()
        }
    }
}

func formatNumber(_ value: Double) -> String {
    if value == value.rounded() {
        return String(Int(value))
    }
    return String(value)
}
