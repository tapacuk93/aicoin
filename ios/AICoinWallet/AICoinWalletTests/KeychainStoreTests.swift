import Testing
import Foundation
import Security
@testable import AICoinWallet

struct KeychainStoreTests {
    @Test func savedSeedRoundTripsAndClearRemovesIt() {
        let seed = Data((0..<32).map { UInt8($0) })
        KeychainStore.save(seed: seed)
        #expect(KeychainStore.loadSeed() == seed)

        KeychainStore.clear()
        #expect(KeychainStore.loadSeed() == nil)
    }

    @Test func savingASecondSeedReplacesTheFirst() {
        let first = Data((0..<32).map { UInt8($0) })
        let second = Data((0..<32).map { UInt8(31 - $0) })
        KeychainStore.save(seed: first)
        KeychainStore.save(seed: second)
        #expect(KeychainStore.loadSeed() == second)
        KeychainStore.clear()
    }

    @Test func savedSeedIsSynchronizable() {
        KeychainStore.save(seed: Data(repeating: 7, count: 32))
        #expect(Self.storedItemIsSynchronizable() == true)
        KeychainStore.clear()
    }

    /// Earlier builds stored the seed device-only; it must still open, and move to iCloud Keychain.
    @Test func deviceOnlySeedFromAnEarlierBuildIsMigrated() {
        KeychainStore.clear()
        let seed = Data(repeating: 9, count: 32)
        let legacy: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.oeaio.aicoin.wallet",
            kSecAttrAccount as String: "wallet-seed",
            kSecValueData as String: seed,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        #expect(SecItemAdd(legacy as CFDictionary, nil) == errSecSuccess)

        #expect(KeychainStore.loadSeed() == seed)
        #expect(Self.storedItemIsSynchronizable() == true)
        #expect(KeychainStore.loadSeed() == seed)
        KeychainStore.clear()
    }

    private static func storedItemIsSynchronizable() -> Bool? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.oeaio.aicoin.wallet",
            kSecAttrAccount as String: "wallet-seed",
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]], items.count == 1 else { return nil }
        return items[0][kSecAttrSynchronizable as String] as? Bool
    }
}
