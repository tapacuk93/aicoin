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
public struct WalletKeychainStore: Sendable {
    public let service: String
    public let account: String
    public let accessGroup: String?

    public init(
        service: String = "com.tarasmaslov.aicoinkit.wallet",
        account: String = "wallet-seed",
        accessGroup: String? = nil
    ) {
        self.service = service
        self.account = account
        self.accessGroup = accessGroup
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
    @discardableResult
    public func loadOrCreateIdentity() throws -> WalletIdentity {
        if let existing = try loadIdentity() { return existing }
        return try createIdentity()
    }

    /// Removes the persisted identity. Does not error if none exists.
    public func deleteIdentity() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unhandledStatus(status)
        }
    }
}
