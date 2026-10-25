import SwiftUI

@main
struct AICoinAdminApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var client = AdminClient()
    @StateObject private var push = PushManager.shared

    var body: some Scene {
        WindowGroup {
            TabView {
                OverviewView().tabItem { Label("Overview", systemImage: "gauge.with.dots.needle.33percent") }
                AppsView().tabItem { Label("Apps", systemImage: "square.grid.2x2") }
                SalesView().tabItem { Label("Sales", systemImage: "cart") }
                WalletsView().tabItem { Label("Wallets", systemImage: "wallet.pass") }
                SettingsView().tabItem { Label("Settings", systemImage: "gearshape") }
            }
            .environmentObject(client)
            .environmentObject(push)
            .task { push.attach(client) }
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
