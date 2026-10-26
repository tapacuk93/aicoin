import SwiftUI

/// A wallet's standing out of five, with what it is made of.
///
/// The reasons are not decoration. A bare number reads as a verdict on somebody's honesty, and this
/// is not that: it is how much a wallet has to lose. Nought means "this wallet has never done
/// anything" — which is not an accusation and not a clean bill either, and is exactly what a
/// throwaway made to take one payment looks like.
struct StandingView: View {
    let standing: ProxyAPI.Reputation?

    var body: some View {
        List {
            if let standing {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Stars(rating: standing.rating, size: .title2)
                        Text(Standing.headline(for: standing))
                            .font(.headline)
                            .foregroundStyle(Standing.colour(for: standing.rating))
                            .accessibilityIdentifier("ratingHeadline")
                    }
                    .padding(.vertical, 6)
                } footer: {
                    Text("A rating measures how much a wallet has to lose — history, AI use, real purchases, established counterparties and age — not whether it's honest. It's what others see before they accept coins from you.")
                }

                Section("Why") {
                    ForEach(standing.reasons, id: \.self) { reason in
                        Text(reason.prefix(1).uppercased() + reason.dropFirst())
                    }
                }

                Section("Record") {
                    if let calls = standing.calls { LabeledContent("AI Calls", value: calls.formatted()) }
                    if let purchases = standing.purchases {
                        LabeledContent("Coin Purchases", value: purchases.formatted())
                    }
                    if let total = standing.counterparties {
                        LabeledContent("Wallets Dealt With",
                                       value: "\(total) (\(standing.solid_counterparties ?? 0) established)")
                    }
                    if let owed = standing.owed, owed > 0 {
                        LabeledContent("Owed", value: "\(Coins.format(owed)) \(Coins.symbol)")
                    }
                    if let first = standing.first_seen, first > 0 {
                        LabeledContent("Oldest Record",
                                       value: Date(timeIntervalSince1970: TimeInterval(first) / 1000)
                                           .formatted(date: .abbreviated, time: .omitted))
                    }
                }
            } else {
                ContentUnavailableView("Standing Unavailable", systemImage: "star.slash",
                                       description: Text("Couldn't reach the AICoin server."))
            }
        }
        .navigationTitle("Standing")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The recipient's standing in one glance, inside the Send form.
struct StandingSummary: View {
    let standing: ProxyAPI.Reputation

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Stars(rating: standing.rating)
                Text(Standing.headline(for: standing))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Standing.colour(for: standing.rating))
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("ratingHeadline")
            ForEach(standing.reasons, id: \.self) { reason in
                Text(reason)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }
}

struct Stars: View {
    let rating: Int
    var size: Font = .caption

    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<5, id: \.self) { index in
                Image(systemName: index < rating ? "star.fill" : "star")
                    .foregroundStyle(index < rating ? Standing.colour(for: rating) : Color.secondary.opacity(0.5))
            }
        }
        .font(size)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(rating) out of 5 stars")
    }
}

enum Standing {
    static func colour(for rating: Int) -> Color {
        switch rating {
        case 0: return .red
        case 1, 2: return .orange
        default: return .green
        }
    }

    static func headline(for standing: ProxyAPI.Reputation) -> String {
        switch standing.rating {
        case 0 where standing.reasons.contains(where: { $0.contains("double-spend") }):
            return "Double-spend on record"
        case 0: return "No history — receiving from it is a risk"
        case 1: return "In debt"
        case 2: return "Little to go on"
        case 3, 4: return "Some history"
        default: return "Established"
        }
    }
}
