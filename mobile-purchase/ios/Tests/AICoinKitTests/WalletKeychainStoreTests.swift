import XCTest
@testable import AICoinKit

/// Exercises real Keychain I/O (no mocking possible for `Security` framework calls) against a
/// uniquely-scoped service/account per test run, cleaned up afterwards. Skips gracefully — rather
/// than failing — if the sandbox this runs in has no Keychain access at all (e.g. a CI runner
/// without the entitlement), since that's an environment limitation, not a logic bug in this
/// package.
final class WalletKeychainStoreTests: XCTestCase {
    /// Never shared through iCloud Keychain: a test must not put wallets on the user's devices.
    private func freshStore() -> WalletKeychainStore {
        WalletKeychainStore(service: "com.tarasmaslov.aicoinkit.tests", account: "seed-\(UUID().uuidString)", synchronizable: false)
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

    // MARK: - Sharing with the user's other devices

    func testReconciliationNeverHidesCoins() {
        XCTAssertEqual(WalletKeychainStore.reconciliation(mine: 0, shared: 3186), .adoptShared, "an empty device takes the funded wallet")
        XCTAssertEqual(WalletKeychainStore.reconciliation(mine: 0, shared: 0), .adoptShared, "two empty ones converge")
        XCTAssertEqual(WalletKeychainStore.reconciliation(mine: 50, shared: 0), .shareMine, "a funded one is shared in place of an empty one")
        XCTAssertEqual(WalletKeychainStore.reconciliation(mine: 50, shared: 3186), .keepBoth, "two funded ones are both left alone")
    }

    func testAStoreThatDoesntShareNeverReconciles() async {
        let store = freshStore()
        XCTAssertFalse(store.synchronizable)
        let switched = await store.reconcile(current: WalletIdentity.generate()) { _ in 0 }
        XCTAssertNil(switched)
    }
}
