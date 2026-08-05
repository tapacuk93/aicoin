import Testing
import Foundation
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
}
