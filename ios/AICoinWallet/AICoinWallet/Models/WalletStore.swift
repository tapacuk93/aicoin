import Foundation
import SwiftUI

/// Owns the currently-open wallet (if any) and drives screen transitions —
/// the SwiftUI equivalent of wallet.html's top-level `wallet` variable plus
/// `showScreen`/`enterWallet`/`goToBackupScreen`.
@MainActor
final class WalletStore: ObservableObject {
    enum Screen {
        case connect
        case backup(keys: WalletKeys, persistOnContinue: Bool)
        case wallet
    }

    @Published private(set) var screen: Screen = .connect
    @Published private(set) var keys: WalletKeys?

    init() {
        if ProcessInfo.processInfo.arguments.contains("UITEST_RESET_WALLET") {
            KeychainStore.clear()
        }
        if let seed = KeychainStore.loadSeed(), let restored = try? WalletKeys(seed: seed) {
            keys = restored
            screen = .wallet
        }
    }

    func generateNewWallet() {
        screen = .backup(keys: WalletKeys.generate(), persistOnContinue: true)
    }

    func importWallet(backupBlob: String) throws {
        let imported = try WalletKeys.importing(backupBlob: backupBlob)
        KeychainStore.save(seed: imported.seed)
        keys = imported
        screen = .wallet
    }

    func continueFromBackup() {
        guard case let .backup(newKeys, persist) = screen else { return }
        if persist {
            KeychainStore.save(seed: newKeys.seed)
        }
        keys = newKeys
        screen = .wallet
    }

    func useADifferentWallet() {
        KeychainStore.clear()
        keys = nil
        screen = .connect
    }
}
