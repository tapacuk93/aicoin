import SwiftUI

/// Mirrors wallet.html's `#connectScreen`: generate a fresh wallet, or
/// import one from a 128-hex-char backup blob.
struct ConnectView: View {
    @ObservedObject var store: WalletStore
    @State private var importText = ""
    @State private var message = ""
    @State private var isError = false
    @State private var isGenerating = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("aicoin wallet").font(.title2.weight(.semibold))
                    Text("A real Ed25519 keypair. Your private key never leaves this device.")
                        .font(.subheadline)
                        .foregroundColor(WalletTheme.muted)
                }

                WalletCard(title: "Open wallet") {
                    PrimaryButton(title: "Generate new wallet", disabled: isGenerating) {
                        isGenerating = true
                        store.generateNewWallet()
                        isGenerating = false
                    }

                    Text("or")
                        .font(.caption)
                        .foregroundColor(WalletTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 4)

                    Text("Import from a backup private key")
                        .font(.caption)
                        .foregroundColor(WalletTheme.muted)
                    TextEditor(text: $importText)
                        .frame(height: 90)
                        .font(.system(.footnote, design: .monospaced))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .padding(6)
                        .background(WalletTheme.background)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.border))

                    SecondaryButton(title: "Import wallet") {
                        do {
                            try store.importWallet(backupBlob: importText)
                            message = ""
                        } catch {
                            message = error.localizedDescription
                            isError = true
                        }
                    }

                    StatusMessage(text: message, isError: isError)
                }
            }
            .padding(20)
        }
        .background(WalletTheme.background)
        .accessibilityIdentifier("connectScreen")
    }
}
