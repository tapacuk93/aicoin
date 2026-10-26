import SwiftUI

/// Paying another wallet — the whole of buying and selling here is a live-signed peer transfer,
/// with no other payment rail.
struct SendView: View {
    @ObservedObject var model: WalletModel
    let onSent: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var recipient: String
    @State private var amountText = ""
    @State private var standing: ProxyAPI.Reputation?
    @State private var standingFailed = false
    @State private var isScanning = false
    @State private var isConfirming = false
    @State private var isSending = false
    @State private var error: String?
    @State private var sent = false
    @FocusState private var amountFocused: Bool

    init(model: WalletModel, initialRecipient: String, onSent: @escaping () -> Void) {
        self.model = model
        self.onSent = onSent
        _recipient = State(initialValue: initialRecipient)
    }

    private var address: String {
        recipient.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private var amount: Double? {
        let normalised = amountText.replacingOccurrences(of: ",", with: ".")
        guard let value = Double(normalised), value > 0 else { return nil }
        return value
    }

    /// Why Send is not available yet, if it isn't — said under the form rather than left to be
    /// guessed from a greyed-out button.
    private var problem: String? {
        if !address.isEmpty, !Coins.isAddress(address) { return "An address is 64 characters, 0–9 and a–f." }
        if address == model.keys.address { return "That's this wallet's own address." }
        if let amount, let balance = model.balance, amount > balance { return "That's more than your balance." }
        return nil
    }

    private var canSend: Bool {
        Coins.isAddress(address) && amount != nil && problem == nil && !isSending
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(alignment: .top) {
                        TextField("Wallet address", text: $recipient, axis: .vertical)
                            .font(.body.monospaced())
                            .lineLimit(1...3)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .accessibilityIdentifier("recipientField")
                        Button {
                            isScanning = true
                        } label: {
                            Image(systemName: "qrcode.viewfinder").font(.title3)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Scan a wallet address")
                        .accessibilityIdentifier("scanAddressButton")
                    }
                    if let standing {
                        StandingSummary(standing: standing)
                    } else if standingFailed {
                        Text("Couldn't look up this wallet's standing.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text("To")
                } footer: {
                    // Who you are about to pay, before you pay them. No history is not an
                    // accusation, but it is what a wallet made an hour ago to take one payment
                    // looks like.
                    if standing != nil {
                        Text("A rating measures how much a wallet has to lose, not whether it's honest.")
                    }
                }

                Section {
                    HStack(alignment: .firstTextBaseline) {
                        TextField("0", text: $amountText)
                            .keyboardType(.decimalPad)
                            .font(.system(.title, design: .rounded).weight(.semibold))
                            .focused($amountFocused)
                            .accessibilityIdentifier("amountField")
                        Text(Coins.symbol).foregroundStyle(.secondary)
                    }
                    if let balance = model.balance, balance > 0 {
                        Button("Send Entire Balance") { amountText = Coins.format(balance) }
                    }
                } header: {
                    Text("Amount")
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if let amount, let price = model.price {
                            Text("≈ \(Coins.usd(amount * price.price_usd))")
                        }
                        if let balance = model.balance {
                            Text("Balance: \(Coins.format(balance)) \(Coins.symbol)")
                        }
                    }
                }

                if let problem {
                    Section {
                        Label(problem, systemImage: "exclamationmark.circle")
                            .foregroundStyle(.orange)
                    }
                }
            }
            .navigationTitle("Send")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isSending {
                        ProgressView()
                    } else {
                        Button("Send") { isConfirming = true }
                            .disabled(!canSend)
                            .accessibilityIdentifier("sendConfirmButton")
                    }
                }
            }
            .confirmationDialog(confirmTitle, isPresented: $isConfirming, titleVisibility: .visible) {
                Button("Send \(amount.map(Coins.format) ?? "") \(Coins.symbol)") { send() }
            } message: {
                Text("Transfers can't be undone.")
            }
            .alert("Couldn't Send", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(error ?? "")
            }
            .sheet(isPresented: $isScanning) {
                ScannerSheet(title: "Scan Address") { scanned in
                    // Into the field, where it can be read, never straight into a transfer: a QR
                    // is somebody else's data until it has been looked at.
                    recipient = scanned
                    isScanning = false
                }
            }
            .task(id: address) { await lookUpRecipient() }
            .sensoryFeedback(.success, trigger: sent)
            .onAppear { if !address.isEmpty { amountFocused = true } }
        }
    }

    private var confirmTitle: String {
        "Send \(amount.map(Coins.format) ?? "") \(Coins.symbol) to \(Coins.shortAddress(address))?"
    }

    /// The recipient's standing, once the field holds an address. A failure is said as one: an
    /// unreachable proxy must not read as a clean record.
    private func lookUpRecipient() async {
        standing = nil
        standingFailed = false
        guard Coins.isAddress(address) else { return }
        do {
            let result = try await ProxyAPI.fetchReputation(address: address)
            guard !Task.isCancelled else { return }
            standing = result
        } catch {
            guard !Task.isCancelled else { return }
            standingFailed = true
        }
    }

    private func send() {
        guard let amount else { return }
        isSending = true
        Task {
            defer { isSending = false }
            do {
                try await ProxyAPI.transfer(keys: model.keys, to: address, amount: amount)
                sent = true
                onSent()
                dismiss()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
