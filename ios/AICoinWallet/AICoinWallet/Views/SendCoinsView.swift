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
    @State private var isScanning = false
    @State private var recipient: ProxyAPI.Reputation?
    @State private var recipientLookupFailed = false

    var body: some View {
        WalletCard(title: "Send coins") {
            Text("To address").font(.caption).foregroundColor(WalletTheme.muted)
            HStack(spacing: 8) {
                TextField("recipient's 64-char address", text: $toAddress)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .padding(8)
                    .background(WalletTheme.background)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.border))
                Button {
                    isScanning = true
                } label: {
                    Image(systemName: "qrcode.viewfinder").font(.title3)
                }
                .accessibilityIdentifier("scanAddressButton")
                .accessibilityLabel("Scan a wallet address")
            }

            // Who you are about to pay, before you pay them. A rating of nought is not an
            // accusation — it means this wallet has no history, which is what a wallet made an hour
            // ago to take one payment looks like.
            if let recipient {
                RatingView(rating: recipient.rating, reasons: recipient.reasons)
            } else if recipientLookupFailed {
                Text("could not look up that wallet's standing")
                    .font(.caption2)
                    .foregroundColor(WalletTheme.muted)
            }

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
        .sheet(isPresented: $isScanning) {
            QRScannerView(
                onScan: { scanned in
                    // Whatever was scanned goes into the field for the user to see, never straight
                    // into a transfer: a QR is somebody else's data until it has been read.
                    toAddress = scanned
                    isScanning = false
                    lookUpRecipient()
                },
                onCancel: { isScanning = false })
        }
        .onChange(of: toAddress) { _, _ in
            lookUpRecipient()
        }
    }

    /// Fetches the recipient's standing once the address looks like one. Failure is silent about
    /// the wallet and loud about the failure: an unreachable proxy must not read as a clean record.
    private func lookUpRecipient() {
        let address = toAddress.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard address.count == 64 else {
            recipient = nil
            recipientLookupFailed = false
            return
        }
        Task {
            do {
                let standing = try await ProxyAPI.fetchReputation(address: address)
                await MainActor.run {
                    recipient = standing
                    recipientLookupFailed = false
                }
            } catch {
                await MainActor.run {
                    recipient = nil
                    recipientLookupFailed = true
                }
            }
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
