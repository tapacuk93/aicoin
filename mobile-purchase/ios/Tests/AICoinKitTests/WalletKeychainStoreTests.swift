import XCTest
@testable import AICoinKit

/// Exercises real Keychain I/O (no mocking possible for `Security` framework calls) against a
/// uniquely-scoped service/account per test run, cleaned up afterwards. Skips gracefully — rather
/// than failing — if the sandbox this runs in has no Keychain access at all (e.g. a CI runner
/// without the entitlement), since that's an environment limitation, not a logic bug in this
/// package.
final class WalletKeychainStoreTests: XCTestCase {
    private func freshStore() -> WalletKeychainStore {
        WalletKeychainStore(service: "com.tarasmaslov.aicoinkit.tests", account: "seed-\(UUID().uuidString)")
    }

    private func skipIfKeychainUnavailable(_ error: Error) throws {
        if let keychainError = error as? WalletKeychainStore.KeychainError,
           case .unhandledStatus(let status) = keychainError,
           status == errSecMissingEntitlement || status == -34018 {
            throw XCTSkip("Keychain access unavailable in this sandbox (status \(status)).")
        }
        throw error
    }

    func testCreateThenLoadRoundTripsTheSameIdentity() throws {
        let store = freshStore()
        defer { try? store.deleteIdentity() }
        do {
            let created = try store.createIdentity()
            let loaded = try store.loadIdentity()
            XCTAssertEqual(loaded?.address, created.address)
        } catch {
            try skipIfKeychainUnavailable(error)
        }
    }

    func testLoadReturnsNilWhenNothingStoredYet() throws {
        let store = freshStore()
        do {
            let loaded = try store.loadIdentity()
            XCTAssertNil(loaded)
        } catch {
            try skipIfKeychainUnavailable(error)
        }
    }

    func testLoadOrCreateIsIdempotentAcrossCalls() throws {
        let store = freshStore()
        defer { try? store.deleteIdentity() }
        do {
            let first = try store.loadOrCreateIdentity()
            let second = try store.loadOrCreateIdentity()
            XCTAssertEqual(first.address, second.address)
        } catch {
            try skipIfKeychainUnavailable(error)
        }
    }

    func testSavingAReplacesAnyExisting() throws {
        let store = freshStore()
        defer { try? store.deleteIdentity() }
        do {
            let first = try store.createIdentity()
            let second = WalletIdentity.generate()
            try store.save(second)
            let loaded = try store.loadIdentity()
            XCTAssertEqual(loaded?.address, second.address)
            XCTAssertNotEqual(loaded?.address, first.address)
        } catch {
            try skipIfKeychainUnavailable(error)
        }
    }

    func testDeleteRemovesTheStoredIdentity() throws {
        let store = freshStore()
        do {
            _ = try store.createIdentity()
            try store.deleteIdentity()
            XCTAssertNil(try store.loadIdentity())
        } catch {
            try skipIfKeychainUnavailable(error)
        }
    }
}
