import Foundation
import Security

/// Persists the wallet's 32-byte seed in the Keychain — the native
/// equivalent of the browser wallet's `localStorage`. Only the seed is
/// stored; the public key/address is always re-derived from it.
///
/// The item is synchronizable, so iCloud Keychain carries the wallet to the
/// user's other iPhones, iPads and Macs (the Mac build is Mac Catalyst with
/// the same bundle ID, hence the same keychain access group). iCloud
/// Keychain is end-to-end encrypted; a synchronizable item cannot be
/// `…ThisDeviceOnly`, so it is `AfterFirstUnlock`. With iCloud Keychain off,
/// the item simply stays on this device.
enum KeychainStore {
    private static let service = "com.oeaio.aicoin.wallet"
    private static let account = "wallet-seed"

    private static func query(synchronizable: Any) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: synchronizable,
            // The modern keychain on macOS too — the only one that syncs.
            kSecUseDataProtectionKeychain as String: true
        ]
    }

    static func save(seed: Data) {
        // Drops any copy, synced or the device-only one earlier builds wrote.
        SecItemDelete(query(synchronizable: kSecAttrSynchronizableAny) as CFDictionary)
        var attributes = query(synchronizable: kCFBooleanTrue!)
        attributes[kSecValueData as String] = seed
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(attributes as CFDictionary, nil)
    }

    static func loadSeed() -> Data? {
        var lookup = query(synchronizable: kSecAttrSynchronizableAny)
        lookup[kSecReturnData as String] = true
        lookup[kSecReturnAttributes as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(lookup as CFDictionary, &result) == errSecSuccess,
              let item = result as? [String: Any],
              let seed = item[kSecValueData as String] as? Data
        else { return nil }
        // A wallet saved device-only by an earlier build moves into iCloud Keychain.
        if (item[kSecAttrSynchronizable as String] as? Bool) != true {
            save(seed: seed)
        }
        return seed
    }

    static func clear() {
        // Removes it from every device signed in to the same iCloud Keychain.
        SecItemDelete(query(synchronizable: kSecAttrSynchronizableAny) as CFDictionary)
    }
}
