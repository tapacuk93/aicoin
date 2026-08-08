import SwiftUI

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// The peer-transfer tab: shows the wallet's own address for receiving, and a form to send coins
/// to another address — CONTRACT.md's "another way" to acquire/dispose of aicoin besides the
/// faucet and IAP ("This is the *entire* buy/sell mechanism ... 'buying' is just receiving a
/// transfer, 'selling' is sending one"). Uses `WalletClient.transfer`, which live-signs the
/// request with `identity`'s private key per CONTRACT.md's wallet-management auth scheme.
public struct SendReceiveView: View {
    private let identity: WalletIdentity
    private let walletClient: WalletClient
    @ObservedObject private var walletStore: WalletBalanceStore

    @State private var recipient: String = ""
    @State private var amountText: String = ""
    @State private var isSending = false
    @State private var statusMessage: String?
    @State private var didCopyAddress = false

    public init(identity: WalletIdentity, walletClient: WalletClient = WalletClient(), walletStore: WalletBalanceStore) {
        self.identity = identity
        self.walletClient = walletClient
        self.walletStore = walletStore
    }

    public var body: some View {
        Form {
            Section("Receive") {
                Text(identity.address)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                Button(didCopyAddress ? "Copied" : "Copy Address") {
                    copyToPasteboard(identity.address)
                    didCopyAddress = true
                }
            }

            Section("Send") {
                TextField("Recipient address", text: $recipient)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                TextField("Amount", text: $amountText)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif

                Button {
                    Task { await send() }
                } label: {
                    if isSending {
                        ProgressView()
                    } else {
                        Text("Send")
                    }
                }
                .disabled(isSending || recipient.trimmingCharacters(in: .whitespaces).isEmpty || parsedAmount == nil)
            }

            if let statusMessage {
                Section {
                    Text(statusMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var parsedAmount: Double? {
        let value = Double(amountText)
        return (value.map { $0 > 0 }) == true ? value : nil
    }

    private func send() async {
        guard let amount = parsedAmount else { return }
        let target = recipient.trimmingCharacters(in: .whitespaces)
        isSending = true
        statusMessage = nil
        defer { isSending = false }
        do {
            _ = try await walletClient.transfer(to: target, amount: amount, identity: identity)
            statusMessage = "Sent \(amount.formatted(.number.precision(.fractionLength(0...2)))) AICoin to \(target.prefix(10))…"
            recipient = ""
            amountText = ""
            await walletStore.refresh()
        } catch {
            statusMessage = error.localizedDescription
        }
    }

    private func copyToPasteboard(_ string: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = string
        #elseif canImport(AppKit)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(string, forType: .string)
        #endif
    }
}
