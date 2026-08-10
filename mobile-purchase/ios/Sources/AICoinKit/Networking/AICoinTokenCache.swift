import Foundation

/// Caches a single issued API token in memory and re-issues a fresh one shortly before it expires
/// — issuance is a purely local signing operation (`WalletSigner.issueAPIToken`), but re-signing
/// on literally every AI-provider call would be wasteful for no benefit, since the token itself is
/// what's presented as the bearer credential.
///
/// Exposes `currentToken` as the exact `@Sendable () -> String?` shape `AICoinRouter.init`
/// expects, so wiring the two together is just `AICoinRouter(underlying: ..., tokenProvider:
/// tokenCache.currentToken)`.
public final class AICoinTokenCache: @unchecked Sendable {
    private let identity: WalletIdentity
    private let validFor: TimeInterval
    private let refreshMargin: TimeInterval
    private let lock = NSLock()
    private var cachedToken: String?
    private var cachedExpiresAt: Date?

    /// - Parameters:
    ///   - identity: The wallet whose private key signs issued tokens.
    ///   - validFor: How long a freshly-issued token should be valid for. Defaults to 30 days.
    ///   - refreshMargin: Re-issue a new token once the cached one is within this long of
    ///     expiring, rather than waiting for it to actually expire mid-request.
    public init(identity: WalletIdentity, validFor: TimeInterval = 30 * 24 * 3600, refreshMargin: TimeInterval = 300) {
        self.identity = identity
        self.validFor = validFor
        self.refreshMargin = refreshMargin
    }

    /// The current, non-expired (or freshly re-issued) token. Only returns `nil` if signing itself
    /// somehow fails (practically never, for Ed25519 over local bytes) — logged rather than
    /// propagated, since `AICoinRouter.tokenProvider` isn't `throws`.
    public func currentToken() -> String? {
        lock.lock()
        defer { lock.unlock() }

        if let cachedToken, let cachedExpiresAt, cachedExpiresAt.timeIntervalSinceNow > refreshMargin {
            return cachedToken
        }
        do {
            let token = try WalletSigner.issueAPIToken(identity: identity, validFor: validFor)
            cachedToken = token
            cachedExpiresAt = Date().addingTimeInterval(validFor)
            return token
        } catch {
            return nil
        }
    }

    /// Forces the next `currentToken()` call to issue a brand-new token — call this right after
    /// `WalletClient.revokeTokens(identity:)` so a stale (now server-revoked) token isn't reused
    /// from cache.
    public func invalidate() {
        lock.lock()
        defer { lock.unlock() }
        cachedToken = nil
        cachedExpiresAt = nil
    }
}
