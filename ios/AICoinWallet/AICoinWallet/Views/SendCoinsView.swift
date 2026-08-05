import SwiftUI

/// Mirrors wallet.html's "Send coins" card — the entire buy/sell mechanism
/// is just a live-signed peer transfer, no external payment rail.
struct SendCoinsView: View {
    let keys: WalletKeys
    let onSent: () -> Void

    @State private var toAddress = ""
    @State private var amountText = ""
    @State private var message = ""
    @State private var isError = false
    @State private var isSending = false

    var body: some View {
        WalletCard(title: "Send coins") {
            Text("To address").font(.caption).foregroundColor(WalletTheme.muted)
            TextField("recipient's 64-char address", text: $toAddress)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .padding(8)
                .background(WalletTheme.background)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.border))

            Text("Amount").font(.caption).foregroundColor(WalletTheme.muted)
            TextField("0.10", text: $amountText)
                .keyboardType(.decimalPad)
                .padding(8)
                .background(WalletTheme.background)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.border))

            PrimaryButton(title: "Send", disabled: isSending) {
                send()
            }

            StatusMessage(text: message, isError: isError)
        }
    }

    private func send() {
        let to = toAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let amount = Double(amountText), amount > 0, !to.isEmpty else {
            message = "Enter a recipient address and a positive amount."
            isError = true
            return
        }
        isSending = true
        message = "Sending..."
        isError = false
        Task {
            defer { isSending = false }
            do {
                try await ProxyAPI.transfer(keys: keys, to: to, amount: amount)
                message = "Sent \(formatNumber(amount)) aicoin to \(to)."
                isError = false
                toAddress = ""
                amountText = ""
                onSent()
            } catch {
                message = error.localizedDescription
                isError = true
            }
        }
    }
}
