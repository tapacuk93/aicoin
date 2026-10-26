import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The wallet's private key, hidden until asked for.
struct PrivateKeyView: View {
    let keys: WalletKeys
    @State private var revealed = false
    @State private var copied = false

    var body: some View {
        List {
            Section {
                Label {
                    Text("Anyone with this key controls the wallet and can spend every coin in it. Never share it, and never enter it on a website you don't trust.")
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                }
            }
            Section {
                Text(keys.backupBlob)
                    .font(.callout.monospaced())
                    .textSelection(.enabled)
                    .blur(radius: revealed ? 0 : 7)
                    .accessibilityHidden(!revealed)
                    .overlay {
                        if !revealed {
                            Button("Tap to Reveal", systemImage: "eye") {
                                withAnimation { revealed = true }
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                if revealed {
                    Button(copied ? "Copied — Clears in 2 Minutes" : "Copy Private Key",
                           systemImage: copied ? "checkmark" : "doc.on.doc") {
                        // Expiring, and kept to this device: a key left on the clipboard is a key
                        // any app — or a Mac on the same account — can read later.
                        UIPasteboard.general.setItems(
                            [[UTType.plainText.identifier: keys.backupBlob]],
                            options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
                        copied = true
                    }
                }
            } header: {
                Text("Private Key")
            } footer: {
                Text("128 characters. It opens this wallet in this app or in the web wallet.")
            }
        }
        .navigationTitle("Private Key")
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.success, trigger: copied)
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in
            // Hidden again before the app switcher takes its snapshot.
            revealed = false
        }
    }
}
