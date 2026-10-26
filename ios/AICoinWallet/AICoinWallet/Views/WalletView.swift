import SwiftUI
import UIKit

/// The open wallet: balance, the three things done most (send, receive, scan), and everything else
/// one row away.
struct WalletView: View {
    @ObservedObject var store: WalletStore
    let keys: WalletKeys
    @StateObject private var model: WalletModel

    /// What is presented over the wallet. One item rather than a flag per sheet, so a scan can
    /// hand straight over to what it found.
    enum Sheet: Identifiable {
        case send(to: String)
        case receive
        case scan
        case authorise(id: String)

        var id: String {
            switch self {
            case .send: return "send"
            case .receive: return "receive"
            case .scan: return "scan"
            case .authorise(let id): return "authorise-\(id)"
            }
        }
    }

    @State private var sheet: Sheet?
    @State private var claim: ClaimState = .idle
    @State private var claimMessage: ClaimMessage?
    @State private var confirmingRemoval = false
    @State private var scanProblem: String?

    enum ClaimState { case idle, claiming }

    struct ClaimMessage: Identifiable {
        let id = UUID()
        let title: String
        let detail: String
    }

    init(store: WalletStore, keys: WalletKeys) {
        self.store = store
        self.keys = keys
        _model = StateObject(wrappedValue: WalletModel(keys: keys))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    balanceHeader
                    actions
                }
                .listRowSeparator(.hidden)

                if let left = model.freeClaimsLeft, left > 0 {
                    faucetSection(left: left)
                }

                activitySection

                Section {
                    NavigationLink {
                        PriceView(current: model.price)
                    } label: {
                        LabeledContent {
                            Text(model.price.map { Coins.price($0.price_usd) } ?? "—")
                        } label: {
                            Label("Price", systemImage: "chart.xyaxis.line")
                        }
                    }
                    NavigationLink {
                        StandingView(standing: model.standing)
                    } label: {
                        LabeledContent {
                            if let standing = model.standing {
                                Stars(rating: standing.rating)
                            }
                        } label: {
                            Label("Your Standing", systemImage: "star.leadinghalf.filled")
                        }
                    }
                }

                Section {
                    NavigationLink {
                        AuthorisedServicesView(keys: keys)
                    } label: {
                        Label("Authorised Services", systemImage: "checkmark.shield")
                    }
                    NavigationLink {
                        APITokensView(keys: keys)
                    } label: {
                        Label("API Tokens", systemImage: "key.horizontal")
                    }
                } header: {
                    Text("Access")
                } footer: {
                    Text("Let apps and scripts make AI calls billed to this wallet, and take that back.")
                }

                Section {
                    NavigationLink {
                        PrivateKeyView(keys: keys)
                    } label: {
                        Label("Back Up Private Key", systemImage: "lock.doc")
                    }
                    Button(role: .destructive) {
                        confirmingRemoval = true
                    } label: {
                        Label("Remove Wallet from iPhone", systemImage: "trash")
                    }
                    .foregroundStyle(.red)
                }
            }
            .navigationTitle("Wallet")
            .refreshable { await model.refresh() }
            .task { await model.refresh() }
            .accessibilityIdentifier("walletScreen")
            .sheet(item: $sheet) { sheet in
                switch sheet {
                case .send(let to):
                    SendView(model: model, initialRecipient: to) {
                        Task { await model.refresh() }
                    }
                case .receive:
                    ReceiveView(address: keys.address)
                case .scan:
                    ScannerSheet(title: "Scan Code") { scanned in
                        route(scanned)
                    }
                case .authorise(let id):
                    AuthorisationRequestView(keys: keys, id: id)
                }
            }
            .alert(item: $claimMessage) { message in
                Alert(title: Text(message.title), message: Text(message.detail))
            }
            .alert("Unrecognised Code", isPresented: Binding(
                get: { scanProblem != nil }, set: { if !$0 { scanProblem = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(scanProblem ?? "")
            }
            .confirmationDialog("Remove this wallet from this iPhone?", isPresented: $confirmingRemoval,
                                titleVisibility: .visible) {
                Button("Remove Wallet", role: .destructive) { store.useADifferentWallet() }
            } message: {
                Text("The coins stay with the wallet, but you'll need its private key to open it again. Make sure you've backed it up.")
            }
        }
    }

    // MARK: Balance

    private var balanceHeader: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Balance")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(model.balance.map(Coins.format) ?? "—")
                    .font(.system(size: 44, weight: .semibold, design: .rounded))
                    .contentTransition(.numericText())
                    .animation(.default, value: model.balance)
                    .accessibilityIdentifier("balanceValue")
                Text(Coins.symbol)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            if let usd = model.usdValue {
                Text("≈ \(Coins.usd(usd))")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else if model.balanceFailed {
                Label("Couldn't reach the AICoin server", systemImage: "wifi.exclamationmark")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Text(keys.address)
                .font(.caption.monospaced())
                .foregroundStyle(.tertiary)
                .lineLimit(1)
                .truncationMode(.middle)
                .accessibilityIdentifier("addressLabel")
                .contextMenu {
                    Button("Copy Address", systemImage: "doc.on.doc") {
                        UIPasteboard.general.string = keys.address
                    }
                }
                .padding(.top, 2)
        }
        .padding(.vertical, 4)
    }

    private var actions: some View {
        HStack {
            ActionButton(title: "Send", systemImage: "arrow.up") { sheet = .send(to: "") }
                .accessibilityIdentifier("sendButton")
            ActionButton(title: "Receive", systemImage: "arrow.down") { sheet = .receive }
                .accessibilityIdentifier("receiveButton")
            ActionButton(title: "Scan", systemImage: "qrcode.viewfinder") { sheet = .scan }
                .accessibilityIdentifier("scanButton")
        }
        .padding(.vertical, 6)
    }

    // MARK: Activity

    /// The last few entries, with the rest one tap away — as Wallet shows a card's latest.
    private var activitySection: some View {
        Section {
            if let transactions = model.transactions {
                if transactions.isEmpty {
                    Text("No activity yet")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(transactions.prefix(4)) { TransactionRow(transaction: $0, showsDate: true) }
                    NavigationLink("See All Activity") { HistoryView(model: model) }
                        .accessibilityIdentifier("seeAllActivity")
                }
            } else if model.transactionsFailed {
                Label("Couldn't load activity", systemImage: "wifi.exclamationmark")
                    .foregroundStyle(.secondary)
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        } header: {
            Text("Recent Activity")
        }
    }

    // MARK: Faucet

    /// Shown only while the shared pool has claims left: a button that can only ever answer
    /// "the pool is empty" is not a feature.
    private func faucetSection(left: Int) -> some View {
        Section {
            Button {
                claimFreeCoins()
            } label: {
                HStack {
                    Label("Claim Free Coins", systemImage: "gift")
                    Spacer()
                    if claim == .claiming { ProgressView() }
                }
            }
            .disabled(claim == .claiming)
            .accessibilityIdentifier("claimBtn")
        } footer: {
            Text("Once an hour per wallet, while the shared pool lasts. \(left) \(left == 1 ? "claim" : "claims") left in the pool.")
                .accessibilityIdentifier("freeCoinsRemainingValue")
        }
    }

    private func claimFreeCoins() {
        claim = .claiming
        Task {
            defer { claim = .idle }
            do {
                switch try await ProxyAPI.claim(keys: keys) {
                case .granted(let amount):
                    claimMessage = ClaimMessage(title: "Coins Claimed",
                                                detail: "\(Coins.format(amount)) \(Coins.symbol) added to your wallet.")
                case .poolExhausted:
                    claimMessage = ClaimMessage(title: "Pool Empty",
                                                detail: "The shared free-coin pool has run out.")
                case .cooldown(let next):
                    let when = next.map { $0.formatted(.relative(presentation: .named)) } ?? "later"
                    claimMessage = ClaimMessage(title: "Not Yet",
                                                detail: "This wallet can claim again \(when).")
                }
            } catch {
                claimMessage = ClaimMessage(title: "Couldn't Claim", detail: error.localizedDescription)
            }
            await model.refresh()
        }
    }

    // MARK: Scanning

    /// A scanned code is either somebody's address (pay them) or a service asking to be
    /// authorised. Anything else is said to be unrecognised rather than guessed at.
    private func route(_ scanned: String) {
        let text = scanned.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let next: Sheet
        if Coins.isAddress(text) {
            next = .send(to: text)
        } else {
            let id = AuthorisedServicesView.identifier(in: scanned)
            guard !id.isEmpty else {
                sheet = nil
                scanProblem = "That code is neither a wallet address nor a service asking for access."
                return
            }
            next = .authorise(id: id)
        }
        sheet = nil
        // Presented after the scanner has gone: replacing a sheet in the same update drops the
        // second presentation.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { sheet = next }
    }
}

/// A round icon over a caption — the Send / Receive / Scan row.
struct ActionButton: View {
    let title: String
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: systemImage)
                    .font(.title3.weight(.semibold))
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(.tint.opacity(0.15)))
                Text(title)
                    .font(.footnote)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(title)
    }
}
