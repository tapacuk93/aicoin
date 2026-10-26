import SwiftUI
import UIKit

/// Shown once, right after generating a brand-new wallet and before it is kept: the only moment the
/// key is offered without being asked for.
struct BackupView: View {
    @ObservedObject var store: WalletStore
    let keys: WalletKeys
    @State private var saved = false
    @State private var copied = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label {
                        Text("This is the only copy of your private key. Anyone who has it can spend your coins, and if you lose it there is no way to recover this wallet.")
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    }
                }
                Section("Private Key") {
                    Text(keys.backupBlob)
                        .font(.callout.monospaced())
                        .textSelection(.enabled)
                        .accessibilityIdentifier("backupOutput")
                    Button(copied ? "Copied" : "Copy Private Key",
                           systemImage: copied ? "checkmark" : "doc.on.doc") {
                        UIPasteboard.general.string = keys.backupBlob
                        copied = true
                    }
                }
                Section {
                    Toggle("I've saved my private key somewhere safe", isOn: $saved)
                        .accessibilityIdentifier("savedToggle")
                }
                Section {
                    Button {
                        store.continueFromBackup()
                    } label: {
                        Text("Continue").frame(maxWidth: .infinity)
                    }
                    .disabled(!saved)
                    .accessibilityIdentifier("continueButton")
                }
            }
            .navigationTitle("Back Up Your Wallet")
            .sensoryFeedback(.success, trigger: copied)
        }
        .accessibilityIdentifier("backupScreen")
    }
}
