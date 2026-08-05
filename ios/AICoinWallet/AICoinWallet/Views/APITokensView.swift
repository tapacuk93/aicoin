import SwiftUI
import UIKit

/// Mirrors wallet.html's "API tokens" card: client-side-only token
/// issuance (the server never sees the private key or has an "issue
/// token" endpoint) plus revocation.
struct APITokensView: View {
    let keys: WalletKeys

    private static let expiryOptions: [(label: String, days: Int)] = [
        ("1 day", 1), ("7 days", 7), ("30 days", 30), ("90 days", 90)
    ]

    @State private var expiryDays = 7
    @State private var token = ""
    @State private var tokenMessage = ""
    @State private var tokenIsError = false
    @State private var revokeMessage = ""
    @State private var revokeIsError = false
    @State private var isRevoking = false

    var body: some View {
        WalletCard(title: "API tokens") {
            Text("A token lets whoever holds it make AI-proxy calls billed to this wallet — use it as your X-Api-Key in scripts, no signing needed per call. A token **cannot** claim free coins or send coins; only your private key can do that.")
                .font(.caption)
                .foregroundColor(WalletTheme.muted)

            Text("Expires after").font(.caption).foregroundColor(WalletTheme.muted)
            Picker("Expires after", selection: $expiryDays) {
                ForEach(Self.expiryOptions, id: \.days) { option in
                    Text(option.label).tag(option.days)
                }
            }
            .pickerStyle(.segmented)

            PrimaryButton(title: "Generate token") {
                generateToken()
            }

            StatusMessage(text: tokenMessage, isError: tokenIsError)

            if !token.isEmpty {
                Text(token)
                    .font(.system(.caption2, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(WalletTheme.background)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.border))

                SecondaryButton(title: "Copy to clipboard") {
                    UIPasteboard.general.string = token
                    tokenMessage = "Copied."
                    tokenIsError = false
                }
            }

            DangerButton(title: "Revoke all tokens", disabled: isRevoking) {
                revokeTokens()
            }
            StatusMessage(text: revokeMessage, isError: revokeIsError)
        }
    }

    private func generateToken() {
        do {
            token = try WalletSigner.buildToken(keys: keys, expiresInSeconds: expiryDays * 86400)
            tokenMessage = "Token generated — use it as your X-Api-Key."
            tokenIsError = false
        } catch {
            tokenMessage = "Could not generate a token: \(error.localizedDescription)"
            tokenIsError = true
        }
    }

    private func revokeTokens() {
        isRevoking = true
        revokeMessage = "Revoking..."
        revokeIsError = false
        Task {
            defer { isRevoking = false }
            do {
                try await ProxyAPI.revokeTokens(keys: keys)
                revokeMessage = "All previously issued tokens are now revoked."
                revokeIsError = false
                token = ""
            } catch {
                revokeMessage = error.localizedDescription
                revokeIsError = true
            }
        }
    }
}
