import SwiftUI

@main
struct AICoinAdminApp: App {
    @StateObject private var client = AdminClient()

    var body: some Scene {
        WindowGroup {
            TabView {
                OverviewView().tabItem { Label("Overview", systemImage: "gauge.with.dots.needle.33percent") }
                SalesView().tabItem { Label("Sales", systemImage: "cart") }
                WalletsView().tabItem { Label("Wallets", systemImage: "wallet.pass") }
                ActivityView().tabItem { Label("Activity", systemImage: "list.bullet.rectangle") }
                SettingsView().tabItem { Label("Settings", systemImage: "gearshape") }
            }
            .environmentObject(client)
        }
    }
}

/// One failure, said the way the proxy said it, under whatever was being done.
struct ErrorBanner: View {
    let message: String?
    var body: some View {
        if let message {
            Text(message)
                .font(.footnote)
                .foregroundStyle(.red)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

extension Double {
    var usd: String { String(format: "$%.2f", self) }
}
