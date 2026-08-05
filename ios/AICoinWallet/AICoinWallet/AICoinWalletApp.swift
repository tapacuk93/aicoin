import SwiftUI

@main
struct AICoinWalletApp: App {
    @StateObject private var store = WalletStore()

    var body: some Scene {
        WindowGroup {
            RootView(store: store)
        }
    }
}
