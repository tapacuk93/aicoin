import AppKit
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

// AICoin brand palette — mirrors WalletTheme.swift / site/index.html
let bg      = CGColor(red: 0x0b/255, green: 0x0d/255, blue: 0x10/255, alpha: 1)
let card    = CGColor(red: 0x14/255, green: 0x17/255, blue: 0x1c/255, alpha: 1)
let mint    = CGColor(red: 0x6e/255, green: 0xe7/255, blue: 0xb7/255, alpha: 1)
let mintDeep = CGColor(red: 0x34/255, green: 0xd3/255, blue: 0x99/255, alpha: 1)

func makeContext(_ size: Int) -> CGContext {
    let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
                        bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.setAllowsAntialiasing(true)
    ctx.interpolationQuality = .high
    return ctx
}

/// Draws the coin mark, centred, occupying `diameter` points.
func drawCoin(_ ctx: CGContext, center: CGPoint, diameter: CGFloat) {
    let r = diameter / 2
    let space = CGColorSpaceCreateDeviceRGB()

    // Soft mint glow behind the coin so it lifts off the dark ground.
    ctx.saveGState()
    let glow = CGGradient(colorsSpace: space,
                          colors: [mint.copy(alpha: 0.22)!, mint.copy(alpha: 0)!] as CFArray,
                          locations: [0, 1])!
    ctx.drawRadialGradient(glow, startCenter: center, startRadius: r * 0.9,
                           endCenter: center, endRadius: r * 1.45, options: [])
    ctx.restoreGState()

    // Coin body: mint gradient, lit from the top-left.
    ctx.saveGState()
    ctx.addArc(center: center, radius: r, startAngle: 0, endAngle: .pi * 2, clockwise: false)
    ctx.clip()
    let body = CGGradient(colorsSpace: space, colors: [mint, mintDeep] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(body,
                           start: CGPoint(x: center.x - r, y: center.y + r),
                           end: CGPoint(x: center.x + r, y: center.y - r), options: [])
    ctx.restoreGState()

    // Inset rim, milled-edge style.
    ctx.saveGState()
    ctx.setStrokeColor(bg.copy(alpha: 0.28)!)
    ctx.setLineWidth(r * 0.055)
    ctx.addArc(center: center, radius: r * 0.86, startAngle: 0, endAngle: .pi * 2, clockwise: false)
    ctx.strokePath()
    ctx.restoreGState()

    // "AI" wordmark knocked out of the coin face.
    let fontSize = r * 0.95
    let font = NSFont.systemFont(ofSize: fontSize, weight: .heavy)
    let attrs: [NSAttributedString.Key: Any] = [
        .font: font,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): bg,
        .kern: -fontSize * 0.04,
    ]
    let line = CTLineCreateWithAttributedString(
        NSAttributedString(string: "AI", attributes: attrs))
    let b = CTLineGetBoundsWithOptions(line, .useOpticalBounds)
    ctx.textPosition = CGPoint(x: center.x - b.width / 2 - b.origin.x,
                               y: center.y - b.height / 2 - b.origin.y)
    CTLineDraw(line, ctx)
}

/// Full-bleed app icon: opaque, square, no rounded corners (the system masks it).
func renderIcon(size: Int) -> CGImage {
    let ctx = makeContext(size)
    let s = CGFloat(size)
    let center = CGPoint(x: s / 2, y: s / 2)

    let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                          colors: [card, bg] as CFArray, locations: [0, 1])!
    ctx.drawRadialGradient(grad, startCenter: center, startRadius: 0,
                           endCenter: center, endRadius: s * 0.72, options: [.drawsAfterEndLocation])

    drawCoin(ctx, center: center, diameter: s * 0.62)
    return ctx.makeImage()!
}

/// Launch-screen / site mark: same coin on a transparent ground.
func renderMark(size: Int) -> CGImage {
    let ctx = makeContext(size)
    let s = CGFloat(size)
    drawCoin(ctx, center: CGPoint(x: s / 2, y: s / 2), diameter: s * 0.74)
    return ctx.makeImage()!
}

func write(_ image: CGImage, to path: String) {
    let url = URL(fileURLWithPath: path)
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                             withIntermediateDirectories: true)
    let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, image, nil)
    guard CGImageDestinationFinalize(dest) else { fatalError("write failed: \(path)") }
    print("wrote \(path) (\(image.width)x\(image.height))")
}

// Two modes:
//   makeicon <outdir>              — the aicoin app icon, launch logo, favicons
//   makeicon manifest <file>       — tab-separated "kind<TAB>pixelSize<TAB>path" lines
if CommandLine.arguments[1] == "manifest" {
    let text = try! String(contentsOfFile: CommandLine.arguments[2], encoding: .utf8)
    for line in text.split(separator: "\n") {
        let f = line.split(separator: "\t", omittingEmptySubsequences: false)
        guard f.count == 3, let px = Int(f[1]) else { continue }
        write(f[0] == "mark" ? renderMark(size: px) : renderIcon(size: px), to: String(f[2]))
    }
} else {
    let out = CommandLine.arguments[1]
    write(renderIcon(size: 1024), to: "\(out)/icon-1024.png")
    // Launch-screen logo at 1x/2x/3x for a 128pt mark.
    for (s, suffix) in [(128, ""), (256, "@2x"), (384, "@3x")] {
        write(renderMark(size: s), to: "\(out)/launch-logo\(suffix).png")
    }
    for s in [180, 32, 16] { write(renderIcon(size: s), to: "\(out)/favicon-\(s).png") }
}
