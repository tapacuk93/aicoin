import SwiftUI

/// Mirrors wallet.html's "Free coin faucet" card.
struct FaucetSectionView: View {
    let keys: WalletKeys
    @Binding var freeCoinsRemaining: Int?
    let onClaimed: () -> Void

    @State private var message = ""
    @State private var isError = false
    @State private var isClaiming = false

    var body: some View {
        WalletCard(title: "Free coin faucet") {
            Text("Up to 10 free coins per wallet per hour, while the shared pool lasts.")
                .font(.caption)
                .foregroundColor(WalletTheme.muted)
            Text("\(freeCoinsRemaining.map(String.init) ?? "\u{2026}") free coins left in the pool")
                .font(.caption)
                .foregroundColor(WalletTheme.muted)
                .accessibilityIdentifier("freeCoinsRemainingValue")

            PrimaryButton(title: "Claim free coins", disabled: isClaiming) {
                claim()
            }
            .accessibilityIdentifier("claimBtn")

            StatusMessage(text: message, isError: isError)
        }
    }

    private func claim() {
        isClaiming = true
        message = "Claiming..."
        isError = false
        Task {
            defer { isClaiming = false }
            do {
                let outcome = try await ProxyAPI.claim(keys: keys)
                switch outcome {
                case .granted(let amount):
                    message = "Claimed \(formatNumber(amount)) free aicoin!"
                    isError = false
                    onClaimed()
                case .poolExhausted:
                    message = "The shared free-coins pool is empty."
                    isError = true
                case .cooldown(let nextEligibleAt):
                    message = "Not eligible yet — next free coin at \(nextEligibleAt)"
                    isError = true
                }
            } catch {
                message = error.localizedDescription
                isError = true
            }
            freeCoinsRemaining = try? await ProxyAPI.fetchFreeCoinsAvailable()
        }
    }
}
