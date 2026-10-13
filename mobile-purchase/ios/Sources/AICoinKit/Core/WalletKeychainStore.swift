import Foundation
import Security

/// Persists a `WalletIdentity`'s 32-byte seed in the platform Keychain — generalizes the
/// prototype `AICoinWallet` app's `KeychainStore`. Only the seed is ever stored; the public
/// key/address is always re-derived from it on load.
///
/// By default each app gets its own independent wallet (a private, per-app Keychain item). If the
/// three consuming apps (InfiniteAIRadio, All Languages Learner, Learn It) want to share a single
/// aicoin balance across all three, pass a common `accessGroup` — a Keychain Sharing access group
/// configured identically in each app's entitlements (same team ID, same group string) — so the
/// same seed round-trips across apps. That capability entitlement is a per-app Xcode project
/// setting this package can't configure for you; see the package README.
///
/// The same app on the user's other devices shares one wallet too: alongside the device's own
/// item, the seed is kept in an iCloud Keychain item (`synchronizable`), which iCloud syncs end to
/// end encrypted to every device signed in to the same Apple ID. A device with no wallet yet
/// adopts that one at once; one that already has its own reconciles the two by balance (see
/// `reconcile`). Without this, a Mac running the same app as a funded iPhone started from an empty
/// wallet of its own. Every synced-item operation is best-effort: with iCloud Keychain off, or
/// without the entitlement a data-protection keychain needs, the device simply keeps its own.
public struct WalletKeychainStore: Sendable {
    public let service: String
    public let account: String
    public let accessGroup: String?
    /// Whether the wallet is shared with the user's other devices through iCloud Keychain.
    public let synchronizable: Bool

    public init(
        service: String = "com.tarasmaslov.aicoinkit.wallet",
        account: String = "wallet-seed",
        accessGroup: String? = nil,
        synchronizable: Bool = true
    ) {
        self.service = service
        self.account = account
        self.accessGroup = accessGroup
        self.synchronizable = synchronizable
    }

    public enum KeychainError: Error, LocalizedError, Sendable {
        case unhandledStatus(OSStatus)
        case unexpectedItemData

        public var errorDescription: String? {
            switch self {
            case .unhandledStatus(let status):
                if let message = SecCopyErrorMessageString(status, nil) as String? {
                    return "Keychain error \(status): \(message)"
                }
                return "Keychain error \(status)"
            case .unexpectedItemData:
                return "Keychain item exists but its data is not a valid wallet seed."
            }
        }
    }

    private var baseQuery: [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let accessGroup {
            query[kSecAttrAccessGroup as String] = accessGroup
        }
        return query
    }

    /// Loads the persisted identity, or `nil` if none has been created/imported yet.
    public func loadIdentity() throws -> WalletIdentity? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else { throw KeychainError.unexpectedItemData }
            return try WalletIdentity(seed: data)
        case errSecItemNotFound:
            return nil
        default:
            throw KeychainError.unhandledStatus(status)
        }
    }

    /// Generates a brand-new identity and persists it, overwriting any existing one.
    @discardableResult
    public func createIdentity() throws -> WalletIdentity {
        let identity = WalletIdentity.generate()
        try save(identity)
        return identity
    }

    /// Persists an already-constructed identity (e.g. one produced by `WalletIdentity.importing`),
    /// overwriting any existing one.
    public func save(_ identity: WalletIdentity) throws {
        SecItemDelete(baseQuery as CFDictionary)
        var attributes = baseQuery
        attributes[kSecValueData as String] = identity.seed
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.unhandledStatus(status) }
    }

    /// Returns the existing identity, or creates and persists a new one if none exists yet — the
    /// call every app makes at launch to get "the current wallet" without caring whether this is
    /// the first launch.
    ///
    /// A device that already has a wallet keeps it (and shares it, if nothing is shared yet);
    /// one that doesn't adopts the wallet shared from the user's other devices, and only creates a
    /// fresh one — shared from then on — when there's none anywhere.
    @discardableResult
    public func loadOrCreateIdentity() throws -> WalletIdentity {
        if let existing = try loadIdentity() {
            if synchronizable, loadSyncedIdentity() == nil { saveSynced(existing) }
            return existing
        }
        if synchronizable, let shared = loadSyncedIdentity() {
            try save(shared)
            return shared
        }
        let created = try createIdentity()
        if synchronizable { saveSynced(created) }
        return created
    }

    // MARK: - Sharing with the user's other devices

    private var syncedQuery: [String: Any] {
        var query = baseQuery
        query[kSecAttrSynchronizable as String] = kCFBooleanTrue
        // iCloud Keychain only syncs the data-protection keychain; on macOS that has to be asked
        // for (iOS has no other).
        query[kSecUseDataProtectionKeychain as String] = true
        return query
    }

    /// The wallet shared through iCloud Keychain, if there is one (and it can be read here).
    public func loadSyncedIdentity() -> WalletIdentity? {
        var query = syncedQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return try? WalletIdentity(seed: data)
    }

    /// Shares `identity` with the user's other devices, replacing whatever was shared before.
    /// Returns whether it was stored.
    @discardableResult
    public func saveSynced(_ identity: WalletIdentity) -> Bool {
        SecItemDelete(syncedQuery as CFDictionary)
        var attributes = syncedQuery
        attributes[kSecValueData as String] = identity.seed
        // A synced item can't be `ThisDeviceOnly` — that's the whole point of it.
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }

    /// Settles which wallet this device should use when it has its own and another is shared from
    /// the user's other devices — which happens to every device that had a wallet before sharing
    /// existed, and to one whose shared wallet arrives through iCloud after it made its own.
    ///
    /// Decided by balance, so no coins are ever stranded out of sight:
    /// - this device's wallet is empty and the shared one isn't: switch to the shared one;
    /// - this device's wallet holds coins and the shared one doesn't: share this one instead, so
    ///   the user's other (empty) devices come over to it;
    /// - both empty: take the shared one, so the devices converge on one wallet;
    /// - both hold coins: leave both alone — overwriting either would hide its coins.
    ///
    /// Returns the identity to switch to (already saved as this device's own), or `nil` to keep
    /// `current`. Nothing changes when a balance can't be read.
    public func reconcile(current: WalletIdentity, balance: (String) async throws -> Double) async -> WalletIdentity? {
        guard synchronizable, let shared = loadSyncedIdentity(), shared.address != current.address,
              let mine = try? await balance(current.address), let theirs = try? await balance(shared.address) else { return nil }
        switch Self.reconciliation(mine: mine, shared: theirs) {
        case .adoptShared:
            guard (try? save(shared)) != nil else { return nil }
            return shared
        case .shareMine:
            saveSynced(current)
            return nil
        case .keepBoth:
            return nil
        }
    }

    /// What `reconcile` does with two different wallets, given their balances.
    public enum Reconciliation: Equatable, Sendable {
        /// Switch this device to the shared wallet.
        case adoptShared
        /// Share this device's wallet in place of the shared one.
        case shareMine
        /// Leave both as they are.
        case keepBoth
    }

    public static func reconciliation(mine: Double, shared: Double) -> Reconciliation {
        switch (mine > 0, shared > 0) {
        case (false, _): return .adoptShared
        case (true, false): return .shareMine
        case (true, true): return .keepBoth
        }
    }

    /// Removes the persisted identity. Does not error if none exists.
    public func deleteIdentity() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unhandledStatus(status)
        }
    }
}
