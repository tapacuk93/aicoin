import SwiftUI
import UIKit

/// This wallet's address, as a code to scan and as text to check it against.
struct ReceiveView: View {
    let address: String
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    // On white whatever the appearance: a QR code inverted for dark mode is one
                    // that many scanners cannot read.
                    QRCodeView(text: address, size: 220)
                        .padding(20)
                        .background(.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: .black.opacity(0.08), radius: 12, y: 4)
                    Text(address)
                        .font(.callout.monospaced())
                        .multilineTextAlignment(.center)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("receiveAddress")
                    HStack(spacing: 12) {
                        Button {
                            UIPasteboard.general.string = address
                            copied = true
                        } label: {
                            Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                                .frame(maxWidth: .infinity)
                        }
                        ShareLink(item: address) {
                            Label("Share", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    Text("Only AICoin can be sent to this address. It's safe to share.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
            }
            .navigationTitle("Receive")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .sensoryFeedback(.success, trigger: copied)
        }
    }
}
