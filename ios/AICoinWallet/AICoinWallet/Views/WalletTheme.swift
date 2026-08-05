import SwiftUI

/// Shared visual language for the wallet's cards/buttons, mirroring
/// wallet.html's `.card`/`button`/`.msg` styling closely enough to feel
/// like the same product on a different platform.
enum WalletTheme {
    static let background = Color(red: 0x0b / 255, green: 0x0d / 255, blue: 0x10 / 255)
    static let card = Color(red: 0x14 / 255, green: 0x17 / 255, blue: 0x1c / 255)
    static let border = Color(red: 0x23 / 255, green: 0x26 / 255, blue: 0x2b / 255)
    static let accent = Color(red: 0x6e / 255, green: 0xe7 / 255, blue: 0xb7 / 255)
    static let danger = Color(red: 0xf8 / 255, green: 0x71 / 255, blue: 0x71 / 255)
    static let muted = Color(red: 0x9a / 255, green: 0xa0 / 255, blue: 0xa6 / 255)
}

struct WalletCard<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title.uppercased())
                .font(.caption.weight(.semibold))
                .foregroundColor(WalletTheme.accent)
                .tracking(0.6)
            content
        }
        .padding(16)
        .background(WalletTheme.card)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(WalletTheme.border))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

struct PrimaryButton: View {
    let title: String
    var disabled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
        }
        .foregroundColor(Color(red: 0x06 / 255, green: 0x25 / 255, blue: 0x1a / 255))
        .background(WalletTheme.accent.opacity(disabled ? 0.5 : 1))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .disabled(disabled)
    }
}

struct SecondaryButton: View {
    let title: String
    var disabled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.body.weight(.medium))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
        }
        .foregroundColor(.primary)
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.border))
        .disabled(disabled)
    }
}

struct DangerButton: View {
    let title: String
    var disabled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.body.weight(.medium))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
        }
        .foregroundColor(WalletTheme.danger)
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.danger))
        .disabled(disabled)
    }
}

struct StatusMessage: View {
    let text: String
    let isError: Bool

    var body: some View {
        if !text.isEmpty {
            Text(text)
                .font(.caption)
                .foregroundColor(isError ? WalletTheme.danger : WalletTheme.accent)
        }
    }
}
