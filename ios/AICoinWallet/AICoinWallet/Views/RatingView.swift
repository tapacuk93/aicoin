import SwiftUI

/// A wallet's standing, out of five, with the reasons underneath it.
///
/// The reasons are not decoration. A bare number reads as a verdict on somebody's honesty, and this
/// is not that: it is how much a wallet has to lose. Nought means "this wallet has never done
/// anything" — which is not an accusation and not a clean bill either, and is exactly what a
/// throwaway made to take one payment looks like.
struct RatingView: View {
    let rating: Int
    let reasons: [String]
    var compact: Bool = false

    private var colour: Color {
        switch rating {
        case 0: return .red
        case 1, 2: return .orange
        default: return WalletTheme.accent
        }
    }

    private var headline: String {
        switch rating {
        case 0 where reasons.contains(where: { $0.contains("double-spend") }): return "double-spend on record"
        case 0: return "no history — receiving is a risk"
        case 1: return "in debt"
        case 2: return "little to go on"
        case 3, 4: return "some history"
        default: return "established"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                ForEach(0..<5, id: \.self) { index in
                    Image(systemName: index < rating ? "star.fill" : "star")
                        .foregroundColor(index < rating ? colour : WalletTheme.muted)
                        .font(.caption)
                }
                Text(headline)
                    .font(.caption)
                    .foregroundColor(colour)
                    .accessibilityIdentifier("ratingHeadline")
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(rating) out of 5: \(headline)")

            if !compact {
                ForEach(reasons, id: \.self) { reason in
                    Text("· " + reason)
                        .font(.caption2)
                        .foregroundColor(WalletTheme.muted)
                }
            }
        }
    }
}
