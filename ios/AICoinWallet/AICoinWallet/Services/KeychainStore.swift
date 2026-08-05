import Foundation
import Security

/// Persists the wallet's 32-byte seed in the iOS Keychain — the native
/// equivalent of the browser wallet's `localStorage`, but backed by the
/// Secure Enclave-protected keychain instead of plain storage. Only the
/// seed is stored; the public key/address is always re-derived from it.
enum KeychainStore {
    private static let service = "com.oeaio.aicoin.wallet"
    private static let account = "wallet-seed"

    private static var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }

    static func save(seed: Data) {
        SecItemDelete(baseQuery as CFDictionary)
        var attributes = baseQuery
        attributes[kSecValueData as String] = seed
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(attributes as CFDictionary, nil)
    }

    static func loadSeed() -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }

    static func clear() {
        SecItemDelete(baseQuery as CFDictionary)
    }
}
