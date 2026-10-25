import SwiftUI

/// Every wallet, richest first, a page at a time, and what happened in each.
struct WalletsView: View {
    @EnvironmentObject private var client: AdminClient
    @State private var wallets: [WalletSummary] = []
    @State private var total: Int?
    @State private var priceUSD: Double?
    @State private var search = ""
    @State private var loadingMore = false
    @State private var error: String?

    private let pageSize = 50

    var body: some View {
        NavigationStack {
            List {
                if let total {
                    Text("\(total) wallets").font(.caption).foregroundStyle(.secondary)
                }
                ForEach(wallets) { w in
                    NavigationLink(value: w) {
                        HStack {
                            Text(w.address.shortAddress).font(.body.monospaced())
                            Spacer()
                            VStack(alignment: .trailing) {
                                Text("\(Int(w.balance))").monospacedDigit()
                                if let priceUSD {
                                    Text((w.balance * priceUSD).usd).font(.caption).monospacedDigit()
                                        .foregroundStyle(.secondary)
                                }
                                Text("\(w.transaction_count) tx").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onAppear { if w.id == wallets.last?.id { Task { await loadMore() } } }
                }
                if loadingMore { ProgressView().frame(maxWidth: .infinity) }
                ErrorBanner(message: error)
            }
            .navigationTitle("Wallets")
            .navigationDestination(for: WalletSummary.self) { WalletDetail(wallet: $0, priceUSD: priceUSD) }
            .searchable(text: $search, prompt: "Address")
            .refreshable { await reload() }
            // Runs on appear and again (debounced) whenever the search text changes.
            .task(id: search) {
                if !search.isEmpty { try? await Task.sleep(for: .milliseconds(300)) }
                if Task.isCancelled { return }
                await reload()
            }
        }
    }

    private var hasMore: Bool { total.map { wallets.count < $0 } ?? false }

    private func reload() async {
        error = nil
        if let p = try? await client.price() { priceUSD = p.price_usd }
        do {
            let page = try await client.wallets(offset: 0, limit: pageSize, query: search)
            // An older proxy ignores q and sends everything, so filter here too
            wallets = page.total == nil && !search.isEmpty
                ? page.wallets.filter { $0.address.localizedCaseInsensitiveContains(search) }
                : page.wallets
            total = page.total
        } catch { self.error = error.localizedDescription }
    }

    private func loadMore() async {
        guard hasMore, !loadingMore else { return }
        loadingMore = true
        defer { loadingMore = false }
        do {
            let page = try await client.wallets(offset: wallets.count, limit: pageSize, query: search)
            wallets += page.wallets
            total = page.total
        } catch { self.error = error.localizedDescription }
    }
}

struct WalletDetail: View {
    @EnvironmentObject private var client: AdminClient
    let wallet: WalletSummary
    var priceUSD: Double?
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
                if let priceUSD {
                    LabeledContent("Worth", value: (wallet.balance * priceUSD).usd)
                }
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
