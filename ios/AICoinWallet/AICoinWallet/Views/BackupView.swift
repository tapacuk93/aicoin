import SwiftUI
import UIKit

/// Mirrors wallet.html's `#backupScreen`: shown once, right after generating
/// a brand-new wallet, before it's ever persisted.
struct BackupView: View {
    @ObservedObject var store: WalletStore
    let keys: WalletKeys

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                WalletCard(title: "Save your private key") {
                    Text("This is the **only** copy of your private key. Anyone who has it can spend and send your coins. If you lose it, there is no way to recover this wallet — save it somewhere safe before continuing.")
                        .font(.caption)
                        .foregroundColor(.primary)
                        .padding(10)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.danger))

                    Text("Private key (back this up now)")
                        .font(.caption)
                        .foregroundColor(WalletTheme.muted)

                    Text(keys.backupBlob)
                        .font(.system(.footnote, design: .monospaced))
                        .textSelection(.enabled)
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(WalletTheme.background)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.border))
                        .accessibilityIdentifier("backupOutput")

                    SecondaryButton(title: "Copy to clipboard") {
                        UIPasteboard.general.string = keys.backupBlob
                    }

                    PrimaryButton(title: "I've saved it — continue") {
                        store.continueFromBackup()
                    }
                }
            }
            .padding(20)
        }
        .background(WalletTheme.background)
        .accessibilityIdentifier("backupScreen")
    }
}
