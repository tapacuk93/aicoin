import SwiftUI

/// AICoin's own mark: a minted gold coin with **AI** stamped into its face.
///
/// Deliberately **not** an SF Symbol. The tempting candidate, `bitcoinsign.circle.fill`,
/// stamps Bitcoin's ₿ onto a currency that is explicitly *not* a cryptocurrency — per
/// CONTRACT.md, aicoin is a centralized ledger with no chain, no mining, and no
/// decentralization — so that glyph misrepresents what a user is buying, on the one
/// screen where they are about to spend money. Nor is there a generic "coin" symbol in
/// SF Symbols that reads as a token rather than as cash or some specific currency.
///
/// Drawn inline rather than shipped as an asset so it needs no asset catalog in any of
/// the three host apps and scales with Dynamic Type the way a real glyph does.
///
/// The coin is built from four stacked layers, which is what sells it as a struck disc
/// rather than a flat circle with letters on it:
/// 1. a metal face, lit from the top-left so it reads as convex;
/// 2. a raised outer **rim**, bright where the light hits and dark opposite;
/// 3. a faint inner ring, mirroring the raised field real coins are struck with;
/// 4. the **AI** legend, in a dark bronze so it looks stamped *into* the metal.
///
/// The palette is fixed rather than tint-following: a coin that changes colour with the
/// surrounding accent stops reading as a coin. It is legible on both light and dark
/// backgrounds, so it needs no per-scheme variant.
public struct AICoinMark: View {
    /// Sized to sit level with adjacent `.subheadline` text at the default Dynamic Type
    /// size, and to grow with the user's chosen text size.
    @ScaledMetric(relativeTo: .subheadline) private var diameter: CGFloat = 18

    public init() {}

    // Struck-metal palette. Kept as constants so the four layers stay in the same family.
    private static let faceHighlight = Color(red: 0.99, green: 0.87, blue: 0.49)
    private static let faceShadow    = Color(red: 0.80, green: 0.55, blue: 0.09)
    private static let rimHighlight  = Color(red: 1.00, green: 0.95, blue: 0.72)
    private static let rimShadow     = Color(red: 0.62, green: 0.40, blue: 0.04)
    private static let engraving     = Color(red: 0.38, green: 0.24, blue: 0.01)

    public var body: some View {
        ZStack {
            // 1. Convex metal face.
            Circle()
                .fill(
                    LinearGradient(
                        colors: [Self.faceHighlight, Self.faceShadow],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )

            // 2. Raised rim — the single strongest "this is a coin" cue.
            Circle()
                .strokeBorder(
                    LinearGradient(
                        colors: [Self.rimHighlight, Self.rimShadow],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: diameter * 0.09
                )

            // 3. Inner field ring, as struck coins have around their device.
            Circle()
                .inset(by: diameter * 0.17)
                .stroke(Self.rimShadow.opacity(0.35), lineWidth: max(0.5, diameter * 0.03))

            // 4. The legend, stamped into the face.
            Text(verbatim: "AI")
                .font(.system(size: diameter * 0.40, weight: .black, design: .rounded))
                .foregroundStyle(Self.engraving)
                .minimumScaleFactor(0.5)
        }
        .frame(width: diameter, height: diameter)
        // A hairline edge keeps the coin from dissolving into a light background.
        .overlay(Circle().stroke(Self.rimShadow.opacity(0.30), lineWidth: 0.5))
        // The surrounding control supplies the real label ("N AICoin"); announcing the
        // mark separately would just make VoiceOver read the coin twice.
        .accessibilityHidden(true)
    }
}

#if DEBUG
#Preview("AICoin mark") {
    VStack(spacing: 20) {
        ForEach([Font.caption, .subheadline, .title2, .largeTitle], id: \.self) { font in
            HStack(spacing: 5) {
                AICoinMark()
                Text(verbatim: "1,234")
            }
            .font(font)
        }
    }
    .padding()
}
#endif
