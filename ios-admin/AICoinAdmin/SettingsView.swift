import SwiftUI

/// Which proxy, the token that opens its /admin side, and which wallets are ours.
struct SettingsView: View {
    @EnvironmentObject private var client: AdminClient
    @State private var token = ""
    @State private var wallet = ""
    @State private var message: String?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Proxy") {
                    TextField("Address", text: $client.baseURL)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    Button("Use production") { client.baseURL = AdminClient.defaultURL }
                }
                Section {
                    SecureField(client.hasToken ? "Saved - paste to replace" : "Admin token", text: $token)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("Save token") { client.setToken(token); token = ""; message = "Token saved" }
                        .disabled(token.isEmpty)
                    if client.hasToken {
                        Button("Forget token", role: .destructive) { client.setToken(""); message = "Token removed" }
                    }
                } header: { Text("Admin token") } footer: {
                    Text("Kept in this device's Keychain only. It is AICOIN_PROXY_ADMIN_TOKEN on the server.")
                }
                Section {
                    TextField("Wallet address", text: $wallet)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().font(.body.monospaced())
                    HStack {
                        Button("Mark internal") { Task { await internalWallet(add: true) } }
                        Spacer()
                        Button("Unmark", role: .destructive) { Task { await internalWallet(add: false) } }
                    }
                    .disabled(wallet.count != 64)
                } header: { Text("Internal wallets") } footer: {
                    Text("Spend from internal wallets - development and testing - does not count against the budget.")
                }
                if let message { Text(message).foregroundStyle(.secondary) }
                ErrorBanner(message: error)
            }
            .navigationTitle("Settings")
        }
    }

    private func internalWallet(add: Bool) async {
        error = nil; message = nil
        let a = wallet.trimmingCharacters(in: .whitespaces).lowercased()
        do {
            try await add ? client.internalWallets(add: [a]) : client.internalWallets(remove: [a])
            message = add ? "Marked internal" : "No longer internal"
            wallet = ""
        } catch { self.error = error.localizedDescription }
    }
}
