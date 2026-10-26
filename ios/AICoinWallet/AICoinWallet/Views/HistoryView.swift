import SwiftUI
import UIKit

/// Every entry in the wallet's log, grouped by day.
struct HistoryView: View {
    @ObservedObject var model: WalletModel

    var body: some View {
        List {
            if let transactions = model.transactions, !transactions.isEmpty {
                ForEach(Self.days(of: transactions), id: \.day) { group in
                    Section(group.day.formatted(.dateTime.weekday(.wide).day().month(.wide))) {
                        ForEach(group.entries) { TransactionRow(transaction: $0) }
                    }
                }
                Section {
                } footer: {
                    Text("The ledger keeps each wallet's last 200 entries.")
                }
            } else if model.transactions != nil {
                ContentUnavailableView("No Activity Yet", systemImage: "clock",
                                       description: Text("Coins you receive, send and spend on AI will appear here."))
            } else if model.transactionsFailed {
                ContentUnavailableView("Couldn't Load Activity", systemImage: "wifi.exclamationmark",
                                       description: Text("Pull to try again."))
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Activity")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await model.refresh() }
    }

    private static func days(of transactions: [ProxyAPI.Transaction]) -> [(day: Date, entries: [ProxyAPI.Transaction])] {
        let calendar = Calendar.current
        var groups: [(day: Date, entries: [ProxyAPI.Transaction])] = []
        for transaction in transactions {
            let day = calendar.startOfDay(for: transaction.date)
            if groups.last?.day == day {
                groups[groups.count - 1].entries.append(transaction)
            } else {
                groups.append((day, [transaction]))
            }
        }
        return groups
    }
}

/// One ledger entry: what it was, who or what with, and how it moved the balance.
struct TransactionRow: View {
    let transaction: ProxyAPI.Transaction
    var showsDate = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: kind.symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(kind.colour)
                .frame(width: 36, height: 36)
                .background(Circle().fill(kind.colour.opacity(0.15)))
            VStack(alignment: .leading, spacing: 2) {
                Text(kind.title)
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text((kind.isCredit ? "+" : "−") + Coins.format(transaction.amount))
                    .font(.body.monospacedDigit().weight(.medium))
                    .foregroundStyle(kind.isCredit ? Color.green : Color.primary)
                if let after = transaction.balance_after {
                    Text(Coins.format(after))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.tertiary)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .contextMenu {
            if let counterparty = transaction.counterparty {
                Button("Copy Address", systemImage: "doc.on.doc") {
                    UIPasteboard.general.string = counterparty
                }
            }
        }
    }

    private var subtitle: String {
        let time = showsDate
            ? transaction.date.formatted(date: .abbreviated, time: .shortened)
            : transaction.date.formatted(date: .omitted, time: .shortened)
        guard let detail = kind.detail(transaction) else { return time }
        return "\(detail) · \(time)"
    }

    private var kind: Kind { Kind(transaction.type) }

    struct Kind {
        let title: String
        let symbol: String
        let colour: Color
        let isCredit: Bool
        let detail: (ProxyAPI.Transaction) -> String?

        init(_ type: String) {
            func provider(_ t: ProxyAPI.Transaction) -> String? { t.provider.map(Kind.providerName) }
            switch type {
            case "claim":
                (title, symbol, colour, isCredit, detail) = ("Free Coins", "gift.fill", .green, true, { _ in nil })
            case "transfer_in":
                (title, symbol, colour, isCredit, detail) = ("Received", "arrow.down", .green, true,
                    { $0.counterparty.map { "From \(Coins.shortAddress($0))" } })
            case "transfer_out":
                (title, symbol, colour, isCredit, detail) = ("Sent", "arrow.up", .blue, false,
                    { $0.counterparty.map { "To \(Coins.shortAddress($0))" } })
            case "debit":
                (title, symbol, colour, isCredit, detail) = ("AI Call", "sparkles", .purple, false, provider)
            case "debit_settlement":
                (title, symbol, colour, isCredit, detail) = ("AI Call", "sparkles", .purple, false, { t in
                    let name = provider(t)
                    guard let owed = t.owed, owed > 0 else { return name }
                    return [name, "\(Coins.format(owed)) owed"].compactMap { $0 }.joined(separator: " · ")
                })
            case "refund":
                (title, symbol, colour, isCredit, detail) = ("Refund", "arrow.uturn.backward", .green, true,
                    { t in provider(t).map { "\($0) call failed" } })
            case "iap":
                (title, symbol, colour, isCredit, detail) = ("Purchase", "cart.fill", .green, true, { _ in "App Store" })
            case "admin_credit":
                (title, symbol, colour, isCredit, detail) = ("Credit", "plus", .green, true, { $0.reason })
            default:
                (title, symbol, colour, isCredit, detail) = (type.replacingOccurrences(of: "_", with: " ").capitalized,
                                                              "circle", .gray, false, { _ in nil })
            }
        }

        static func providerName(_ id: String) -> String {
            switch id {
            case "openai": return "OpenAI"
            case "anthropic": return "Anthropic"
            case "google", "googletts": return "Google"
            case "elevenlabs": return "ElevenLabs"
            case "stability": return "Stability AI"
            case "mistral": return "Mistral"
            case "cohere": return "Cohere"
            case "kimi": return "Kimi"
            default: return id.capitalized
            }
        }
    }
}
