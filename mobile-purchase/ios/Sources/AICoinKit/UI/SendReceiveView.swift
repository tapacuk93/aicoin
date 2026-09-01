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
///
/// ## Accessibility identifiers
///
/// Every field and control carries a stable, namespaced `aicoin.sendReceive.*`
/// identifier — see `Identifiers`. Note `address` (this wallet's own address,
/// shown for *receiving*) and `recipient` (the typed destination for *sending*)
/// are deliberately distinct.
public struct SendReceiveView: View {
    /// The stable accessibility identifiers this view exposes. Referenced by
    /// name from host apps' UI tests; treat these strings as API.
    public enum Identifiers {
        /// The view's root container element.
        public static let root = "aicoin.sendReceive"
        /// This wallet's own address, shown under "Receive".
        public static let address = "aicoin.sendReceive.address"
        /// The copy-own-address button.
        public static let copyAddress = "aicoin.sendReceive.copyAddress"
        /// The "send to" destination-address field.
        public static let recipient = "aicoin.sendReceive.recipient"
        /// The amount-to-send field.
        public static let amount = "aicoin.sendReceive.amount"
        /// The submit button (disabled until recipient + amount are valid).
        public static let send = "aicoin.sendReceive.send"
        /// The post-transfer result/error line (absent until a send is tried).
        public static let status = "aicoin.sendReceive.status"
        /// The "transfers unavailable" notice shown in place of the whole form
        /// while `AICoinConfig.isPeerTransferEnabled` is off — the element a
        /// host app's test should assert on to prove transfers stay hidden.
        public static let unavailable = "aicoin.sendReceive.unavailable"
    }

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
        // The gate lives here, in the view itself, and not only in
        // `BuyAICoinSheet`'s tab picker: All Languages Learner and Learn It
        // both present this view straight from their own Settings screens,
        // bypassing that picker entirely, so gating only the picker would have
        // left the transfer form fully reachable in two of the three apps that
        // ship this package. See `AICoinConfig.isPeerTransferEnabled`.
        if AICoinConfig.isPeerTransferEnabled {
            transferForm
        } else {
            unavailableNotice
        }
    }

    /// Shown in place of the form while transfers are off. Deliberately says
    /// something rather than rendering empty: a host app whose Settings row
    /// still points here would otherwise present a blank sheet.
    private var unavailableNotice: some View {
        VStack(spacing: 8) {
            Text("Transfers unavailable")
                .font(.headline)
            Text("Coins can be bought and spent in the app. Sending them between wallets isn't available in this version.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(Identifiers.unavailable)
    }

    private var transferForm: some View {
        Form {
            Section("Receive") {
                Text(identity.address)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                    .accessibilityIdentifier(Identifiers.address)
                Button(didCopyAddress ? "Copied" : "Copy Address") {
                    copyToPasteboard(identity.address)
                    didCopyAddress = true
                }
                .accessibilityIdentifier(Identifiers.copyAddress)
            }

            Section("Send") {
                TextField("Recipient address", text: $recipient)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                    .accessibilityIdentifier(Identifiers.recipient)
                TextField("Amount", text: $amountText)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                    .accessibilityIdentifier(Identifiers.amount)

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
                .accessibilityIdentifier(Identifiers.send)
            }

            if let statusMessage {
                Section {
                    Text(statusMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier(Identifiers.status)
                }
            }
        }
        // `.contain` rather than a bare identifier, for the same reason as
        // `BuyAICoinSheet`'s root: keep the container addressable without its
        // identifier propagating onto unnamed descendants.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(Identifiers.root)
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
            statusMessage = "Sent \(amount.formatted(.number.precision(.fractionLength(0...2)))) aicoins to \(target.prefix(10))…"
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
