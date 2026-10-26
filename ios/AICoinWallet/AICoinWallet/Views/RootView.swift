import SwiftUI

struct RootView: View {
    @ObservedObject var store: WalletStore

    var body: some View {
        Group {
            switch store.screen {
            case .connect:
                OnboardingView(store: store)
            case .backup(let keys, _):
                BackupView(store: store, keys: keys)
            case .wallet:
                if let keys = store.keys {
                    WalletView(store: store, keys: keys)
                        .id(keys.address)
                } else {
                    OnboardingView(store: store)
                }
            }
        }
        .animation(.default, value: store.keys?.address)
    }
}
