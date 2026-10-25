import SwiftUI

/// Every wallet, richest first, and what happened in each.
struct WalletsView: View {
    @EnvironmentObject private var client: AdminClient
    @State private var wallets: [WalletSummary] = []
    @State private var search = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                ForEach(shown) { w in
                    NavigationLink(value: w) {
                        HStack {
                            Text(w.address.shortAddress).font(.body.monospaced())
                            Spacer()
                            VStack(alignment: .trailing) {
                                Text("\(Int(w.balance))").monospacedDigit()
                                Text("\(w.transaction_count) tx").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                ErrorBanner(message: error)
            }
            .navigationTitle("Wallets")
            .navigationDestination(for: WalletSummary.self) { WalletDetail(wallet: $0) }
            .searchable(text: $search, prompt: "Address")
            .refreshable { await load() }
            .task { await load() }
        }
    }

    private var shown: [WalletSummary] {
        let all = wallets.sorted { $0.balance > $1.balance }
        return search.isEmpty ? all : all.filter { $0.address.localizedCaseInsensitiveContains(search) }
    }

    private func load() async {
        error = nil
        do { wallets = try await client.wallets() } catch { self.error = error.localizedDescription }
    }
}

struct WalletDetail: View {
    @EnvironmentObject private var client: AdminClient
    let wallet: WalletSummary
    @State private var transactions: [Transaction] = []
    @State private var error: String?
    @State private var amount = ""
    @State private var reason = ""
    @State private var confirm = false

    var body: some View {
        List {
            Section("Wallet") {
                Text(wallet.address).font(.caption.monospaced()).textSelection(.enabled)
                LabeledContent("Balance", value: "\(Int(wallet.balance))")
            }
            Section {
                TextField("Coins (negative takes them back)", text: $amount).keyboardType(.numbersAndPunctuation)
                TextField("Reason", text: $reason)
                Button("Credit") { confirm = true }
                    .disabled(Double(amount) == nil || reason.trimmingCharacters(in: .whitespaces).isEmpty)
            } header: { Text("Credit") }
            Section("Transactions") {
                ForEach(Array(transactions.enumerated()), id: \.offset) { _, t in TransactionRow(t: t) }
            }
            ErrorBanner(message: error)
        }
        .navigationTitle(wallet.address.shortAddress)
        .refreshable { await load() }
        .task { await load() }
        .confirmationDialog("Credit \(amount) coins to \(wallet.address.shortAddress)?", isPresented: $confirm,
                            titleVisibility: .visible) {
            Button("Credit") { Task { await credit() } }
        }
    }

    private func load() async {
        error = nil
        do { transactions = try await client.transactions(wallet.address) }
        catch { self.error = error.localizedDescription }
    }

    private func credit() async {
        guard let a = Double(amount) else { return }
        do {
            try await client.credit(address: wallet.address, amount: a, reason: reason)
            amount = ""; reason = ""
            await load()
        } catch { self.error = error.localizedDescription }
    }
}

struct TransactionRow: View {
    let t: Transaction
    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(t.type)
                Text([t.provider, t.reason, t.date?.formatted(date: .abbreviated, time: .shortened)]
                        .compactMap { $0 }.joined(separator: " - "))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if let a = t.amount {
                Text((t.type.hasPrefix("debit") ? "-" : "+") + String(format: "%g", a)).monospacedDigit()
                    .foregroundStyle(t.type.hasPrefix("debit") ? .primary : Color.green)
            }
        }
    }
}

/// The latest movements across every wallet.
struct ActivityView: View {
    @EnvironmentObject private var client: AdminClient
    @State private var entries: [ActivityEntry] = []
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(entries.enumerated()), id: \.offset) { _, e in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(e.address.shortAddress).font(.caption.monospaced()).foregroundStyle(.secondary)
                        TransactionRow(t: e.tx)
                    }
                }
                ErrorBanner(message: error)
            }
            .navigationTitle("Activity")
            .refreshable { await load() }
            .task { await load() }
        }
    }

    private func load() async {
        error = nil
        do { entries = try await client.activity() } catch { self.error = error.localizedDescription }
    }
}
