import SwiftUI

/// AICoin's own mark: a struck coin disc with "AI" knocked out of it.
///
/// Deliberately **not** an SF Symbol. The tempting candidate, `bitcoinsign.circle.fill`,
/// stamps Bitcoin's ₿ onto a currency that is explicitly *not* a cryptocurrency — per
/// CONTRACT.md, aicoin is a centralized ledger with no chain, no mining, and no
/// decentralization — so that glyph misrepresents what a user is buying, on the one
/// screen where they are about to spend money. There is also no generic "coin" symbol
/// in SF Symbols that reads as a token rather than as cash or a specific currency.
///
/// Drawn inline rather than shipped as an asset so it needs no asset catalog in any of
/// the three host apps, renders correctly in both light and dark mode, and scales with
/// Dynamic Type the way a real glyph does.
///
/// The disc is filled with the current foreground colour and the letters are punched out
/// with `.destinationOut`, so the mark behaves like a monochrome symbol: it inherits
/// whatever tint or foreground style the surrounding context applies, exactly as the SF
/// Symbol it replaces did.
public struct AICoinMark: View {
    /// Matches the cap height of `.subheadline` text at the default Dynamic Type size,
    /// so the mark sits level with an adjacent balance number instead of riding high or
    /// low, and grows with the user's chosen text size.
    @ScaledMetric(relativeTo: .subheadline) private var diameter: CGFloat = 17

    public init() {}

    public var body: some View {
        Circle()
            .overlay {
                Text(verbatim: "AI")
                    // Sized as a fraction of the disc so the letters keep their
                    // proportions at every Dynamic Type size.
                    .font(.system(size: diameter * 0.46, weight: .heavy, design: .rounded))
                    .minimumScaleFactor(0.5)
                    .blendMode(.destinationOut)
            }
            // Required for `.destinationOut` to punch through only the disc rather than
            // everything already drawn behind this view.
            .compositingGroup()
            .frame(width: diameter, height: diameter)
            // The surrounding control supplies the real label ("N AICoin"); announcing
            // the mark separately would just make VoiceOver read the coin twice.
            .accessibilityHidden(true)
    }
}

#if DEBUG
#Preview("AICoin mark") {
    VStack(spacing: 16) {
        HStack(spacing: 4) {
            AICoinMark()
            Text(verbatim: "1,234")
        }
        .font(.subheadline.weight(.medium))

        HStack(spacing: 4) {
            AICoinMark()
            Text(verbatim: "1,234")
        }
        .font(.subheadline.weight(.medium))
        .foregroundStyle(.tint)

        HStack(spacing: 6) {
            AICoinMark()
            Text(verbatim: "50 AICoin")
        }
        .font(.title2)
    }
    .padding()
}
#endif
