import SwiftUI
import UIKit

/// The first screen: make a new wallet, or bring back one from its private key.
struct OnboardingView: View {
    @ObservedObject var store: WalletStore
    @State private var isImporting = false

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Image("LaunchLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 96, height: 96)
                .accessibilityHidden(true)
            Text("AICoin Wallet")
                .font(.largeTitle.bold())
                .padding(.top, 20)
            Text("Pay for AI with coins you hold yourself. Your private key never leaves this iPhone.")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
                .padding(.horizontal, 32)
            Spacer()
            VStack(spacing: 12) {
                Button {
                    store.generateNewWallet()
                } label: {
                    Text("Create New Wallet").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                Button {
                    isImporting = true
                } label: {
                    Text("Import Existing Wallet").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            .controlSize(.large)
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .accessibilityIdentifier("connectScreen")
        .sheet(isPresented: $isImporting) {
            ImportWalletView(store: store)
        }
    }
}

/// Bringing back a wallet from the 128-character key saved when it was made.
struct ImportWalletView: View {
    @ObservedObject var store: WalletStore
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Private key", text: $key, axis: .vertical)
                        .font(.body.monospaced())
                        .lineLimit(3...6)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .accessibilityIdentifier("importField")
                    Button("Paste from Clipboard", systemImage: "doc.on.clipboard") {
                        key = UIPasteboard.general.string ?? ""
                    }
                } footer: {
                    Text("The 128-character private key you saved when you created the wallet. The same key works in the web wallet.")
                }
                if let error {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Import Wallet")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Import") { importKey() }
                        .disabled(key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func importKey() {
        do {
            try store.importWallet(backupBlob: key)
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
