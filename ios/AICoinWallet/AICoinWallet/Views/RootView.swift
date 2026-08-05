import SwiftUI

struct RootView: View {
    @ObservedObject var store: WalletStore

    var body: some View {
        Group {
            switch store.screen {
            case .connect:
                ConnectView(store: store)
            case .backup(let keys, _):
                BackupView(store: store, keys: keys)
            case .wallet:
                if let keys = store.keys {
                    WalletView(store: store, keys: keys)
                } else {
                    ConnectView(store: store)
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}
