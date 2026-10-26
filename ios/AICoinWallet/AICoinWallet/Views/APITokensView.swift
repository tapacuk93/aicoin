import SwiftUI
import UIKit

/// Tokens for scripts: minted here with the private key (the proxy has no "issue token" endpoint
/// and never sees the key), and all withdrawn at once.
struct APITokensView: View {
    let keys: WalletKeys

    private static let expiryOptions: [(label: String, days: Int)] = [
        ("1 Day", 1), ("7 Days", 7), ("30 Days", 30), ("90 Days", 90)
    ]

    @State private var expiryDays = 7
    @State private var token = ""
    @State private var copied = false
    @State private var isConfirmingRevoke = false
    @State private var isRevoking = false
    @State private var revoked = false
    @State private var error: String?

    var body: some View {
        Form {
            Section {
                Picker("Expires After", selection: $expiryDays) {
                    ForEach(Self.expiryOptions, id: \.days) { option in
                        Text(option.label).tag(option.days)
                    }
                }
                Button("Generate Token", systemImage: "plus.circle") { generate() }
                    .accessibilityIdentifier("generateTokenButton")
            } footer: {
                Text("Whoever holds a token can make AI calls billed to this wallet — send it as X-Api-Key, with no signing per call. A token can't send coins or claim free coins.")
            }

            if !token.isEmpty {
                Section {
                    Text(token)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                        .accessibilityIdentifier("tokenOutput")
                    Button(copied ? "Copied" : "Copy Token", systemImage: copied ? "checkmark" : "doc.on.doc") {
                        UIPasteboard.general.string = token
                        copied = true
                    }
                    ShareLink(item: token) {
                        Label("Share Token", systemImage: "square.and.arrow.up")
                    }
                } header: {
                    Text("New Token")
                } footer: {
                    Text("Shown once. This app doesn't keep a copy.")
                }
            }

            Section {
                Button(role: .destructive) {
                    isConfirmingRevoke = true
                } label: {
                    HStack {
                        Label("Revoke All Tokens", systemImage: "xmark.octagon")
                        Spacer()
                        if isRevoking { ProgressView() }
                    }
                }
                .foregroundStyle(.red)
                .disabled(isRevoking)
            } footer: {
                if revoked {
                    Label("Every token issued before now has stopped working.", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                } else {
                    Text("Stops every token this wallet has issued, including those held by authorised services. To cut off one service only, revoke it under Authorised Services.")
                }
            }
        }
        .navigationTitle("API Tokens")
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.success, trigger: copied)
        .confirmationDialog("Revoke all tokens?", isPresented: $isConfirmingRevoke, titleVisibility: .visible) {
            Button("Revoke All Tokens", role: .destructive) { revokeAll() }
        } message: {
            Text("Scripts and services using any existing token will stop working immediately.")
        }
        .alert("Something Went Wrong", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(error ?? "")
        }
    }

    private func generate() {
        do {
            token = try WalletSigner.buildToken(keys: keys, expiresInSeconds: expiryDays * 86400)
            copied = false
            revoked = false
        } catch {
            self.error = "Couldn't generate a token: \(error.localizedDescription)"
        }
    }

    private func revokeAll() {
        isRevoking = true
        Task {
            defer { isRevoking = false }
            do {
                try await ProxyAPI.revokeTokens(keys: keys)
                token = ""
                revoked = true
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
