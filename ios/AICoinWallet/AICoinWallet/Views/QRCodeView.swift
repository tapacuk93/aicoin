import CoreImage.CIFilterBuiltins
import SwiftUI

/// A QR code for a wallet address, so receiving does not mean reading 64 hex characters aloud.
///
/// Generated on the device from the address already on screen — nothing is fetched, and the code is
/// exactly the text below it, so what is scanned can always be checked against what is shown.
struct QRCodeView: View {
    let text: String
    var size: CGFloat = 180

    var body: some View {
        Group {
            if let image = Self.render(text) {
                Image(uiImage: image)
                    .interpolation(.none)          // a QR is squares; smoothing them makes it worse
                    .resizable()
                    .frame(width: size, height: size)
                    .accessibilityIdentifier("addressQRCode")
                    .accessibilityLabel("QR code for this wallet address")
            } else {
                // Never a blank square pretending to be scannable.
                Text("could not draw a QR code")
                    .font(.caption)
                    .foregroundColor(WalletTheme.muted)
                    .frame(width: size, height: size)
            }
        }
    }

    static func render(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        // Medium correction: an address is short, and the extra redundancy costs nothing at this size.
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        // Scaled before rasterising: the generator emits one pixel per module, which on a phone
        // screen is a smudge rather than a code.
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cgImage = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
